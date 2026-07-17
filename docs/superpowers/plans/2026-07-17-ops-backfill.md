# 运维补账轮实现计划（pg_dump 备份 + 分区验证 + 优雅停机实证）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 兑现 deployment.md §2/§4 的三项运维承诺：pg_dump 每日备份（含真实恢复演练）、日志分区补建机制实证 + 文档补账、优雅停机正反场景实证。

**Architecture:** 备份 = host crontab 调 `docker exec` 容器内 pg_dump（-Fc、临时文件后原子改名、14 天轮换）；分区与停机 = 对本机 compose 全套形态做真实实验，证据落 `docs/self-check.md`。无 Java 改动、无 Flyway 迁移。

**Tech Stack:** bash + docker compose + psql + curl。spec：`docs/superpowers/specs/2026-07-17-ops-backfill-design.md`。

## Global Constraints

- **禁止改 Java 代码、禁止新增 Flyway 脚本**。实验若暴露 bug：停下，把证据整理后上报用户，不得静默修复（spec §4）。
- 实验全部跑在本机 compose 上；Task 3/4 需全套形态：`docker compose --profile app up -d --build`，4 容器 healthy 才开始。
- `deploy/.env` 不入库；实验用环境变量只经**临时 override 文件**注入（放 scratchpad，不放仓库），实验结束必须恢复原状并验证已恢复。
- 每个 Task 结束把实证证据（含真实测量值）追加到 `docs/self-check.md` 再 commit（项目纪律，见 memory self-check-per-step）。
- psql/pg_dump 一律 `docker exec` 进 `hify-postgres` 容器内跑，凭容器内 `$POSTGRES_USER`/`$POSTGRES_DB` 环境变量，不在宿主机放任何数据库密码。
- 本轮 shell 脚本不引入测试框架；每步验证 = 真实执行 + 核对输出（spec §4 验收口径）。
- compose 服务名：`postgres` / `sandbox` / `hify-server` / `nginx`；容器名：`hify-postgres` / `hify-sandbox` / `hify-server` / `hify-nginx`。`hify-server`、`nginx` 带 `profiles: [app]`，凡 stop/up 它们的命令都要带 `--profile app`。
- 仓库根有 `docker-compose.override.yml`（本地 sandbox 调试端口，gitignore）。凡用 `-f` 显式指定文件的命令**只针对 `hify-server` 且必须带 `--no-deps`**，避免误重建 sandbox。

---

### Task 1: 备份脚本 + runbook + package 白名单

**Files:**
- Create: `deploy/backup/pg-backup.sh`
- Create: `deploy/backup/README.md`
- Modify: `Makefile`（package 目标，约 83-97 行）

**Interfaces:**
- Produces: `pg-backup.sh` 环境变量约定——`HIFY_BACKUP_DIR`（默认 `/var/backups/hify`）、`HIFY_PG_CONTAINER`（默认 `hify-postgres`）、`HIFY_BACKUP_RETENTION_DAYS`（默认 `14`）；产物命名 `hify-YYYYMMDD-HHMMSS.dump`（-Fc 格式）。Task 2 的恢复演练消费该产物。

- [ ] **Step 1: 写备份脚本**

创建 `deploy/backup/pg-backup.sh`（完整内容）：

