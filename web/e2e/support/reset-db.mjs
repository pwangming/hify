// 在 Playwright 启动之前跑：确保 postgres healthy，然后 drop+recreate hify_e2e（WITH FORCE 踢掉残留连接）。
// 必须早于后端启动——后端启动才跑 Flyway + 引导 admin。
import { execSync } from 'node:child_process'

const root = new URL('../../../', import.meta.url).pathname // 仓库根（docker-compose.yml 所在）
const run = (cmd) => execSync(cmd, { cwd: root, stdio: 'inherit' })

run('docker compose up -d --wait postgres')
// 两条 -c 分开执行：psql 会把同一条 -c 里的多条语句当成一个多语句字符串发送，
// 服务端对多语句字符串会隐式包一层事务块，而 DROP/CREATE DATABASE 不能在事务块内执行。
run('docker compose exec -T postgres psql -U hify -d hify -c "DROP DATABASE IF EXISTS hify_e2e WITH (FORCE);"')
run('docker compose exec -T postgres psql -U hify -d hify -c "CREATE DATABASE hify_e2e;"')
console.log('[reset-db] hify_e2e 已重建')
