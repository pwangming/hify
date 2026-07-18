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

  // 同步无工具 → JSON 终答（非 SSE）
  const syncRes = await fetch(`${base}/v1/chat/completions`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ model: 'stub', messages: [{ role: 'user', content: 'hi' }] }),
  })
  assert.match(syncRes.headers.get('content-type'), /application\/json/, '同步请求必须回 JSON')
  const sync = await syncRes.json()
  assert.equal(sync.choices[0].message.content, '这是知识库助手的固定测试回答。', '同步须回固定答案')
  assert.equal(sync.choices[0].finish_reason, 'stop')
  assert.ok(sync.usage.total_tokens > 0, '同步响应须带 usage')

  // 带 tools 声明 → 固定 tool_calls
  const tc = await (await fetch(`${base}/v1/chat/completions`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      model: 'stub',
      messages: [{ role: 'user', content: 'hi' }],
      tools: [{ type: 'function' }],
    }),
  })).json()
  assert.equal(tc.choices[0].finish_reason, 'tool_calls')
  assert.equal(tc.choices[0].message.tool_calls[0].function.name, 'mcpdemo__get_current_time')
  assert.equal(tc.choices[0].message.tool_calls[0].function.arguments, '{"timezone":"Asia/Shanghai"}')

  // messages 已含 role:tool → 终答（即使仍带 tools，优先级在 tool_calls 分支之上）
  const fin = await (await fetch(`${base}/v1/chat/completions`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      model: 'stub',
      messages: [{ role: 'user', content: 'hi' }, { role: 'tool', content: 'x' }],
      tools: [{ type: 'function' }],
    }),
  })).json()
  assert.equal(fin.choices[0].message.content, '工具已调用完成，这是最终回答。', '带工具结果须回终答')
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