```bash
#!/usr/bin/env bash
# Hify PostgreSQL 每日备份（deployment.md §4：host crontab 调用，保留 14 天）。
# 一份 pg_dump 同时覆盖业务数据、向量与文档原始文件（全库状态都在 PG）。
# 可用环境变量覆盖（配置外化，CLAUDE.md）：
#   HIFY_BACKUP_DIR             备份目录，默认 /var/backups/hify
#   HIFY_PG_CONTAINER           postgres 容器名，默认 hify-postgres
#   HIFY_BACKUP_RETENTION_DAYS  保留天数，默认 14
set -euo pipefail

BACKUP_DIR="${HIFY_BACKUP_DIR:-/var/backups/hify}"
CONTAINER="${HIFY_PG_CONTAINER:-hify-postgres}"
RETENTION_DAYS="${HIFY_BACKUP_RETENTION_DAYS:-14}"

# 容器必须在运行：静默备份失败比不备份更危险，响亮退出让 cron 凭非零码感知
if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true; then
    echo "[pg-backup] $(date '+%F %T') 容器 $CONTAINER 未运行，备份中止" >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="$BACKUP_DIR/hify-$STAMP.dump"

# 中途失败不留半截文件冒充好备份
trap 'rm -f "$TARGET.tmp"' ERR

# 容器内 pg_dump：客户端/服务端版本必然一致；本地 socket 免密，密码不落宿主机。
# -Fc 自定义压缩格式（pg_restore 可选择性/并行恢复）。先写 .tmp，成功后原子改名——
# 目录里凡是 hify-*.dump 必是完整备份。
docker exec "$CONTAINER" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$TARGET.tmp"
mv "$TARGET.tmp" "$TARGET"
echo "[pg-backup] $(date '+%F %T') 备份完成：$TARGET（$(du -h "$TARGET" | cut -f1)）"

# 轮换：只删自有命名模式，不碰目录里其他文件。-mtime +N 意为「修改时间 > N 天」，
# 保留 14 天 → 删 ≥14 天的 → +13。
find "$BACKUP_DIR" -maxdepth 1 -name 'hify-*.dump' -mtime +"$((RETENTION_DAYS - 1))" -delete
# 顺手清理上次异常残留的 .tmp（超过 1 天必是垃圾）
find "$BACKUP_DIR" -maxdepth 1 -name 'hify-*.dump.tmp' -mtime +0 -delete
```

然后：`chmod +x deploy/backup/pg-backup.sh`

- [ ] **Step 2: 写运维 runbook**

创建 `deploy/backup/README.md`（完整内容；Task 2 演练后如有出入须回改此文件）：

````markdown
# Hify 数据库备份与恢复 runbook

一份 `pg_dump` 覆盖全部状态（业务数据、向量、文档原始文件同在 PostgreSQL）。
脚本 `pg-backup.sh` 随 `make package` 分发到部署机。

## 1. 安装每日备份（部署机一次性动作）

`crontab -e` 追加一行（每日 02:30，日志追加到备份目录下 backup.log）：

```
30 2 * * * /opt/hify/deploy/backup/pg-backup.sh >> /var/backups/hify/backup.log 2>&1
```

- 路径按实际部署位置调整；备份目录可用 `HIFY_BACKUP_DIR=... ` 前缀覆盖（默认 `/var/backups/hify`，目录会自动创建，跑 cron 的用户需对其有写权限且能执行 docker）。
- 保留 14 天，脚本自动轮换；只删 `hify-*.dump` 模式，不碰目录中其他文件。
- 验证已生效：次日 `ls -lh /var/backups/hify/` 应出现 `hify-YYYYMMDD-HHMMSS.dump`；
  `tail backup.log` 应有「备份完成」。**backup.log 连续出现失败或 dump 文件不再增长 = 立即排查。**

## 2. 恢复演练（每月一次，deployment.md §4 承诺）

全程用临时容器，不碰运行中的库，验证完即销毁：

```bash
# ① 取最新备份
DUMP=$(ls -t /var/backups/hify/hify-*.dump | head -n1) && echo "$DUMP"

# ② 起临时 pgvector 容器（独立端口 5544，匿名卷）
docker run -d --name hify-restore-drill -e POSTGRES_PASSWORD=drill \
  -p 127.0.0.1:5544:5432 pgvector/pgvector:pg16

# ③ 等就绪（重复执行直到输出 accepting connections）
docker exec hify-restore-drill pg_isready -U postgres

# ④ 建库并恢复（--no-owner/--no-privileges：演练容器无 hify 角色，跳过属主/授权还原）
docker exec hify-restore-drill createdb -U postgres hify
docker cp "$DUMP" hify-restore-drill:/tmp/hify.dump
docker exec hify-restore-drill pg_restore -U postgres -d hify --no-owner --no-privileges /tmp/hify.dump

# ⑤ 核对（与生产库对照，见下方核对清单）
docker exec hify-restore-drill psql -U postgres -d hify -tAc \
  "select count(*) from information_schema.tables where table_schema='public'"

# ⑥ 销毁（-v 连匿名卷一起删）
docker rm -fv hify-restore-drill
```

