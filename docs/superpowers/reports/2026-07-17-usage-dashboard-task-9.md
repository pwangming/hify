# Task 9 Report: CallLogList 页面

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/CallLogList.spec.ts
Results:
Expected fetchCallLogs to be called once, but the Task 6 placeholder called it zero times
Test Files: 1 failed
Tests: 1 failed
exit=1
```

Green:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/CallLogList.spec.ts && pnpm typecheck
Results:
Test Files: 1 passed
Tests: 1 passed
vue-tsc --noEmit
exit=0
```

## Changes

- Replaced the placeholder with a default seven-day call-log query.
- Added time, source, user, app, and model filters with 31-day prevalidation.
- Added cursor-based append loading and button removal when exhausted.
- Added resolved names, localized source labels, token formatting, and existing `formatDateTime` reuse.

## Deviations

- Modified out-of-list `web/src/composables/useNameMaps.ts` to return its existing `users`, `apps`, and `models` refs. Task 9 Step 2 explicitly permits this shape adjustment so filter selects can enumerate loaded names; existing resolver consumers remain compatible.
- Used `vi.hoisted` for page fixtures because Vitest 4 hoists mock factories, and registered Element Plus in the isolated spec so scoped table slots are exercised with real slot arguments. Assertions and behavior from the plan are unchanged.
