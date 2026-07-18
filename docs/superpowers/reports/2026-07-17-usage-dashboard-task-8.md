# Task 8 Report: UsageDashboard 页面

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/UsageDashboard.spec.ts
Results:
Tests: 2 failed
Missing card-total-tokens and tab-user DOM elements in the Task 6 placeholder
exit=1
```

Green:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/UsageDashboard.spec.ts && pnpm typecheck
Results:
Test Files: 1 passed
Tests: 2 passed
vue-tsc --noEmit
exit=0
```

## Changes

- Replaced the placeholder with date presets, custom 92-day validation, and parallel statistic loading.
- Added total-token, call-count, and estimated-cost cards with incomplete-price warning.
- Added the dual-axis trend chart and app/user/model ranking tabs.
- Added name resolution, formatting, call-log navigation, and responsive card styling.

## Deviations

- The plan test fixture constants were referenced inside hoisted `vi.mock` factories, which causes a temporal-dead-zone failure under Vitest 4 before the component is tested. Moved the unchanged fixtures into `vi.hoisted`.
- This repository's isolated component tests do not globally install Element Plus. Registered Element Plus and stubbed `router-link`/transition in this spec so scoped table slots receive their real arguments and tab clicks exercise the production component.
