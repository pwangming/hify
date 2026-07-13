# Hify 代码执行沙箱：最小镜像，仅标准库，无 pip 安装。
FROM python:3.12-slim
WORKDIR /app
COPY sandbox_server.py .
# 以非 root 运行（compose 再叠 read_only/cap_drop/no-new-privileges）
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin sandbox
USER sandbox
EXPOSE 8000
CMD ["python", "-u", "sandbox_server.py"]
