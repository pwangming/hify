# 检索阈值调优（评估集 + score-threshold 拍板）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可复跑的检索阈值评估工具（`scripts/retrieval-eval/`）：28 题评估集 + Node 脚本一次检索离线扫 11 档候选阈值，输出召回率/误命中率/分隔带报告，供用户拍板新的 `score-threshold` 默认值。

**Architecture:** 新顶层目录 `scripts/retrieval-eval/`。纯函数模块 `report.mjs`（指标计算 + markdown 渲染，node:test 可测）与编排脚本 `eval.mjs`（登录→建评估库→传 4 篇 fixtures→轮询向量化→逐题采分→算报告→删库）分离。每题只调一次命中测试端点（`topK=10, scoreThreshold=0`）取原始分数，候选阈值全部离线计算——embedding 调用数 = 题数（28 次）。

**Tech Stack:** Node ≥ 20 原生能力（`fetch`/`FormData`/`Blob`/`node:test`/`node:assert`），**零 npm 依赖、不建 package.json**。后端零改动、零迁移。

**Spec:** `docs/superpowers/specs/2026-07-11-retrieval-threshold-tuning-design.md`

## Global Constraints

- 本轮**后端/前端代码零改动**（不动 server/、web/ 下任何文件）；只创建 `scripts/retrieval-eval/**` 与修改 `CLAUDE.md` 一处
- 脚本是**手跑调参工具**：不进 mvn test、不进 CI、不进 vitest；唯一自动化测试是 `node --test scripts/retrieval-eval/`（在仓库根目录跑，看退出码判定）
- 账密不硬编码入仓库：`HIFY_EVAL_USER`/`HIFY_EVAL_PASSWORD` 环境变量必填，缺失时报错退出；`HIFY_BASE_URL` 默认 `http://localhost:8080`
- 指标按**生产口径**：只用每题排名前 4（`PROD_TOP_K=4`）的分数参与召回/误命中判定；采分 `topK=10` 仅为多看分布
- 候选阈值 0.20 → 0.70 步长 0.05 共 11 档，浮点用整数运算再除避免精度尾巴
- 所有 API 细节已现场核实（登录/建库/传档/轮询/采分/删库/模型名端点，见各 Task 内注释），**不得改动或凭记忆替换路径与字段名**
- 报错信息、报告文案、README 全部中文；提交信息用中文 conventional commits

## File Structure

```
scripts/retrieval-eval/
├── fixtures/
│   ├── attendance-leave.md    # 语料①员工考勤与休假制度
│   ├── travel-expense.md      # 语料②差旅报销流程
│   ├── network-vpn.md         # 语料③办公网络与 VPN 使用指南
│   └── product-return.md      # 语料④产品退换货政策
├── questions.json             # 16 应命中（标 expectedDoc）+ 12 不应命中
├── report.mjs                 # 纯函数：computeReport / renderReport / 常量
├── report.test.mjs            # node:test 单测（唯一自动化测试）
├── eval.mjs                   # 编排 CLI（登录→建库→采分→报告→清理）
└── README.md                  # 前置条件/用法/报告解读/复跑场景
CLAUDE.md                      # 仓库布局补 scripts/ 一行（Task 4）
```

---

### Task 1: 评估语料 fixtures + questions.json

**Files:**
- Create: `scripts/retrieval-eval/fixtures/attendance-leave.md`
- Create: `scripts/retrieval-eval/fixtures/travel-expense.md`
- Create: `scripts/retrieval-eval/fixtures/network-vpn.md`
- Create: `scripts/retrieval-eval/fixtures/product-return.md`
- Create: `scripts/retrieval-eval/questions.json`

**Interfaces:**
- Produces: `questions.json` 结构 `{ "shouldHit": [{"query": string, "expectedDoc": string}], "shouldMiss": [{"query": string}] }`，`expectedDoc` 取值 = fixtures 文件名（含 `.md` 后缀，与上传后 `DocumentResponse.name` 一致——上传时文件名原样入库）。Task 3 的 eval.mjs 按此结构读取。

