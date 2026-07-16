#!/usr/bin/env bash
# 生成本地自签证书（10 年期）到 deploy/nginx/certs/，供 nginx 容器 volume 挂载。
# 产物 gitignore 不入库；生产换真证书 = 用同名文件替换后重启 nginx 容器。
# 浏览器首次访问会提示"不安全"，手动信任一次即可（自签证书的正常现象）。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p certs
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
  -keyout certs/hify.key -out certs/hify.crt
echo "已生成: $(pwd)/certs/hify.crt 与 hify.key"