核对清单（临时库 vs 生产库，逐项一致才算演练通过）：

| 项 | 临时库命令（生产库把容器/用户换成 hify-postgres 内 $POSTGRES_USER） |
|---|---|
| public 表数量一致 | `select count(*) from information_schema.tables where table_schema='public'` |
| Flyway 迁移史完整 | `select count(*), max(version) from flyway_schema_history where success` |
| 用户数据在 | `select count(*) from sys_user`（>0） |
| 向量可查 | `select count(*) from kb_chunk where embedding is not null`，且相似度查询不报错：`select id from kb_chunk order by embedding <=> (select embedding from kb_chunk where embedding is not null limit 1) limit 3` |
| 分区子表齐全 | `select count(*) from pg_inherits join pg_class p on inhparent=p.oid where p.relname in ('llm_call_log','workflow_node_run')` |

## 3. 真实灾难恢复（思路同演练）

新机器上：起 postgres 容器（同 compose 配置）→ 对空库执行 ②④ 的恢复步骤
（目标换成正式容器与 `$POSTGRES_USER`）→ 起 server 验证登录与数据 → 切流量。
恢复点 = 最近一次备份（最多丢 24 小时数据，deployment.md 既定口径）。
````

- [ ] **Step 3: package 白名单加 backup 目录**

修改 `Makefile` package 目标，在 nginx 拷贝行之后插入两行。

旧（两行上下文定位）：

```make
	 cp deploy/.env.example "$$STAGE/deploy/"; \
	 cp deploy/nginx/Dockerfile deploy/nginx/nginx.conf deploy/nginx/gen-self-signed-cert.sh "$$STAGE/deploy/nginx/"; \
	 tar -czf $(DIST_DIR)/hify-$$VER.tar.gz -C $(DIST_DIR) "hify-$$VER"; \
```

新：

```make
	 cp deploy/.env.example "$$STAGE/deploy/"; \
	 cp deploy/nginx/Dockerfile deploy/nginx/nginx.conf deploy/nginx/gen-self-signed-cert.sh "$$STAGE/deploy/nginx/"; \
	 mkdir -p "$$STAGE/deploy/backup"; \
	 cp deploy/backup/pg-backup.sh deploy/backup/README.md "$$STAGE/deploy/backup/"; \
	 tar -czf $(DIST_DIR)/hify-$$VER.tar.gz -C $(DIST_DIR) "hify-$$VER"; \
```

- [ ] **Step 4: 真实跑一次备份（成功路径）**

前置：`docker compose up -d postgres` 且 `docker compose ps postgres` 显示 healthy。

```bash
SCRATCH=/tmp/claude-1000/-home-wang-playlab-hify/99e2c1b5-0d2f-401a-8665-e87a5f582419/scratchpad/backup-test
HIFY_BACKUP_DIR="$SCRATCH" bash deploy/backup/pg-backup.sh
ls -lh "$SCRATCH"
```

预期：输出 `[pg-backup] ... 备份完成：.../hify-<时间戳>.dump（<大小>）`；目录中恰有一个 `.dump` 文件、无 `.tmp` 残留；退出码 0。文件大小应为百 KB~MB 级（开发库有 25 表 + 向量数据，若只有几 KB 说明 dump 不完整，停下排查）。

- [ ] **Step 5: 验证失败路径响亮退出**

```bash
HIFY_PG_CONTAINER=no-such-container HIFY_BACKUP_DIR="$SCRATCH" bash deploy/backup/pg-backup.sh; echo "exit=$?"
```

预期：stderr 输出 `容器 no-such-container 未运行，备份中止`，`exit=1`，目录中无新文件。

- [ ] **Step 6: 验证 package 白名单**

```bash
make package
tar -tzf dist/hify-*.tar.gz | grep backup
```

预期（构建需几分钟）：列出 `hify-<ver>/deploy/backup/pg-backup.sh` 与 `hify-<ver>/deploy/backup/README.md` 两行。

- [ ] **Step 7: Commit**