- [ ] **Step 1: 写 4 篇语料**

每篇 1500 字左右（按 chunk-size 500 / overlap 50 会切成 3~6 段）。内容如下，**原样写入**：

`scripts/retrieval-eval/fixtures/attendance-leave.md`：

```markdown
# 员工考勤与休假制度

## 工作时间与打卡

公司实行弹性工作制，标准工作时间为每周一至周五 9:30 至 18:30，午休一小时。员工可在 9:00 至 10:00 之间弹性签到，对应下班时间顺延，保证每日在岗满 8 小时即可。上下班均须在企业微信打卡，外出办公需提前在 OA 系统提交外勤申请，经直属主管批准后按外勤打卡处理。

每月迟到（晚于弹性窗口签到）3 次以内不做处理；第 4 次起每次扣减当月绩效分 1 分。单次迟到超过 60 分钟按半天事假处理。忘记打卡可在当日 24 点前于 OA 系统提交补卡申请，每月补卡上限为 3 次，超出部分按缺卡处理。

## 年假

年假天数按累计工龄核定：入职满 1 年不满 3 年的员工每年享有 5 天年假；满 3 年不满 10 年的享有 10 天；满 10 年及以上的享有 15 天。年假以 0.5 天为最小请假单位，当年未休完的年假可结转至次年 3 月 31 日前使用，逾期自动清零，不折算工资。

请年假需至少提前 1 个工作日在 OA 系统提交申请；连续休 3 天以上年假需提前 5 个工作日申请，并做好工作交接。

## 病假与事假

病假需在恢复上班后 48 小时内在 OA 系统补交二级及以上医院开具的诊断证明或病历，未按时提交的按事假处理。当年累计病假 10 天以内按基本工资的 80% 计薪。

事假为无薪假，以 0.5 天为最小单位，全年累计不得超过 15 天。事假需提前 1 个工作日申请，紧急情况可先电话报备直属主管、事后 1 个工作日内补单。

## 加班与调休

工作日加班需事先在 OA 提交加班申请并经主管审批，审批通过的加班时长按 1:1 折算为调休额度。调休额度自产生之日起 6 个月内有效，使用方式与年假相同，在 OA 选择「调休」假别提交即可。法定节假日加班按劳动法支付三倍工资，不再折算调休。
```

`scripts/retrieval-eval/fixtures/travel-expense.md`：

```markdown
# 差旅报销流程

## 出差申请

员工出差须提前 3 个工作日在 OA 系统提交出差申请，写明目的地、事由、预计天数与预算，经直属主管与部门负责人两级审批通过后方可预订行程。未经审批的出差产生的费用原则上不予报销。行程如有变更，应在变更发生后 1 个工作日内在原申请单上补充说明。

## 交通标准

市际交通：4 小时以内行程优先选择高铁二等座；4 小时以上可选飞机经济舱，机票须通过公司协议差旅平台预订，选择当日可比价航班中的合理低价。总监级及以上员工可乘坐高铁一等座。市内交通实报实销，优先使用地铁、公交与网约车快车，单程出租车费用超过 100 元需在报销单中说明事由。

## 住宿标准

住宿费用上限按城市分档：北京、上海、广州、深圳为每晚 500 元；其他城市为每晚 350 元。同性别同事两人同行出差原则上合住标准间，确需单住的在申请单中说明。超标部分由个人承担。住宿发票必须为增值税发票，抬头为公司全称。

## 餐补与其他费用

出差期间餐费按每人每天 100 元包干补贴，无需发票，按实际出差天数在报销单中一并申领。因公宴请需事前单独申请，凭发票与宴请事由说明实报，不占用餐补额度。行李托运费、签证费等因公杂费凭票实报。

## 报销提交与打款

差旅结束后 15 个工作日内在 OA 系统提交报销单，逾期系统自动关闭申请入口，特殊情况需部门负责人书面说明。报销单需附行程单、发票原件照片与出差申请编号。财务每月 10 日与 25 日两批集中打款，审核通过的报销款项打入员工工资卡。发票信息与报销单不符的将被整单退回，修改后重新进入审核队列。
```

