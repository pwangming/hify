# 运维补账轮设计（第 2 轮）：pg_dump 备份 + 分区验证 + 优雅停机实证

日期：2026-07-17
状态：已与用户逐节确认
背景：deploy 容器化收尾轮之后的既定第 2 轮，兑现 deployment.md §2/§4 承诺但尚未落地/实证的三件运维事项。

## 0. 探索结论（改变了本轮范围）

原任务单第 2 项「日志表按月分区」经探索确认**建表时已完成**，无存量搬迁工作：

- `llm_call_log`（V12）、`workflow_node_run`（V21）第一天即按 create_time 月 RANGE 分区，
  主键含分区键，初始预建 2026-07 ~ 2026-12 六个月分区；
- 应用内已有 `PartitionMaintainer` / `WorkflowPartitionMaintainer`：启动 + 每月 1 日 00:30
  向前补建 3 个月分区，`if not exists` 幂等；
- `message` / `kb_chunk` / `workflow_run` 不分区是 database-standards §大表分级的既定决策
  （触发线：单表 > 3000 万行或相关查询 P95 翻倍），本轮不碰。

**真实缺口**：① 补建机制从未真正建过分区（预建分区未耗尽，历次运行均为幂等空跑），
需实证；② 分区只有「建」没有「删」，清理口径未归档。已拍板：本轮只做验证 + 补文档，
**不写自动清理代码**（数据量几年内不需清理；计费流水保留期无依据拍板；YAGNI）。

## 1. pg_dump 每日备份（新增交付物）

### 1.1 备份脚本 `deploy/backup/pg-backup.sh`

| 决策 | 内容 | 为什么 |
|---|---|---|
| 执行方式 | `docker exec hify-postgres pg_dump -Fc`，容器内跑 | 客户端/服务端版本永远匹配；容器内本地 socket trust 免密，**密码不落宿主机任何文件**；宿主机零新依赖 |
| 格式 | `-Fc` 自定义压缩格式，文件名 `hify-YYYYMMDD-HHMMSS.dump` | 向量数据文本化臃肿，-Fc 自带压缩；pg_restore 支持选择性/并行恢复 |
| 原子写 | 先写 `.tmp`，pg_dump 成功后 `mv` 为正式名 | 防半截坏文件冒充好备份、轮换删光好备份的隐蔽事故；正式名文件必然完整 |
| 保留 | 删除 14 天前的备份，`find -name 'hify-*.dump' -mtime +13 -delete` | 14 天为 deployment.md 既定口径；只匹配自有模式，不误删目录中其他文件 |
| 目录 | 默认 `/var/backups/hify`，环境变量 `HIFY_BACKUP_DIR` 覆盖 | Linux 惯例位置；配置外化（CLAUDE.md 指令） |
| 纪律 | `set -euo pipefail`；容器未运行报错退出；非零退出码留给 cron 感知 | 失败要响，不要静默 |

### 1.2 运维 runbook `deploy/backup/README.md`

- 现成 crontab 一行（每日 02:30，stdout/stderr 追加到 `$HIFY_BACKUP_DIR/backup.log`），
  部署人 `crontab -e` 手动粘贴一次。**不写 install-cron.sh**：一台机器一次性动作，
  安装脚本引入重复安装/幂等/卸载等待测逻辑，风险收益倒挂。
- 恢复演练完整步骤（临时容器流程，见 1.3）+ 每月演练 checklist（deployment.md 承诺每月一次）。

### 1.3 恢复演练（本轮真实跑一次作为验收）

不碰运行中的库，全程临时容器：

1. 跑备份脚本产出 dump；
2. `docker run` 临时 pgvector/pg16 容器（独立端口与数据卷）；
3. `pg_restore` 灌入临时库；
4. 核对：表数量一致、`flyway_schema_history` 完整、`sys_user` 有行、
   `kb_chunk` 向量列可做相似度查询、`llm_call_log`/`workflow_node_run` 分区子表齐全；
5. 销毁临时容器；步骤据实回写 README。

为什么必须实操：没跑过的恢复步骤等于没有备份；临时容器恢复流程本身就是真实灾难时的操作预演。

