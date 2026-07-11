# 检索阈值评估（retrieval-eval）

手跑调参工具，**不进 mvn test / CI**。用一组固定语料 + 28 题评估集，测出当前
embedding 模型下 `hify.knowledge.retrieval.score-threshold` 各候选值（0.20~0.70，
步长 0.05）的应命中召回率与不应命中误命中率，供拍板默认阈值。
设计文档：`docs/superpowers/specs/2026-07-11-retrieval-threshold-tuning-design.md`。

## 前置条件

- 服务已启动（本地开发默认 `http://localhost:8080`）
- 已配置**真实** embedding 供应商，且系统默认 embedding 模型已设置
  （假桩向量测不出真实阈值）
- Node ≥ 20（用到原生 fetch / FormData / node:test）
- 一次完整评估约调用 28 次 embedding API + 4 篇文档向量化，费用可忽略

## 用法

```bash
HIFY_EVAL_USER=admin HIFY_EVAL_PASSWORD=xxx node scripts/retrieval-eval/eval.mjs > report.md
```

- 报告走 stdout（重定向即存档），过程日志走 stderr
- `HIFY_BASE_URL` 可覆盖服务地址（默认 `http://localhost:8080`）
- `--keep`：跑完保留评估库，可在前端知识库详情页「命中测试」里人工复查分数，
  用完手动删除
- 用 Admin 账号跑可在报告头看到 embedding 模型名（member 账号该字段降级为「未知」）
- 脚本失败会尽力删除已建的评估库；提示清理失败时到前端手动删除
  `eval-阈值调优-*` 开头的库

## 报告怎么读

- **应命中召回率**：16 道针对语料的问题里，前 4 名（生产 topK）中有过阈值且
  属期望文档的分段的比例——越高越好，阈值升高会下降
- **不应命中误命中率**：12 道无关问题里，前 4 名中仍有分段过阈值的比例——
  应为 0，阈值过低会大于 0（这正是 0.3 时代「问天气也命中满额」的病根）
- **分隔带**：应命中组期望文档最高分的最小值 vs 不应命中组全场最高分——
  两者之间就是安全区间，报告给出中点作为参考
- 最终默认值人工拍板后改 `server/src/main/resources/application.yml` 的
  `hify.knowledge.retrieval.score-threshold`，并在 yml 注释补一句依据
  （评估日期 + 模型名 + 推荐区间）

## 什么时候要重跑

- 更换 embedding 模型（阈值与模型强相关，这是评估集沉淀的意义）
- 调整分段参数（chunk-size / chunk-overlap）
- 引入混合检索 / Rerank（二期）

## 单测

指标纯函数（`report.mjs`）有 node:test 单测：

```bash
node --test scripts/retrieval-eval/report.test.mjs
```