`scripts/retrieval-eval/fixtures/network-vpn.md`：

```markdown
# 办公网络与 VPN 使用指南

## 办公室 Wi-Fi

办公区无线网络分为两个：员工网络 SSID 为 Hify-Office，访客网络 SSID 为 Hify-Guest。Hify-Office 使用 802.1X 认证，用企业邮箱账号密码登录，首次连接需按 IT 部发的配置指引安装证书；Hify-Guest 密码每周一更换，张贴在前台接待处，仅供访客使用，员工办公设备禁止长期连接访客网络。

办公网络禁止使用 P2P 下载与网络代理工具，禁止私接路由器或随身 Wi-Fi。IT 部会对异常流量设备做断网处理并通知直属主管。

## VPN 远程接入

远程办公访问内网资源统一使用 WireGuard 客户端。申请流程：在 OA 系统提交「VPN 接入申请」，经直属主管审批后，IT 部在 1 个工作日内将专属配置文件发送到申请人企业邮箱，导入 WireGuard 客户端即可使用。每人限绑定 2 台设备，配置文件禁止转发他人。

连上 VPN 后可访问的内网资源包括：代码仓库 GitLab（git.hify.internal）、知识库 Wiki（wiki.hify.internal）、测试环境网关（test.hify.internal）。生产环境不对 VPN 开放，需走堡垒机并单独申请权限。

## 故障排查

网络连不上时按以下顺序排查：第一步确认设备 Wi-Fi 开关与飞行模式状态，尝试遗忘网络后重新认证登录；第二步检查是否能打开外网网页，若外网正常而内网资源打不开，重启 WireGuard 客户端并确认配置文件未过期；第三步换手机热点验证是否为办公网络问题。以上无法解决时，携带设备到 3 楼 IT 服务台，或发邮件到 it-support@hify.com 描述故障现象与设备信息，IT 部工作时间内 2 小时响应。

VPN 配置文件有效期为 12 个月，到期前一周会收到邮件提醒，需重新提交申请换发。离职员工的 VPN 权限在离职当日由 IT 部统一回收。
```

`scripts/retrieval-eval/fixtures/product-return.md`：

```markdown
# 产品退换货政策

## 七天无理由退货

自签收之日起 7 天内，商品在不影响二次销售的前提下（外包装完好、配件齐全、无使用痕迹）可申请无理由退货。退货一经质检通过，退款将在 3 至 5 个工作日内原路退回支付账户。无理由退货的往返运费由买家承担，使用运费险的订单按保险条款赔付。

## 质量问题换货

自签收之日起 15 天内出现非人为质量问题的，可申请免费换货，往返运费由公司承担。换货需提供问题部位的照片或视频作为凭证，客服确认后安排上门取件。同型号无货时可选择等价换购其他型号或全额退款。

## 保修

产品整机保修期为 12 个月，自发票开具之日起计算；电池等易损耗配件保修期为 6 个月。保修期内非人为损坏免费维修，往返运费公司承担；超出保修期或人为损坏的提供有偿维修，维修前会先出具报价单，确认后再施工。

## 退换货流程

第一步：联系在线客服或拨打 400-800-1234 提交退换货申请，提供订单号与申请原因；第二步：客服在 1 个工作日内审核并给出寄回地址与售后单号；第三步：将商品连同配件、赠品一并寄回，包裹内附注售后单号；第四步：仓库签收后 2 个工作日内完成质检，质检通过即进入退款或换货发货环节。

## 不支持退货的情形

以下情形不支持无理由退货：定制类商品（含刻字、专属配色）；已激活或绑定账号的智能设备；因人为原因（进液、摔损、私自拆机）造成损坏的商品；超过退换货时限的申请。发票丢失不影响保修资格，可凭订单号与机身序列号查询购买记录，但需自行承担无法开具退货发票的税务影响。
```