```bash
git add deploy/backup/pg-backup.sh deploy/backup/README.md Makefile
git commit -m "feat(ops): pg_dump 每日备份脚本与恢复 runbook；随 make package 分发"
```

---

### Task 2: 恢复演练（真实执行一次）

**Files:**
- Modify: `deploy/backup/README.md`（仅当演练发现步骤有误时勘误）
- Modify: `docs/self-check.md`（追加演练记录）

**Interfaces:**
- Consumes: Task 1 的 `pg-backup.sh` 与其产物 `hify-*.dump`（`$SCRATCH/backup-test/` 下已有）。

- [ ] **Step 1: 照 runbook 起临时容器并恢复**

严格按 `deploy/backup/README.md` §2 的 ①-④ 执行，仅 ① 的目录换成本地测试目录：

```bash
SCRATCH=/tmp/claude-1000/-home-wang-playlab-hify/99e2c1b5-0d2f-401a-8665-e87a5f582419/scratchpad/backup-test
DUMP=$(ls -t "$SCRATCH"/hify-*.dump | head -n1) && echo "$DUMP"
docker run -d --name hify-restore-drill -e POSTGRES_PASSWORD=drill \
  -p 127.0.0.1:5544:5432 pgvector/pgvector:pg16
sleep 5 && docker exec hify-restore-drill pg_isready -U postgres
docker exec hify-restore-drill createdb -U postgres hify
docker cp "$DUMP" hify-restore-drill:/tmp/hify.dump
docker exec hify-restore-drill pg_restore -U postgres -d hify --no-owner --no-privileges /tmp/hify.dump
```

预期：pg_restore 退出码 0（个别 `WARNING` 可接受，`ERROR` 不可）。

- [ ] **Step 2: 逐项核对（临时库 vs 源库）**

对照跑 README §2 核对清单的 5 项。临时库用 `docker exec hify-restore-drill psql -U postgres -d hify -tAc "<SQL>"`；源库用 `docker exec hify-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "<SQL>"'`。

预期：表数量、flyway 行数与 max(version)、分区子表数三项两边完全一致；sys_user > 0；向量相似度查询在临时库返回行且不报错（若源库 kb_chunk 为空则记 N/A——按既往轮次开发库应有数据）。任何一项不一致：停下，保留现场并排查（先怀疑 dump/restore 命令，再怀疑脚本）。

- [ ] **Step 3: 销毁临时容器**

```bash
docker rm -fv hify-restore-drill
```

- [ ] **Step 4: 回写文档 + self-check + Commit**

- 若步骤与 README 有出入（命令参数、等待时间等），修正 `deploy/backup/README.md`；
- `docs/self-check.md` 末尾追加一节 `## 2026-07-17 运维补账 Task 1-2：备份与恢复演练`，写明：备份文件大小、pg_restore 退出码、5 项核对的**实测数值**（两边对照）、发现并回改的出入（若无写"无"）。

```bash
git add deploy/backup/README.md docs/self-check.md
git commit -m "docs(ops): 首次恢复演练实证通过，核对数据记入 self-check"
```

---

### Task 3: 分区补建机制实证 + 运维矩阵补账

**Files:**
- Modify: `docs/architecture/deployment.md`（§4 运维矩阵：改备份行 + 加日志分区行）
- Modify: `docs/self-check.md`（追加实证记录）

**Interfaces:**
- Consumes: 全套形态运行中（`docker compose --profile app up -d --build`，4 容器 healthy）；`PartitionMaintainer` / `WorkflowPartitionMaintainer` 的既有行为（启动时向前补建当月+3 个月，`if not exists` 幂等）。

- [ ] **Step 1: 记录当前分区清单**

```bash
PSQL='docker exec hify-postgres sh -c'
$PSQL 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select c.relname from pg_inherits i join pg_class c on i.inhrelid=c.oid join pg_class p on i.inhparent=p.oid where p.relname in ('\''llm_call_log'\'','\''workflow_node_run'\'') order by 1"'
```

预期：两表各 6 个月度子表（2026_07 ~ 2026_12）。记下完整清单。

- [ ] **Step 2: 确认目标分区为空后 drop（制造「缺分区」现场）**

