# Task 8 Report: DatasetDetail polling + retry + failed reason

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/web && pnpm vitest run src/views/knowledge
Result: FAIL
Key failures:
- filename > 200 chars was not blocked before upload
- failed document retry button did not exist
- processing document polling did not refresh
Test Files: 1 failed | 1 passed
Tests: 3 failed | 21 passed
```

Green, frontend:

```text
Command: cd /home/wang/playlab/hify/web && pnpm vitest run && pnpm typecheck
Results:
Test Files: 31 passed (31)
Tests: 212 passed (212)
vue-tsc --noEmit exited 0
```

Green, backend:

```text
Command: cd /home/wang/playlab/hify/server && mvn test
Results:
Tests run: 379, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-03T15:51:18+08:00
```

## Changes

- Added pending/processing polling with automatic stop at terminal states.
- Added failed document retry button for owner/admin and refresh after retry.
- Added failed status tooltip with `errorMessage`.
- Added frontend filename length precheck and upload success copy change.
- Changed drawer pagination from deprecated `small` prop to `size="small"`.