- [ ] **Step 2: 写 questions.json**

`scripts/retrieval-eval/questions.json`，**原样写入**（16 应命中 = 每篇 4 题，混合直接问法与同义改写；12 不应命中 = 与语料完全无关）：

```json
{
  "shouldHit": [
    { "query": "入职满三年年假有几天？", "expectedDoc": "attendance-leave.md" },
    { "query": "迟到会有什么处理？", "expectedDoc": "attendance-leave.md" },
    { "query": "生病请假需要提交什么证明材料？", "expectedDoc": "attendance-leave.md" },
    { "query": "加班之后想换成休息时间，怎么操作？", "expectedDoc": "attendance-leave.md" },
    { "query": "出差住宿一晚最多能报销多少钱？", "expectedDoc": "travel-expense.md" },
    { "query": "出差申请要提前几天提交？", "expectedDoc": "travel-expense.md" },
    { "query": "坐高铁可以选一等座吗？", "expectedDoc": "travel-expense.md" },
    { "query": "报销的钱大概什么时候能到账？", "expectedDoc": "travel-expense.md" },
    { "query": "办公室 Wi-Fi 的名称是什么？", "expectedDoc": "network-vpn.md" },
    { "query": "公司 VPN 用的是哪个客户端软件？", "expectedDoc": "network-vpn.md" },
    { "query": "在家想访问公司内网的代码仓库要怎么弄？", "expectedDoc": "network-vpn.md" },
    { "query": "网络连不上应该先做哪些排查？", "expectedDoc": "network-vpn.md" },
    { "query": "买的东西不喜欢，多少天内可以退货？", "expectedDoc": "product-return.md" },
    { "query": "产品的保修期是多久？", "expectedDoc": "product-return.md" },
    { "query": "退款多长时间能退回到账户？", "expectedDoc": "product-return.md" },
    { "query": "哪些情况下不能退货？", "expectedDoc": "product-return.md" }
  ],
  "shouldMiss": [
    { "query": "今天天气怎么样？" },
    { "query": "1 加 1 等于几？" },
    { "query": "特斯拉今天的股价是多少？" },
    { "query": "给我讲个笑话吧" },
    { "query": "量子纠缠是什么原理？" },
    { "query": "珠穆朗玛峰有多高？" },
    { "query": "上届世界杯是哪个队夺冠的？" },
    { "query": "红烧肉怎么做才好吃？" },
    { "query": "明天会下雨吗？" },
    { "query": "推荐一部好看的科幻电影" },
    { "query": "太阳系一共有几颗行星？" },
    { "query": "唐朝是哪一年建立的？" }
  ]
}
```

- [ ] **Step 3: 校验语料与问题集一致性**

在仓库根目录运行：

```bash
node -e '
const fs = require("fs");
const q = JSON.parse(fs.readFileSync("scripts/retrieval-eval/questions.json", "utf8"));
const docs = fs.readdirSync("scripts/retrieval-eval/fixtures");
if (q.shouldHit.length !== 16) throw new Error("应命中题数不是 16");
if (q.shouldMiss.length !== 12) throw new Error("不应命中题数不是 12");
for (const item of q.shouldHit) {
  if (!docs.includes(item.expectedDoc)) throw new Error("expectedDoc 无对应语料: " + item.expectedDoc);
  if (!item.query || item.query.length > 1000) throw new Error("query 非法: " + item.query);
}
for (const d of docs) {
  const len = fs.readFileSync("scripts/retrieval-eval/fixtures/" + d, "utf8").length;
  if (len < 800) throw new Error(d + " 过短(" + len + " 字符)，分段数会不足");
  console.log(d, len + " 字符");
}
console.log("questions.json 校验通过");
'
```

Expected: 输出 4 行「文件名 + 字符数」（每篇 ≥ 800）与 `questions.json 校验通过`，退出码 0。

