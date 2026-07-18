# Task 10 Report: 供应商模型单价表单 + 全量回归

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test src/views/admin/provider/__tests__/ProviderDetail.spec.ts
Results:
Test Files: 1 failed
Tests: 2 failed, 16 passed
Missing input-price control and price table column
exit=1
```

Feature green:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test src/views/admin/provider/__tests__/ProviderDetail.spec.ts
Results:
Test Files: 1 passed
Tests: 18 passed
exit=0
```

Backend full regression:

```text
Command: cd /home/wang/playlab/hify/server && mvn verify > /tmp/hify-mvn-verify.log 2>&1; echo exit=$?
Results:
Tests run: 716, Failures: 0, Errors: 0, Skipped: 0
exit=0
```

Frontend full regression:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test && pnpm typecheck && pnpm lint && pnpm build; echo exit=$?
Results:
Test Files: 60 passed
Tests: 410 passed
vue-tsc: passed
eslint: passed
vite build: passed
exit=0
```

## Changes

- Added nullable input/output prices to frontend model contracts.
- Added create/edit input-number controls and full PUT price semantics.
- Added model table pricing display with half-configured models shown as unconfigured.
- Added priced and unpriced creation coverage plus table display coverage.
- Completed backend and frontend full regressions.

## Deviations

- Modified out-of-list `web/src/api/admin/__tests__/model.spec.ts` because adding required `ModelForm.inputPrice/outputPrice` fields makes its typed request fixture fail TypeScript compilation; added `null, null` without weakening assertions.
- The plan's Maven log path points to an unavailable executor-specific UUID directory. Wrote the same full `mvn verify` output to `/tmp/hify-mvn-verify.log`; command semantics and exit-code judgment are unchanged.
