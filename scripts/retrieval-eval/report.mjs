/**
 * 检索阈值评估的指标计算与报告渲染（纯函数，node:test 可测）。
 * 指标按生产口径：生产是「取 top-4 再按阈值过滤」（RetrievalService），
 * 所以召回/误命中只看每题排名前 PROD_TOP_K 的分数；分数分布看全部返回（采分 topK=10）。
 */

export const PROD_TOP_K = 4

/** 0.20 → 0.70 步长 0.05 共 11 档。整数运算再除，避免 0.1+0.2 式浮点尾巴。 */
export const CANDIDATE_THRESHOLDS = Array.from({ length: 11 }, (_, i) => (20 + i * 5) / 100)

function median(sortedAsc) {
  const n = sortedAsc.length
  if (n === 0) return 0
  const mid = Math.floor(n / 2)
  return n % 2 === 1 ? sortedAsc[mid] : (sortedAsc[mid - 1] + sortedAsc[mid]) / 2
}

/**
 * @param {Array<{query: string, expectedDoc: string, hits: Array<{documentName: string, score: number}>}>} shouldHit
 * @param {Array<{query: string, hits: Array<{documentName: string, score: number}>}>} shouldMiss
 *   hits 须按 score 降序（服务端按余弦距离升序返回即满足）。
 */
export function computeReport(shouldHit, shouldMiss) {
  const warnings = []

  // 分布口径：应命中组取 expectedDoc 在全部返回中的最高分；完全未返回按 0 分计并告警
  const hitExpectedTops = shouldHit.map((q) => {
    const own = q.hits.filter((h) => h.documentName === q.expectedDoc)
    if (own.length === 0) {
      warnings.push(`「${q.query}」的期望文档 ${q.expectedDoc} 未出现在返回结果中，按 0 分计`)
      return 0
    }
    return Math.max(...own.map((h) => h.score))
  })
  const missTops = shouldMiss.map((q) => (q.hits.length ? Math.max(...q.hits.map((h) => h.score)) : 0))

  const rows = CANDIDATE_THRESHOLDS.map((threshold) => {
    const recallCount = shouldHit.filter((q) =>
      q.hits.slice(0, PROD_TOP_K).some((h) => h.score >= threshold && h.documentName === q.expectedDoc)
    ).length
    const missCount = shouldMiss.filter((q) =>
      q.hits.slice(0, PROD_TOP_K).some((h) => h.score >= threshold)
    ).length
    return {
      threshold,
      recallCount,
      recall: shouldHit.length ? recallCount / shouldHit.length : 0,
      missCount,
      missRate: shouldMiss.length ? missCount / shouldMiss.length : 0,
    }
  })

  const sortedTops = [...hitExpectedTops].sort((a, b) => a - b)
  const distribution = {
    hitExpectedTopMin: sortedTops.length ? sortedTops[0] : 0,
    hitExpectedTopMedian: median(sortedTops),
    missTopMax: missTops.length ? Math.max(...missTops) : 0,
  }

  const zeroMiss = rows.filter((r) => r.missCount === 0)
  const bestRecall = zeroMiss.length ? Math.max(...zeroMiss.map((r) => r.recall)) : 0
  const thresholds = zeroMiss.filter((r) => r.recall === bestRecall).map((r) => r.threshold)
  const sepMidpoint =
    distribution.hitExpectedTopMin > distribution.missTopMax
      ? Math.round(((distribution.hitExpectedTopMin + distribution.missTopMax) / 2) * 1000) / 1000
      : null

  return { rows, distribution, recommendation: { thresholds, sepMidpoint }, warnings }
}

const pct = (v) => `${Math.round(v * 1000) / 10}%`
const f2 = (v) => v.toFixed(2)

/** @param {{modelName: string, baseUrl: string, datasetName: string, date: string}} meta */
export function renderReport(report, meta) {
  const lines = []
  lines.push('# 检索阈值评估报告')
  lines.push('')
  lines.push(`- 日期：${meta.date}`)
  lines.push(`- embedding 模型：${meta.modelName}`)
  lines.push(`- 服务：${meta.baseUrl}`)
  lines.push(`- 评估库：${meta.datasetName}`)
  lines.push(`- 口径：召回/误命中只看每题前 ${PROD_TOP_K} 名（生产 topK），分布看全部返回`)
  lines.push('')
  lines.push('| 阈值 | 应命中召回率 | 不应命中误命中率 |')
  lines.push('|---|---|---|')
  for (const r of report.rows) {
    lines.push(`| ${f2(r.threshold)} | ${pct(r.recall)}（${r.recallCount} 题） | ${pct(r.missRate)}（${r.missCount} 题） |`)
  }
  lines.push('')
  const d = report.distribution
  lines.push(`分数分布：应命中组期望文档最高分 min=${f2(d.hitExpectedTopMin)} / median=${f2(d.hitExpectedTopMedian)}；不应命中组全场最高分 max=${f2(d.missTopMax)}`)
  lines.push('')
  const rec = report.recommendation
  if (rec.thresholds.length) {
    lines.push(`推荐（误命中 0 且召回最高）：阈值档 ${rec.thresholds.map(f2).join(' / ')}${rec.sepMidpoint !== null ? `；分隔带中点 ${rec.sepMidpoint}` : '；两组分数重叠，无干净分隔带'}`)
  } else {
    lines.push('推荐：不存在误命中为 0 的阈值档，两组分数重叠，请对照上表人工取舍')
  }
  if (report.warnings.length) {
    lines.push('')
    lines.push('告警：')
    for (const w of report.warnings) lines.push(`- ${w}`)
  }
  lines.push('')
  return lines.join('\n')
}
