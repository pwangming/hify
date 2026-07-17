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
