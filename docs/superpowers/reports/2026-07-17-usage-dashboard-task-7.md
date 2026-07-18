# Task 7 Report: ECharts + TrendChart

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/TrendChart.spec.ts
Results:
Failed to resolve import "../TrendChart.vue"
Test Files: 1 failed
exit=1
```

Green:

```text
Command: cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/TrendChart.spec.ts && pnpm typecheck
Results:
Test Files: 1 passed
Tests: 1 passed
vue-tsc --noEmit
exit=0
```

## Changes

- Added the approved `echarts` dependency and lockfile update.
- Added the single ECharts-owning component with on-demand chart modules.
- Added dual token/cost axes, reactive rerendering, resize handling, and disposal.
- Mocked ECharts modules in jsdom tests.
- Registered the ECharts ownership and testing constraints in frontend standards.

## Deviations

None.
