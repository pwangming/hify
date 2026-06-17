# ============================================================================
# Hify 本地开发 Makefile —— 所有常用操作的统一入口
# ----------------------------------------------------------------------------
# Makefile 的核心模型只有三样东西：
#   目标(target):  依赖(prerequisites)
#   <TAB>命令              ← 行首必须是制表符 Tab，不能是空格（Make 最经典的坑）
# 执行 `make 目标` 时，Make 会先按需执行它的“依赖”，再跑它的命令。
#
# 常用：make start / stop / restart / build / clean / package（直接 `make` 看帮助）
# ============================================================================

SHELL := /bin/bash        # 指定用 bash 执行 recipe（默认可能是 sh，行为有差异）

# 变量定义与引用：定义用 :=，引用用 $(变量名)
SERVER_DIR := server
WEB_DIR    := web
DIST_DIR   := dist

# 默认目标：不带参数执行 `make` 时跑哪个（这里是 help）
.DEFAULT_GOAL := help
# .PHONY：声明这些目标“不是真实文件名”。否则若恰好存在同名文件/目录，Make 会以为
# “目标已是最新”而跳过。我们的 start/build 等都是动作而非文件，故全列为 PHONY。
.PHONY: help start stop restart build build-backend build-web clean package

help:
	@echo "Hify 可用命令："
	@echo "  make start     启动（检查 DB → 构建并起后端 → 健康检查 → 起前端）"
	@echo "  make stop      停止前后端（优雅停止，超时 SIGKILL）"
	@echo "  make restart   重启（先 stop 再 start）"
	@echo "  make build     构建后端(jar) + 前端(dist)"
	@echo "  make clean     清理构建产物（server/target、web/dist、dist）"
	@echo "  make package   打包可分发 tar.gz 到 dist/"

# 行首 @ 表示“执行但不回显这行命令本身”，输出更干净。
# start/stop 只是转调脚本——把“入口”和“实现”分开：脚本管细节，Make 管编排。
start:
	@./start.sh

stop:
	@./stop.sh

# 重启：先停再起。`-` 前缀表示“忽略这条命令的失败”——没在运行时 stop 不应阻断后续 start。
restart:
	@-./stop.sh
	@./start.sh

# build 依赖 build-backend 与 build-web：执行 `make build` 会按序先构建后端再构建前端。
build: build-backend build-web

build-backend:
	@echo "==> 构建后端（mvn clean package）..."
	mvn -B -f $(SERVER_DIR)/pom.xml clean package

build-web:
	@echo "==> 构建前端（pnpm install + build）..."
	cd $(WEB_DIR) && pnpm install && pnpm build

clean:
	@echo "==> 清理构建产物..."
	mvn -B -f $(SERVER_DIR)/pom.xml clean
	rm -rf $(WEB_DIR)/dist $(DIST_DIR)

# 打包：先 build 出最新产物，再把 后端 jar + 前端静态产物 + 部署配置 组装成
# dist/hify-<版本>.tar.gz。整段是一条 shell（用 \ 续行），注意：
#   - $$ 在 Makefile 里代表传给 shell 的单个 $（$$VAR 即 shell 变量 VAR）
#   - 版本号从构建出的 jar 文件名提取，不硬编码，避免与 pom 里父版本混淆
package: build
	@echo "==> 打包可分发产物..."
	@JAR=$$(ls -t $(SERVER_DIR)/target/*.jar | grep -v '\.original$$' | head -n1); \
	 if [ -z "$$JAR" ]; then echo "未找到后端 jar，先 make build" >&2; exit 1; fi; \
	 VER=$$(basename "$$JAR" .jar | sed 's/^hify-server-//'); \
	 STAGE=$(DIST_DIR)/hify-$$VER; \
	 rm -rf "$$STAGE" && mkdir -p "$$STAGE/web"; \
	 cp "$$JAR" "$$STAGE/hify-server.jar"; \
	 cp -r $(WEB_DIR)/dist/. "$$STAGE/web/"; \
	 [ -f docker-compose.yml ] && cp docker-compose.yml "$$STAGE/" || true; \
	 [ -d deploy ] && cp -r deploy "$$STAGE/" || true; \
	 tar -czf $(DIST_DIR)/hify-$$VER.tar.gz -C $(DIST_DIR) "hify-$$VER"; \
	 rm -rf "$$STAGE"; \
	 echo "✓ 已生成 $(DIST_DIR)/hify-$$VER.tar.gz"
