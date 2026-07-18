# Task 6 Report: 前端用量看板地基

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test src/composables/__tests__/useNameMaps.spec.ts
Results:
Failed to resolve import "../useNameMaps"
Test Files: 1 failed
exit=1
```

Green:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test src/composables/__tests__/useNameMaps.spec.ts && pnpm typecheck
Results:
Test Files: 1 passed
Tests: 1 passed
vue-tsc --noEmit
exit=0
```

## Changes

- Added usage dashboard and call-log TypeScript contracts.
- Added typed admin usage API functions.
- Added parallel user/app/model name-map loading with deleted-resource fallbacks.
- Added admin-only dashboard and call-log routes.
- Verified the existing `listApps` PageResult shape and `AdminUser.username` before implementation.

## Deviations

- Created `web/src/views/admin/usage/UsageDashboard.vue` and `web/src/views/admin/usage/CallLogList.vue`, which are absent from the Task 6 Files list but explicitly required by Step 2 as minimal placeholders so the two new lazy route imports are not dangling. Tasks 8 and 9 replace them.
