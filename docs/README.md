# docs/ 目录导航

四类文档，按「我现在要干什么」挑：

| 我要… | 去哪 |
|---|---|
| 写代码前搞清楚该怎么设计 | [`architecture/`](architecture) —— 分层、建表、接口、前端、测试等规范，索引见根目录 `CLAUDE.md` |
| 确认某轮改动真的生效了 | [`self-check.md`](self-check.md) —— 自检手册，按轮次时间序，一轮一节 |
| 手工点一遍接口做验收 | [`postman/`](postman) —— Postman 集合，用法与清单见 [`postman/README.md`](postman/README.md) |
| 跑端到端浏览器测试 | [`e2e-guide.md`](e2e-guide.md) —— Playwright 操作手册（原理见 `architecture/testing-standards.md` 第五节） |
| 回看某轮是怎么想 / 怎么拆的 | [`superpowers/`](superpowers) —— `specs/` 设计、`plans/` 实现计划、`reports/` 逐任务报告，按日期命名 |

`superpowers/` 下的文档是**历史记录**，只反映当时的决策，不随代码更新；
判断"现在应该怎么做"一律以 `architecture/` 和 `CLAUDE.md` 为准。
