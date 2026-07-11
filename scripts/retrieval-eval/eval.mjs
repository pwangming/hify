#!/usr/bin/env node
/**
 * 检索阈值评估脚本（手跑调参工具，不进 CI）。
 * 流程：登录 → 建评估库 → 上传 fixtures → 轮询向量化 → 逐题采分（topK=10, threshold=0）
 *       → 离线扫 11 档候选阈值 → stdout 输出 markdown 报告 → 删除评估库（--keep 保留）。
 * 用法见同目录 README.md。
 */
import { readFile, readdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { computeReport, renderReport } from './report.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const BASE = process.env.HIFY_BASE_URL ?? 'http://localhost:8080'
const USER = process.env.HIFY_EVAL_USER
const PASS = process.env.HIFY_EVAL_PASSWORD
const KEEP = process.argv.includes('--keep')
const POLL_INTERVAL_MS = 3000
const POLL_TIMEOUT_MS = 180000

if (!USER || !PASS) {
  console.error('缺少环境变量 HIFY_EVAL_USER / HIFY_EVAL_PASSWORD（账密不入仓库，运行时提供）')
  process.exit(1)
}

/** 统一请求：解 Result 信封，code !== 200 抛错（业务码与 message 原样进错误信息，不吞）。 */
async function api(method, apiPath, { token, json, form } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  let body
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(json)
  } else if (form !== undefined) {
    body = form // FormData 自带 multipart Content-Type
  }
  const res = await fetch(BASE + apiPath, { method, headers, body })
  const payload = await res.json().catch(() => null)
  if (!payload || payload.code !== 200) {
    throw new Error(`${method} ${apiPath} 失败：HTTP ${res.status}，code=${payload?.code}，message=${payload?.message}`)
  }
  return payload.data
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  const questions = JSON.parse(await readFile(path.join(HERE, 'questions.json'), 'utf8'))
  const fixtureNames = (await readdir(path.join(HERE, 'fixtures'))).filter((f) => f.endsWith('.md')).sort()
  console.error(`语料 ${fixtureNames.length} 篇，应命中 ${questions.shouldHit.length} 题，不应命中 ${questions.shouldMiss.length} 题`)

  console.error(`登录 ${BASE} ...`)
  const { token } = await api('POST', '/api/v1/identity/login', { json: { username: USER, password: PASS } })

  // 模型名仅 Admin 可查，member 账号 403 时降级，不中断评估
  let modelName = '未知（查询失败或非 Admin 账号）'
  try {
    const setting = await api('GET', '/api/v1/admin/provider/settings/embedding-model', { token })
    if (setting?.modelName) modelName = setting.modelName
  } catch {
    /* 降级即可 */
  }

  // 精确到秒（如 20260711T153045），避免 --keep 保留旧库后短时间内重跑撞重名 409
  const datasetName = `eval-阈值调优-${new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)}`
  console.error(`创建评估库 ${datasetName} ...`)
  const dataset = await api('POST', '/api/v1/knowledge/datasets', {
    token,
    json: { name: datasetName, description: '检索阈值评估临时库，脚本自动创建，可删除' },
  })

  try {
    for (const name of fixtureNames) {
      const content = await readFile(path.join(HERE, 'fixtures', name), 'utf8')
      const form = new FormData()
      form.append('file', new Blob([content], { type: 'text/markdown' }), name)
      await api('POST', `/api/v1/knowledge/datasets/${dataset.id}/documents`, { token, form })
      console.error(`已上传 ${name}`)
    }

    console.error('等待向量化（pending/processing → ready）...')
    const deadline = Date.now() + POLL_TIMEOUT_MS
    for (;;) {
      const page = await api('GET', `/api/v1/knowledge/datasets/${dataset.id}/documents?page=1&size=20`, { token })
      const failed = page.list.find((d) => d.status === 'failed')
      if (failed) {
        throw new Error(`文档 ${failed.name} 向量化失败：${failed.errorMessage ?? '无错误信息'}——请检查 embedding 供应商配置与系统默认 embedding 模型`)
      }
      const readyCount = page.list.filter((d) => d.status === 'ready').length
      console.error(`  ${readyCount}/${fixtureNames.length} ready`)
      if (page.list.length === fixtureNames.length && readyCount === fixtureNames.length) break
      if (Date.now() > deadline) throw new Error(`向量化超时（${POLL_TIMEOUT_MS / 1000}s）`)
      await sleep(POLL_INTERVAL_MS)
    }

    console.error('逐题采分（每题一次检索，topK=10，threshold=0）...')
    const collect = async (q) => {
      const data = await api('POST', `/api/v1/knowledge/datasets/${dataset.id}/retrieve`, {
        token,
        json: { query: q.query, topK: 10, scoreThreshold: 0 },
      })
      return { ...q, hits: data.map((h) => ({ documentName: h.documentName, score: h.score })) }
    }
    const shouldHit = []
    for (const q of questions.shouldHit) shouldHit.push(await collect(q))
    const shouldMiss = []
    for (const q of questions.shouldMiss) shouldMiss.push(await collect(q))

    const report = computeReport(shouldHit, shouldMiss)
    const md = renderReport(report, {
      modelName,
      baseUrl: BASE,
      datasetName,
      date: new Date().toISOString().slice(0, 10),
    })
    console.log(md) // 报告走 stdout；过程日志全部走 stderr，方便 > report.md 重定向
  } finally {
    if (KEEP) {
      console.error(`--keep：保留评估库 ${datasetName}（id=${dataset.id}），可在前端「命中测试」里人工复查，用完手动删除`)
    } else {
      try {
        await api('DELETE', `/api/v1/knowledge/datasets/${dataset.id}`, { token })
        console.error('评估库已删除')
      } catch (e) {
        console.error(`清理评估库失败（请到前端手动删除 ${datasetName}）：${e.message}`)
      }
    }
  }
}

main().catch((e) => {
  console.error(`评估失败：${e.message}`)
  process.exit(1)
})