- [ ] **Step 4: Commit**

```bash
git add scripts/retrieval-eval/fixtures scripts/retrieval-eval/questions.json
git commit -m "feat(eval): 检索阈值评估集（4 篇合成语料 + 16 应命中/12 不应命中问题）"
```

---

### Task 2: report.mjs 指标纯函数（TDD）

**Files:**
- Create: `scripts/retrieval-eval/report.mjs`
- Test: `scripts/retrieval-eval/report.test.mjs`

**Interfaces:**
- Produces（Task 3 的 eval.mjs 消费）：
  - `PROD_TOP_K = 4`、`CANDIDATE_THRESHOLDS`（`[0.2, 0.25, ..., 0.7]` 共 11 档）
  - `computeReport(shouldHit, shouldMiss)`：入参 `shouldHit = [{query, expectedDoc, hits}]`、`shouldMiss = [{query, hits}]`，其中 `hits = [{documentName, score}]` **按 score 降序**（服务端已按余弦距离升序返回，即分数降序，eval.mjs 原样传入）。返回 `{rows, distribution, recommendation, warnings}`：
    - `rows`: `[{threshold, recallCount, recall, missCount, missRate}]`（11 行）
    - `distribution`: `{hitExpectedTopMin, hitExpectedTopMedian, missTopMax}`（分布看全部 hits，不截前 4）
    - `recommendation`: `{thresholds: number[], sepMidpoint: number|null}`——误命中为 0 且召回最高的阈值档列表；分隔带中点 `(missTopMax + hitExpectedTopMin) / 2`，两组分数重叠（`hitExpectedTopMin <= missTopMax`）时为 `null`
    - `warnings`: `string[]`（expectedDoc 完全未出现在返回结果时告警并按 0 分计）
  - `renderReport(report, meta)`：`meta = {modelName, baseUrl, datasetName, date}`，返回 markdown 字符串

- [ ] **Step 1: 写失败测试**

`scripts/retrieval-eval/report.test.mjs`，**原样写入**：

```js
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
```

- [ ] **Step 2: 跑测试确认失败**

Run（仓库根目录）: `node --test scripts/retrieval-eval/`
Expected: FAIL——`Cannot find module ... report.mjs`，退出码非 0。

- [ ] **Step 3: 实现 report.mjs**

`scripts/retrieval-eval/report.mjs`，**原样写入**：

```js
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
```

- [ ] **Step 4: 跑测试确认通过**

Run（仓库根目录）: `node --test scripts/retrieval-eval/`
Expected: 9 个测试全部 pass，退出码 0。

- [ ] **Step 5: Commit**

```bash
git add scripts/retrieval-eval/report.mjs scripts/retrieval-eval/report.test.mjs
git commit -m "feat(eval): 阈值评估指标纯函数（生产口径 top-4 召回/误命中/分隔带 + 报告渲染）"
```

---

### Task 3: eval.mjs 编排脚本 + README

**Files:**
- Create: `scripts/retrieval-eval/eval.mjs`
- Create: `scripts/retrieval-eval/README.md`

**Interfaces:**
- Consumes: Task 2 的 `computeReport`/`renderReport`/`PROD_TOP_K`；Task 1 的 `questions.json` 与 `fixtures/*.md`
- Consumes（后端 API，已现场核实，不得改动）：
  - `POST /api/v1/identity/login`，body `{username, password}` → `data.token`；后续请求头 `Authorization: Bearer <token>`
  - `POST /api/v1/knowledge/datasets`，body `{name(≤50), description(≤200)}` → `data.id`（Long 序列化为字符串）
  - `POST /api/v1/knowledge/datasets/{id}/documents`，multipart 字段名 `file` → `data.status`
  - `GET /api/v1/knowledge/datasets/{id}/documents?page=1&size=20` → `data.list[]`，元素含 `name`/`status`（`pending`/`processing`/`ready`/`failed`）/`errorMessage`
  - `POST /api/v1/knowledge/datasets/{id}/retrieve`，body `{query, topK: 10, scoreThreshold: 0}` → `data[]`，元素含 `documentName`/`score`（服务端按分数降序）
  - `DELETE /api/v1/knowledge/datasets/{id}` → 软删级联
  - `GET /api/v1/admin/provider/settings/embedding-model` → `data.modelName`（仅 Admin；403/失败时降级为「未知」，不中断评估）
  - 统一信封 `{code, message, data, traceId}`，`code === 200` 为成功