选未来分区 `llm_call_log_2026_10` 与 `workflow_node_run_2026_10`（在当月+3 覆盖范围内，重启必被补建）：

```bash
$PSQL 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select (select count(*) from llm_call_log_2026_10), (select count(*) from workflow_node_run_2026_10)"'
```

预期 `0|0`（未来月份必为空）。**非 0 则立即停下上报，禁止 drop。** 为 0 才执行：

```bash
$PSQL 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "drop table llm_call_log_2026_10; drop table workflow_node_run_2026_10"'
```

再跑 Step 1 的查询确认两个分区已消失（各剩 5 个）。

- [ ] **Step 3: 重启 server，验证自动重建**

```bash
docker compose --profile app restart hify-server
until [ "$(docker inspect -f '{{.State.Health.Status}}' hify-server)" = healthy ]; do sleep 3; done
docker logs hify-server --since 3m | grep 分区
```

预期：日志含 `llm_call_log 分区已确保至 2026-10`（workflow 侧有对应日志行）。再跑 Step 1 查询：两表各恢复 6 个子表，`*_2026_10` 回来了。**若未重建：这是会在 2026-11 打爆写入的真 bug，停下整理证据上报，本 Task 终止。**

- [ ] **Step 4: deployment.md 运维矩阵补账**

修改 `docs/architecture/deployment.md` §4 表格。

旧：

```markdown
| 备份 | host crontab：`pg_dump` 每日一次（业务+向量一份搞定），保留 14 天，**每月做一次恢复演练** |
```

新（改备份行，并在其后新增日志分区行）：

```markdown
| 备份 | host crontab：`pg_dump` 每日一次（业务+向量一份搞定），保留 14 天，**每月做一次恢复演练**；脚本与恢复 runbook 见 `deploy/backup/`（随 make package 分发） |
| 日志分区 | `llm_call_log` / `workflow_node_run` 建表即按月分区；应用内 Maintainer 启动时及每月 1 日自动补建未来 3 个月（2026-07 实证：drop 空分区→重启→自动重建）。清理 = 手动 drop 旧分区；当前无自动清理，等数据量需要清理时再定保留口径 |
```

- [ ] **Step 5: self-check + Commit**

`docs/self-check.md` 追加一节 `## 2026-07-17 运维补账 Task 3：分区补建实证`：drop 前后与重启后的分区清单（实测）、server 日志关键行原文。

```bash
git add docs/architecture/deployment.md docs/self-check.md
git commit -m "docs(ops): 分区补建机制实证通过；运维矩阵补备份 runbook 与日志分区条目"
```

---

### Task 4: 优雅停机正反场景实证

**Files:**
- Modify: `docs/self-check.md`（追加实证记录）
- 临时（不入库）：`$SCRATCH/exp-sandbox-timeout.yml`、`$SCRATCH/*.json`

**Interfaces:**
- Consumes: 全套形态 4 容器 healthy；API——`POST /api/v1/identity/login`（body `{username,password}`，token 在 `data.token`，成功 code 200）、`POST /api/v1/app/apps`（`{name,type:"workflow"}`，id 在 `data.id`，字符串）、`PUT /api/v1/workflow/apps/{appId}/draft`（`{graph:{nodes,edges}}`）、`POST /api/v1/workflow/apps/{appId}/runs`；管理员凭证在 `deploy/.env` 的 `HIFY_ADMIN_USERNAME`/`HIFY_ADMIN_PASSWORD`。所有请求经 nginx：`https://localhost`（自签证书，curl 加 `-k`）。
- 关键配置（被测对象）：`server.shutdown=graceful` + `timeout-per-shutdown-phase: 50s`（application.yml）+ `stop_grace_period: 60s`（compose）；`ZombieRunResetter` 启动置遗留 running 为 failed。

- [ ] **Step 1: 临时调大沙箱超时（override 文件注入，不改任何仓库文件）**

