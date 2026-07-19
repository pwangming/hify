# ③ 日期组件全宽拉伸风源调查（结案）

日期：2026-07-19（留账清理轮终审阶段，timebox 30 分钟内结案）
对象：`web/src/views/admin/usage/UsageDashboard.vue` 筛选行的 `el-date-picker`（daterange），
看板轮验收时被撑满整行，当时靠 `width + max-width + flex-grow` 三保险 `!important` 压住，
风源记为「未定位」并留账。本轮定案：**不是 CSS 特异性问题，是 flex 布局问题。**

## 根因

range 编辑器的**根元素同时挂着 `.el-date-editor` 与 `.el-input__wrapper` 两个类**
（浏览器实测 className：`el-date-editor el-date-editor--daterange el-input__wrapper
el-range-editor el-tooltip__trigger`）。而：

- `el-input.css`：`.el-input__wrapper { ... flex-grow: 1 ... }`
- `el-time-picker.css`（range 编辑器样式在此，不在 el-date-picker.css——当初排查易走空）：
  `.el-date-editor.el-input__wrapper { width: var(--el-date-editor-width) }`，
  daterange 变体把该变量设为 350px
- 本页 `.usage-dashboard__filters` 是 `display: flex`

⇒ 该元素是 flex item 且 `flex-grow: 1`，在 flex 容器里**沿主轴吸收全部剩余空间**。

## 当初为何误判为「特异性被压制」

- **单写 `width` 无效**，不是因为被某条更强的规则压过，而是两者根本不在同一机制上：
  `width` 只提供 flex-basis 的起点，`flex-grow` 在其之上再分配剩余空间，最终 used width 由后者决定。
- **`max-width` 看似"生效"**，是因为 max-width 会 clamp flex 的增长量——它掩盖了真正的原因，
  于是三保险被一起保留，`!important` 也被误认为必需。
- 实际 `:deep` 生成的 `.usage-dashboard__filters[data-v-x] .el-date-editor--daterange`
  特异性(0,3,0) 远高于 EP 的 `.el-input__wrapper`(0,1,0)，**从来不需要 `!important`**。

## 双向实证（Playwright 量真实页面计算样式）

| 样式写法 | offsetWidth | computed flex-grow |
|---|---|---|
| 仅 `width: 260px`（去掉 grow 控制） | **815px**（撑满整行，故障复现） | 1 |
| `flex: none; width: 260px`（根治） | **260px** | 0 |

同时实测确认：`parentDisplay === 'flex'`、根元素 className 含 `el-input__wrapper`——
根因链三个环节全部有实测支撑，非推断。

## 结论与落地

`UsageDashboard.vue` 三保险 `!important` 全部拿掉，改为：

```scss
:deep(.el-date-editor--daterange) {
  flex: none;
  width: 260px;
}
```

探针为临时文件，取证完成后已删除（不进回归套件——它断言的是像素宽度，属脆弱断言，
留下会成为后续改版的噪声源）。

## 可复用教训

1. **组件在 flex 容器里"不听 width"，先查 `flex-grow` 而非怀疑特异性**；EP 的输入类组件
   根元素普遍带 `.el-input__wrapper{flex-grow:1}`，这是通用陷阱而非本页特例。
2. **`max-width` 能压住但 `width` 压不住 = flex-grow 在作祟**的典型信号，见此组合应直接查 grow。
3. EP 的 range 编辑器样式在 `el-time-picker.css`，不在 `el-date-picker.css`——查 EP 源码 CSS
   要按类名 grep 全目录，别按组件名猜文件。
4. 给第三方组件打补丁前，先量一次 computed style（className/父容器 display/生效值），
   比连续叠加 `!important` 快得多——本次从零到定案不足 30 分钟，而当初四层取证未果。
