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