```bash
SCRATCH=/tmp/claude-1000/-home-wang-playlab-hify/99e2c1b5-0d2f-401a-8665-e87a5f582419/scratchpad
cat > "$SCRATCH/exp-sandbox-timeout.yml" <<'EOF'
services:
  hify-server:
    environment:
      HIFY_SANDBOX_EXEC_TIMEOUT_MS: "30000"
      HIFY_SANDBOX_READ_TIMEOUT_MS: "32000"
EOF
docker compose -f docker-compose.yml -f "$SCRATCH/exp-sandbox-timeout.yml" --profile app up -d --no-deps hify-server
until [ "$(docker inspect -f '{{.State.Health.Status}}' hify-server)" = healthy ]; do sleep 3; done
docker exec hify-server env | grep HIFY_SANDBOX
```

预期：输出含 `HIFY_SANDBOX_EXEC_TIMEOUT_MS=30000` 与 `HIFY_SANDBOX_READ_TIMEOUT_MS=32000`。
（为什么：沙箱默认执行超时 5s，sleep(20) 会先被沙箱掐掉；read-timeout 须 = exec + 余量，见 application.yml 注释。`--no-deps` 防止顺带重建 sandbox。）

- [ ] **Step 2: 登录并建两个 workflow 应用**

```bash
set -a; source deploy/.env; set +a
TOKEN=$(curl -sk https://localhost/api/v1/identity/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$HIFY_ADMIN_USERNAME\",\"password\":\"$HIFY_ADMIN_PASSWORD\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
AUTH="Authorization: Bearer $TOKEN"
POS_ID=$(curl -sk -X POST https://localhost/api/v1/app/apps -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"停机实验-正向","type":"workflow"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')
NEG_ID=$(curl -sk -X POST https://localhost/api/v1/app/apps -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"停机实验-反向","type":"workflow"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')
echo "POS_ID=$POS_ID NEG_ID=$NEG_ID"
```

预期：两个 id 均为数字字符串。（登录失败先核对 deploy/.env 凭证，仍失败问用户。）

- [ ] **Step 3: 存两份草稿**

正向：单代码节点 sleep(20)（总时长 ~20s < 50s 预算）：

```bash
cat > "$SCRATCH/pos-graph.json" <<'EOF'
{"graph":{"nodes":[
  {"id":"start","type":"start","data":{"inputs":[]}},
  {"id":"c1","type":"code","data":{"code":"def main():\n    import time\n    time.sleep(20)\n    return {\"slept\": \"20\"}","inputs":{}}},
  {"id":"end","type":"end","data":{"outputs":[{"name":"slept","value":"{{c1.slept}}"}]}}
],"edges":[{"source":"start","target":"c1"},{"source":"c1","target":"end"}]}}
EOF
curl -sk -X PUT "https://localhost/api/v1/workflow/apps/$POS_ID/draft" -H "$AUTH" \
  -H 'Content-Type: application/json' -d @"$SCRATCH/pos-graph.json"
```

反向：三个 sleep(25) 节点串联（总时长 ~75s > 50s 预算；单节点 25s < 30s 沙箱上限）：

```bash
cat > "$SCRATCH/neg-graph.json" <<'EOF'
{"graph":{"nodes":[
  {"id":"start","type":"start","data":{"inputs":[]}},
  {"id":"c1","type":"code","data":{"code":"def main():\n    import time\n    time.sleep(25)\n    return {\"step\": \"1\"}","inputs":{}}},
  {"id":"c2","type":"code","data":{"code":"def main():\n    import time\n    time.sleep(25)\n    return {\"step\": \"2\"}","inputs":{}}},
  {"id":"c3","type":"code","data":{"code":"def main():\n    import time\n    time.sleep(25)\n    return {\"step\": \"3\"}","inputs":{}}},
  {"id":"end","type":"end","data":{"outputs":[{"name":"step","value":"{{c3.step}}"}]}}
],"edges":[{"source":"start","target":"c1"},{"source":"c1","target":"c2"},{"source":"c2","target":"c3"},{"source":"c3","target":"end"}]}}
EOF
curl -sk -X PUT "https://localhost/api/v1/workflow/apps/$NEG_ID/draft" -H "$AUTH" \
  -H 'Content-Type: application/json' -d @"$SCRATCH/neg-graph.json"
```

预期：两次 PUT 均返回 `"code":200`。