### 1.4 Makefile

`package` 白名单追加拷贝 `deploy/backup/`（脚本 + README 随包发布到部署机）。
落 `deploy/` 而非 `scripts/`：备份是部署机上的生产运维件，`scripts/` 定位是不进 CI 的开发侧手跑工具。

## 2. 日志分区：验证 + 补文档（无新代码、无 Flyway）

### 2.1 验证实验

在开发库：确认某**未来空分区**（如 `llm_call_log_2026_10`）行数为 0 → `drop table` →
重启 server → 验证 `PartitionMaintainer` 启动兜底将其重建（`workflow_node_run` 同法一次）。

为什么：补建机制第一次真干活在 2026-10，若届时有 bug，11 月起 token 流水 INSERT
直接失败（分区表缺分区即写入报错），伤核心聊天链路。删空的未来分区零风险
（无数据；Maintainer 建不回来则手工一条 create 即恢复，且恰好提前暴露 bug），
触发的就是被测行为本身。

### 2.2 文档补账

deployment.md §4 运维矩阵新增「日志分区」行：自动补建机制（启动 + 每月 1 日，向前 3 个月）、
清理 = 手动 drop 旧分区、当前无自动清理、需要清理时再定保留口径。

## 3. 优雅停机行为实证（正反两场景）

停机链路（被测对象）：`docker compose stop server` → SIGTERM → Spring graceful
（拒新请求，等在途请求最多 50s）→ 超时则强行退出；Docker `stop_grace_period: 60s`
后 SIGKILL 兜底。50 < 60 保证 Spring 有序退出先于强杀。工作流同步执行（跑在请求线程里），
故「等在途请求」=「等工作流收尾」。

实验前提：环境变量临时调大沙箱超时（`HIFY_SANDBOX_EXEC_TIMEOUT_MS=30000`，
`HIFY_SANDBOX_READ_TIMEOUT_MS` 同步 ≥ 32000），实验后移除，零代码/配置文件改动。
选 sleep 代码节点造长任务：时长精确可控；真实 LLM 时长不可复现；HTTP 节点访问
本地慢接口被 SSRF 防护拦截（白名单仅 MCP），外网慢接口（httpbin）已知不稳定。

| 场景 | 构造 | 预期（全部核对才算过） |
|---|---|---|
| 正向：预算内收尾 | 单代码节点 `time.sleep(20)`，运行中 stop | 容器不立即退出；约 20s 后退出（等到收尾而非傻等 50s）；`workflow_run` 落终态；重启后 ZombieRunResetter 报 0 条重置 |
| 反向：超时强杀 + 自愈衔接 | 三个 `sleep(25)` 节点串 ~75s，运行中 stop | 等满 ~50s 强退；库中留 `running` 僵尸；重启后 ZombieRunResetter 日志报重置 1 条，库中状态变 `failed` |

正向证明优雅停机配置生效（尽量不产生烂摊子），反向证明强杀后兜底链路完整
（真产生了也收得掉）——两者合起来才是 deployment.md 的完整语义，
「与启动自愈的衔接」只有反向场景能实证。

结果与实测时间点记入 `docs/self-check.md`（无测试代码守护的行为，实测记录即回归基线）。
实测与 deployment.md §2 描述不符则修文档；发现真 bug 单独上报再议，不静默夹带修复。

## 4. 交付物与边界

**交付**：
- `deploy/backup/pg-backup.sh`、`deploy/backup/README.md`（新增）
- `Makefile` package 白名单 +1 行
- `docs/architecture/deployment.md` 运维矩阵更新（备份行指向 runbook；新增日志分区行）
- 三组实验实证记录 → `docs/self-check.md`

**不做**：Java 改动、Flyway 迁移、自动分区清理、cron 安装脚本、第 5 个容器、异机备份、
`message`/`kb_chunk`/`workflow_run` 分区。

**验收口径**：shell 脚本不引入测试框架（技术栈外依赖），验收 = 真实跑通
备份 → 临时容器恢复 → 数据核对，加正反停机实验全预期命中，全部留档 self-check.md。
