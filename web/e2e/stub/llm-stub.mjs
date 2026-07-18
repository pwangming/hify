// 假 LLM 桩：OpenAI 兼容 /v1/embeddings + /v1/chat/completions。
// 铁律「傻」：对任何输入返回同一定值——不看请求内容、无测试特判分支。
// 固定向量令「问题向量 == 分段向量」→ 余弦相似度=1 → 必过 K4 阈值 0.3。
import http from 'node:http'

const PORT = Number(process.env.STUB_PORT || 8090)
const STUB_ANSWER = '这是知识库助手的固定测试回答。'
const STUB_VEC = Array(1024).fill(0.1) // 精确 1024 维
const AGENT_TOOL_NAME = 'mcpdemo__get_current_time'
const AGENT_FINAL = '工具已调用完成，这是最终回答。'

function sendJson(res, obj) {
  res.writeHead(200, { 'content-type': 'application/json' })
  res.end(JSON.stringify(obj))
}

function completion(message, finishReason) {
  return {
    id: 'chatcmpl-stub', object: 'chat.completion', model: 'stub-chat',
    choices: [{ index: 0, message, finish_reason: finishReason }],
    usage: { prompt_tokens: 10, completion_tokens: 12, total_tokens: 22 },
  }
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200).end('ok')
    return
  }
  if (req.method === 'POST' && req.url === '/v1/embeddings') {
    readBody(req).then(() => {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({
        object: 'list',
        data: [{ object: 'embedding', index: 0, embedding: STUB_VEC }],
        model: 'stub-embed',
        usage: { prompt_tokens: 1, total_tokens: 1 },
      }))
    })
    return
  }
  if (req.method === 'POST' && req.url === '/v1/chat/completions') {
    readBody(req).then((raw) => {
      let body = {}
      try { body = JSON.parse(raw) } catch { /* 傻：解析失败按空对象走默认分支 */ }
      const messages = Array.isArray(body.messages) ? body.messages : []
      // ① 已带工具结果 → 同步 JSON 终答（Agent 循环第二轮）。顺序在②前：第二轮请求仍带 tools
      if (messages.some((m) => m && m.role === 'tool')) {
        sendJson(res, completion({ role: 'assistant', content: AGENT_FINAL }, 'stop'))
        return
      }
      // ② 带工具声明 → 固定 tool_calls（Agent 循环第一轮）
      if (Array.isArray(body.tools) && body.tools.length > 0) {
        sendJson(res, completion({
          role: 'assistant', content: null,
          tool_calls: [{ id: 'call_stub_1', type: 'function',
            function: { name: AGENT_TOOL_NAME, arguments: '{"timezone":"Asia/Shanghai"}' } }],
        }, 'tool_calls'))
        return
      }
      // ③ 流式 → SSE（原逻辑逐字保留）；同步 → JSON 固定答案
      if (body.stream === true) {
        res.writeHead(200, {
          'content-type': 'text/event-stream',
          'cache-control': 'no-cache',
          connection: 'keep-alive',
        })
        const id = 'chatcmpl-stub'
        // 模拟流式：一个 delta 块送完整答案
        res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', model: 'stub-chat', choices: [{ index: 0, delta: { content: STUB_ANSWER }, finish_reason: null }] })}\n\n`)
        // 收尾块带 usage + finish_reason
        res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', model: 'stub-chat', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 12, total_tokens: 22 } })}\n\n`)
        res.write('data: [DONE]\n\n')
        res.end()
        return
      }
      sendJson(res, completion({ role: 'assistant', content: STUB_ANSWER }, 'stop'))
    })
    return
  }
  res.writeHead(404).end()
})

function readBody(req) {
  return new Promise((resolve) => {
    let b = ''
    req.on('data', (c) => { b += c })
    req.on('end', () => resolve(b))
  })
}

server.listen(PORT, () => console.log(`[llm-stub] listening on ${PORT}`))
