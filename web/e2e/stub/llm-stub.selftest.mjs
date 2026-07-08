// 独立自测：起桩 → 打两个接口 → 断言维度/SSE → 退出码表示成败。用 `node llm-stub.selftest.mjs` 跑。
import { spawn } from 'node:child_process'
import assert from 'node:assert/strict'

const port = 8091
const proc = spawn('node', [new URL('./llm-stub.mjs', import.meta.url).pathname], {
  env: { ...process.env, STUB_PORT: String(port) }, stdio: 'inherit',
})
const base = `http://localhost:${port}`
try {
  await waitHealth()
  // embeddings：必须 1024 维
  const emb = await (await fetch(`${base}/v1/embeddings`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ input: '任意文本', model: 'stub' }),
  })).json()
  assert.equal(emb.data[0].embedding.length, 1024, 'embedding 必须 1024 维')
  // chat：SSE 流含答案且以 [DONE] 收尾
  const res = await fetch(`${base}/v1/chat/completions`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ model: 'stub', stream: true, messages: [{ role: 'user', content: 'hi' }] }),
  })
  const text = await res.text()
  assert.match(text, /data: /, 'chat 必须是 SSE')
  assert.match(text, /这是知识库助手的固定测试回答/, '必须含固定答案')
  assert.match(text, /\[DONE\]/, '必须以 [DONE] 收尾')
  console.log('STUB SELFTEST PASS')
} finally {
  proc.kill()
}

async function waitHealth() {
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(`${base}/health`)).ok) return } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 100))
  }
  throw new Error('stub 未就绪')
}
