import test from 'node:test'
import assert from 'node:assert/strict'
import { computeReport, renderReport, CANDIDATE_THRESHOLDS, PROD_TOP_K } from './report.mjs'

/** 造 hits：入参 [文档名, 分数] 二元组，按分数降序排好再传（与服务端返回口径一致）。 */
function hits(...pairs) {
  return pairs
    .map(([documentName, score]) => ({ documentName, score }))
    .sort((a, b) => b.score - a.score)
}

test('候选阈值为 0.20~0.70 步长 0.05 共 11 档，无浮点尾巴', () => {
  assert.equal(CANDIDATE_THRESHOLDS.length, 11)
  assert.deepEqual(CANDIDATE_THRESHOLDS, [0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7])
  assert.equal(PROD_TOP_K, 4)
})

test('召回按生产口径只看前 4 名：expectedDoc 排第 5 不算召回', () => {
  const shouldHit = [{
    query: 'q1', expectedDoc: 'a.md',
    hits: hits(['x.md', 0.6], ['x.md', 0.58], ['y.md', 0.56], ['y.md', 0.54], ['a.md', 0.52]),
  }]
  const { rows } = computeReport(shouldHit, [])
  for (const row of rows) assert.equal(row.recallCount, 0, `阈值 ${row.threshold} 不应召回`)
})

test('召回需过阈值：expectedDoc 最高 0.44 分，0.40 档召回、0.45 档不召回', () => {
  const shouldHit = [{ query: 'q1', expectedDoc: 'a.md', hits: hits(['a.md', 0.44], ['x.md', 0.3]) }]
  const { rows } = computeReport(shouldHit, [])
  const at = (t) => rows.find((r) => r.threshold === t)
  assert.equal(at(0.4).recallCount, 1)
  assert.equal(at(0.4).recall, 1)
  assert.equal(at(0.45).recallCount, 0)
})

test('误命中需过阈值：最高 0.38 分，0.35 档误命中、0.40 档不误命中', () => {
  const shouldMiss = [{ query: 'm1', hits: hits(['a.md', 0.38], ['b.md', 0.2]) }]
  const { rows } = computeReport([], shouldMiss)
  const at = (t) => rows.find((r) => r.threshold === t)
  assert.equal(at(0.35).missCount, 1)
  assert.equal(at(0.35).missRate, 1)
  assert.equal(at(0.4).missCount, 0)
})

test('分数分布与分隔带中点', () => {
  const shouldHit = [
    { query: 'q1', expectedDoc: 'a.md', hits: hits(['a.md', 0.5], ['x.md', 0.4]) },
    { query: 'q2', expectedDoc: 'b.md', hits: hits(['b.md', 0.7]) },
  ]
  const shouldMiss = [
    { query: 'm1', hits: hits(['a.md', 0.35]) },
    { query: 'm2', hits: hits(['b.md', 0.1]) },
  ]
  const { distribution, recommendation } = computeReport(shouldHit, shouldMiss)
  assert.equal(distribution.hitExpectedTopMin, 0.5)
  assert.equal(distribution.hitExpectedTopMedian, 0.6)
  assert.equal(distribution.missTopMax, 0.35)
  assert.equal(recommendation.sepMidpoint, 0.425)
})

test('两组分数重叠时分隔带中点为 null', () => {
  const shouldHit = [{ query: 'q1', expectedDoc: 'a.md', hits: hits(['a.md', 0.3]) }]
  const shouldMiss = [{ query: 'm1', hits: hits(['x.md', 0.45]) }]
  const { recommendation } = computeReport(shouldHit, shouldMiss)
  assert.equal(recommendation.sepMidpoint, null)
})

test('expectedDoc 完全未返回时告警且按 0 分计入分布', () => {
  const shouldHit = [{ query: 'q1', expectedDoc: 'a.md', hits: hits(['x.md', 0.6]) }]
  const { distribution, warnings } = computeReport(shouldHit, [])
  assert.equal(distribution.hitExpectedTopMin, 0)
  assert.equal(warnings.length, 1)
  assert.match(warnings[0], /q1/)
  assert.match(warnings[0], /a\.md/)
})

test('推荐 = 误命中为 0 且召回最高的全部阈值档', () => {
  const shouldHit = [
    { query: 'q1', expectedDoc: 'a.md', hits: hits(['a.md', 0.55]) },
    { query: 'q2', expectedDoc: 'b.md', hits: hits(['b.md', 0.45]) },
  ]
  const shouldMiss = [{ query: 'm1', hits: hits(['x.md', 0.38]) }]
  const { recommendation } = computeReport(shouldHit, shouldMiss)
  // 0.40/0.45 两档：误命中 0（0.38 < 0.40）且召回 2/2（0.45 >= 0.45）；0.50 起 q2 掉出
  assert.deepEqual(recommendation.thresholds, [0.4, 0.45])
})

test('renderReport 含模型名、11 行数据与推荐结论', () => {
  const shouldHit = [{ query: 'q1', expectedDoc: 'a.md', hits: hits(['a.md', 0.55]) }]
  const shouldMiss = [{ query: 'm1', hits: hits(['x.md', 0.3]) }]
  const report = computeReport(shouldHit, shouldMiss)
  const md = renderReport(report, {
    modelName: 'text-embedding-v4', baseUrl: 'http://localhost:8080',
    datasetName: 'eval-x', date: '2026-07-11',
  })
  assert.match(md, /text-embedding-v4/)
  assert.match(md, /0\.70/)
  assert.equal(md.split('\n').filter((l) => /^\| 0\.\d{2} \|/.test(l)).length, 11)
  assert.match(md, /推荐/)
})
