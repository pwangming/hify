# 留账清理轮 Task 4

Status: DONE

## Red Evidence

前端用例先加入观测字段/断言；页面未实现列时预期失败。实现后执行同一文件测试。

## Green Evidence

`cd web && pnpm vitest run src/views/admin/usage/__tests__/CallLogList.spec.ts` → `1 passed`。

`cd web && pnpm test` → 全量测试绿（Task 5 再贴最终实录）。
