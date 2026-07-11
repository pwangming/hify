# 检索阈值调优：评估集 + score-threshold 拍板（设计）

> 2026-07-11 brainstorm 定稿。W3a 验收留账的小轮：`hify.knowledge.retrieval.score-threshold: 0.3`
> 过宽松，无关问题（如问天气）也命中满额 topK=4，导致 ① RAG 给 LLM 喂不相关资料
> ② conversation 引用卡片展示不相关来源 ③ workflow「count>0」分支判断几乎恒真。
> 本轮建**可复跑评估集**（阈值与 embedding 模型强相关，将来换模型/加混合检索可直接重测），
> 据报告拍板新默认值。排在 Vue Flow 画布轮之前的间奏小轮。

## 1. 范围与目标

**做**：
- 新顶层目录 `scripts/retrieval-eval/`（用户拍板：仓库布局新增 `scripts/`，CLAUDE.md 同步补一行）：
  评估脚本 `eval.mjs` + 问题集 `questions.json` + 语料 `fixtures/*.md` + `README.md`
- 跑评估 → 出指标报告 → 用户拍板新阈值 → 改 `application.yml` 默认值（env 覆盖机制不变）
- 三步人工验收（真实库抽查，见 §6）

**不做**（YAGNI）：
- topK 调整（报告顺带展示数据，默认 4 不动）
- per-dataset 阈值（dataset 表不加列；真有需求另开轮）
- Rerank / 混合检索（二期候选，tsvector 地基已就绪）
- E2E 负样本自动化、检索质量进 CI（评估依赖真实付费 embedding API，进 CI 会 flaky 且烧钱，
  违背 testing-standards 确定性原则）

**四个拍板决策**：
1. 产出形态 = 可复跑评估集 + 调阈值（不是只手工调数字，也不做 per-dataset 配置）
2. 语料 = repo 自带固定 fixtures（评估集随 repo 走，换环境/换模型零准备复测）
3. 脚本定位 = 手跑调参工具，不进 mvn test / CI（与 docs/postman 验收集合同级）
4. 实现 = Node 原生脚本（零新依赖，同 e2e 桩先例）+「一次检索、离线扫阈值」：
   每题只调一次命中测试端点取原始分数，候选阈值在脚本内离线计算——
   embedding 调用数 = 题数（28 次），不是 题数×阈值档数

## 2. 评估集设计

**fixtures（4 篇合成中文 md，每篇 1500~2500 字）**：主题差异明显、贴近内部团队知识库形态——
「员工考勤与休假制度」「差旅报销流程」「办公网络与 VPN 使用指南」「产品退换货政策」。
按现有分段参数（chunk-size 500 / overlap 50）每篇切 3~6 段，全库 15~20 段。

**questions.json（28 题）**：
- **应命中组 16 题**：每篇 4 题，混合两种问法——直接问法（用原文词汇）与同义改写
  （换说法考验语义检索）；每题标注 `expectedDoc`（期望命中的文档文件名）
- **不应命中组 12 题**：与语料完全无关——闲聊（"今天天气怎么样"）、常识（"1+1等于几"）、
  超纲领域（"特斯拉股价"、"量子纠缠原理"）等

```json
{
  "shouldHit": [
    {"query": "年假有多少天？", "expectedDoc": "attendance-leave.md"}
  ],
  "shouldMiss": [
    {"query": "今天天气怎么样"}
  ]
}
```

## 3. 脚本设计（eval.mjs）

**运行环境**：Node ≥ 20 原生 fetch，零依赖。配置走环境变量：
`HIFY_BASE_URL`（默认 `http://localhost:8080`）、`HIFY_EVAL_USER` / `HIFY_EVAL_PASSWORD`
（账密不硬编码入仓库）。

**前置条件**（README 写明）：服务已启动；已配置真实 embedding 供应商且系统默认
embedding 模型已设置——假桩向量测不出真实阈值，评估必须打真 API。

**流程**：
1. 登录 `POST /api/v1/identity/login` 拿 token
2. 建评估库 `POST /api/v1/knowledge/datasets`（名称带时间戳如 `eval-阈值调优-20260711T1530`，避免重跑 409）
3. 逐篇上传 fixtures `POST /datasets/{id}/documents`
4. 轮询文档列表直到全部 `ready`（总超时 180s；任一篇 `failed` 立即中止并提示检查供应商配置）
5. 逐题 `POST /datasets/{id}/retrieve`，参数 `{query, topK: 10, scoreThreshold: 0}`
   （topK 上限 20、threshold 下限 0.0，已核对 RetrieveTestRequest 约束），收集每题原始分数
6. 离线扫描候选阈值算指标（§4），stdout 输出 markdown 表格报告
7. 删除评估库收尾（软删级联，K2 现成）；`--keep` 参数保留评估库，
   供在前端「命中测试」弹窗里人工复查

**错误处理**：任一步失败即中止，尽力删除已建评估库（`--keep` 除外）；
业务错误原样打印 `Result.code/message`，不吞。

## 4. 指标与推荐规则

候选阈值：0.20 → 0.70，步长 0.05（11 档）。**指标按生产口径计算**——生产是
「取 top-4 再按阈值过滤」，所以每题只用排名前 4 的分数参与判定（采分取 topK=10
仅为多看分数分布，第 5 名以后不进指标）：

| 指标 | 定义 |
|---|---|
| 应命中召回率 | 前 4 名中有过阈值且属 `expectedDoc` 分段的题数 / 16 |
| 不应命中误命中率 | 前 4 名中有任何分段过阈值的题数 / 12 |

另输出两组分数分布看**分隔带**：应命中组 expectedDoc 最高分的 min / median，
不应命中组全场最高分的 max——两者之差即安全余量。

**推荐规则**：报告标出「误命中率 = 0 且召回率最高」的阈值区间与分隔带中点，
**最终数字用户拍板**。若两组分数重叠（不存在完美区间），报告如实展示权衡曲线，由用户取舍。

## 5. 落地

- 改 `server/src/main/resources/application.yml` 的
  `hify.knowledge.retrieval.score-threshold` 默认值，yml 注释补一句调参依据
  （评估日期 + embedding 模型 + 推荐区间）
- CLAUDE.md 仓库布局补 `scripts/` 一行
- 评估结论记入 self-check.md

## 6. 验收（DoD）

对着 W3a 留账的三个症状逐一人工验收：

1. **真实知识库抽查**（关键——合成语料代表性有限，结论必须过这关）：
   前端命中测试问 2~3 个无关问题 → 0 命中；再问真实相关问题 → 仍正常命中（防矫枉过正）
2. conversation 绑库应用问无关问题 → 无引用来源卡片、正常降级回答
3. 可选：W3a 工作流未命中方向改用真实库重验 `count=0` 走 false 分支

**脚本本身的 DoD**：`node scripts/retrieval-eval/eval.mjs` 在真实环境一条命令跑通出报告；
`--keep` 保留的评估库能在前端正常查看；重跑不因重名失败；评估库删除后不留脏数据
（文档/分段随库软删）。

## 7. 已知边界

- 评估打的是真实 embedding API，结果随模型版本波动——报告头部输出所用模型名，
  换模型后需重跑（这正是评估集沉淀的意义）
- 28 题小样本，指标粒度粗（召回率步进 1/16≈6%）；对「拍板一个默认值」够用，
  不追求学术级评估
- 阈值仍是全局一个数：不同库的语料密度不同，最优阈值可能有差异，一期接受，
  per-dataset 配置留到真实需求出现