- [ ] **Step 4: 正向场景——SIGTERM 后等收尾**

```bash
curl -sk -X POST "https://localhost/api/v1/workflow/apps/$POS_ID/runs" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"inputs":{}}' > "$SCRATCH/pos-run.json" 2>&1 &
sleep 3
time docker compose --profile app stop hify-server
cat "$SCRATCH/pos-run.json"
docker exec hify-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select id,status from workflow_run order by id desc limit 1"'
```

预期（三项全中才算过）：
1. `time` 实测 **约 17~25s**——不是立即（说明在等收尾），也不是 50s（说明等到即走）；
2. `pos-run.json` 是完整响应，`data.status` 为 `succeeded`（服务器发完响应才退）；
3. 库中最新 run `status=succeeded`。

记录实测秒数。然后重启并确认无自愈动作（正向不产生僵尸）：

```bash
docker compose -f docker-compose.yml -f "$SCRATCH/exp-sandbox-timeout.yml" --profile app up -d --no-deps hify-server
until [ "$(docker inspect -f '{{.State.Health.Status}}' hify-server)" = healthy ]; do sleep 3; done
docker logs hify-server --since 2m | grep 自愈 || echo "无自愈日志（预期）"
```

预期：输出 `无自愈日志（预期）`。

- [ ] **Step 5: 反向场景——超时强杀 + ZombieRunResetter 衔接**

```bash
curl -sk -m 120 -X POST "https://localhost/api/v1/workflow/apps/$NEG_ID/runs" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"inputs":{}}' > "$SCRATCH/neg-run.json" 2>&1 &
sleep 5
time docker compose --profile app stop hify-server
docker exec hify-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select id,status from workflow_run order by id desc limit 1"'
```

预期：
1. `time` 实测 **约 50~58s**（等满 50s 预算后 Spring 强制退出；若 ≥60s 说明是 docker SIGKILL 兜的底而非 Spring，如实记录——这也是有效发现）；
2. 库中最新 run 卡在 `status=running`（僵尸产生）。

重启验证自愈衔接：

```bash
docker compose -f docker-compose.yml -f "$SCRATCH/exp-sandbox-timeout.yml" --profile app up -d --no-deps hify-server
until [ "$(docker inspect -f '{{.State.Health.Status}}' hify-server)" = healthy ]; do sleep 3; done
docker logs hify-server --since 2m | grep 自愈
docker exec hify-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select id,status from workflow_run order by id desc limit 1"'
```

预期：日志含 `workflow 启动自愈：重置 N 条遗留 running 记录为 failed`（N≥1，典型为 2 = 1 条 run + 1 条 node_run）；库中该 run `status=failed`。**任何一步与预期不符 = 可能的真 bug，停下整理证据上报，不修复。**

- [ ] **Step 6: 撤实验环境，恢复原状**

```bash
docker compose --profile app up -d --no-deps hify-server
until [ "$(docker inspect -f '{{.State.Health.Status}}' hify-server)" = healthy ]; do sleep 3; done
docker exec hify-server env | grep HIFY_SANDBOX || echo "实验变量已清除（预期）"
curl -sk -X DELETE "https://localhost/api/v1/app/apps/$POS_ID" -H "$AUTH"
curl -sk -X DELETE "https://localhost/api/v1/app/apps/$NEG_ID" -H "$AUTH"
rm -f "$SCRATCH/exp-sandbox-timeout.yml"
```

预期：`实验变量已清除（预期）`；两个实验应用删除返回 code 200（若因 token 过期失败，重新登录后删）。

- [ ] **Step 7: self-check + Commit**

`docs/self-check.md` 追加一节 `## 2026-07-17 运维补账 Task 4：优雅停机正反实证`，必须含：正向/反向 stop 的实测秒数、正向响应 status、反向自愈日志原文与前后状态、结论一句话（「SIGTERM 后等待 workflow 收尾 + 超时强杀由启动自愈兜底，链路实证完整」或如实写偏差）、实验环境已恢复的证据。

```bash
git add docs/self-check.md
git commit -m "docs(ops): 优雅停机正反场景实证记录（等收尾/强杀+启动自愈衔接）"
```
