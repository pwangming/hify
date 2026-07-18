# Task 4 Report: usage 聚合统计接口 + provider 依赖白名单

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest=UsageStatServiceTest | tail -8; echo exit=${PIPESTATUS[0]}
Results:
Compilation failure: UsageStatService / UsageStatQueryMapper / usage DTO classes did not exist
exit=1
```

Green:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='UsageStatServiceTest,UsageStatQueryDbTest,ModularityTests,LayerRulesTest' | tail -8; echo exit=${PIPESTATUS[0]}
Results:
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
exit=0
```

## Changes

- Opened the documented usage-to-`provider::api` dependency solely for model pricing.
- Added overview, daily, and dimension-ranking aggregate SQL queries.
- Added backend BigDecimal cost calculation, incomplete-price handling, and 92-day validation.
- Added three admin usage statistics endpoints.
- Added real PostgreSQL coverage for time-window filtering and grouping.
- Kept module and layer architecture tests in the Task green suite.

## Deviations

- With user approval, modified out-of-list file `server/src/test/java/com/hify/LayerRulesTest.java`. The existing “mapper only used by service” predicate also treated mapper interfaces' own nested SQL projection records as forbidden external mapper consumers, making the plan-mandated `UsageStatQueryMapper.ModelAgg`/`DailyModelAgg`/`DimModelAgg` structure incompatible with the plan-mandated `LayerRulesTest` green run. The rule now excludes classes residing in `..mapper..` from the consumer side while continuing to forbid controller/dto/entity and every other non-service, non-mapper package from depending on mapper classes.
