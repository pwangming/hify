# Task 7 Report: admin system settings page

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/web && pnpm vitest run src/views/admin/system
Result: FAIL
Key failure:
- Failed to resolve import "@/views/admin/system/SystemSettings.vue"
Test Files: 1 failed
Tests: no tests
```

Green:

```text
Command: cd /home/wang/playlab/hify/web && pnpm vitest run src/views/admin/system && pnpm typecheck
Results:
Test Files: 1 passed (1)
Tests: 5 passed (5)
vue-tsc --noEmit exited 0
```

## Changes

- Added `SystemSettings.vue` with embedding model selection and full re-embedding action.
- Added `/admin/settings` route under admin role/menu.
- Registered Element Plus `Tools` icon in the layout icon map.