- [ ] **Step 1: 实现 eval.mjs**

`scripts/retrieval-eval/eval.mjs`，**原样写入**：

```js
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
```

- [ ] **Step 2: 语法与单测回归**

Run（仓库根目录）:

```bash
node --check scripts/retrieval-eval/eval.mjs && node --test scripts/retrieval-eval/
```

Expected: `--check` 无输出；9 个测试 pass，退出码 0。

- [ ] **Step 3: 缺环境变量的防呆验证**

Run: `node scripts/retrieval-eval/eval.mjs`
Expected: stderr 输出 `缺少环境变量 HIFY_EVAL_USER / HIFY_EVAL_PASSWORD（账密不入仓库，运行时提供）`，退出码 1。**不发任何网络请求**（真实环境跑通属用户验收，非本任务范围）。

- [ ] **Step 4: 写 README**

`scripts/retrieval-eval/README.md`，**原样写入**（注意：下面外层围栏是四个反引号，因为 README 自身含 ``` 代码块；写入文件时只写围栏内的内容）：

````markdown
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
node --test scripts/retrieval-eval/
```
````

- [ ] **Step 5: Commit**

```bash
git add scripts/retrieval-eval/eval.mjs scripts/retrieval-eval/README.md
git commit -m "feat(eval): 阈值评估编排脚本（建库→传档→轮询→采分→报告→清理，--keep 保留）+ README"
```

---

### Task 4: CLAUDE.md 仓库布局补 scripts/

**Files:**
- Modify: `CLAUDE.md`（「仓库布局」代码块，`deploy/` 行之后、`docker-compose.yml` 行之前插一行）

**Interfaces:**
- Produces: 无代码接口；仓库布局文档与实际目录一致（brainstorm 已拍板新增顶层 `scripts/`）。

- [ ] **Step 1: 修改 CLAUDE.md**

在「仓库布局」代码块中，把：

```
├── deploy/              # nginx 配置、.env.example（真实 .env 不入库）
├── docker-compose.yml
```

改为：

```
├── deploy/              # nginx 配置、.env.example（真实 .env 不入库）
├── scripts/             # 手跑工具脚本，不进 CI（retrieval-eval/ 检索阈值评估集）
├── docker-compose.yml
```

- [ ] **Step 2: 验证仅此一处改动**

Run: `git diff --stat`
Expected: 仅 `CLAUDE.md` 1 文件、+1 行。

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: 仓库布局补顶层 scripts/（手跑工具脚本，检索阈值评估集落位）"
```

---

## 验收与收尾（用户手动，非 Codex 范围）

按 spec §6 执行，属验收阶段，不写进任何 Task：

1. 真实环境跑 `HIFY_EVAL_USER=... HIFY_EVAL_PASSWORD=... node scripts/retrieval-eval/eval.mjs > report.md`，
   确认一条命令出报告；再跑一次确认不因重名失败；`--keep` 跑一次确认评估库能在前端查看后手动删除
2. 对照报告拍板新 `score-threshold` 默认值 → 改 `server/src/main/resources/application.yml`
   （该值来自报告，无法预写进计划；改动时在 yml 注释补一句「评估日期 + 模型名 + 推荐区间」）
3. 三步症状验收：真实知识库命中测试问无关问题 → 0 命中且相关问题仍命中；
   conversation 无关问题 → 无引用卡片；可选 W3a 工作流未命中方向 `count=0` 分流
