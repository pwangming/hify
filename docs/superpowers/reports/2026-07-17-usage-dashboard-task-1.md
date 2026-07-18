# Task 1 Report: V26 迁移脚本 + 数据文档补账

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest=UsageDashboardMigrationTest | tail -15; echo exit=${PIPESTATUS[0]}
Results:
Tests run: 3, Failures: 2, Errors: 0, Skipped: 0
expected: 2 but was: 0
expected: 7 but was: 0
exit=1
```

Green:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest=UsageDashboardMigrationTest | tail -8; echo exit=${PIPESTATUS[0]}
Results:
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
exit=0
```

## Changes

- Added V26 model price columns, call source column, daily dashboard aggregate table, indexes, and historical backfill.
- Added a Testcontainers migration test for schema, indexes, and Beijing-date backfill semantics.
- Updated the data-model inventory and usage weak-reference ER relationships.
- Regenerated `docs/architecture/er-diagram.svg`.

## Deviations

- `docs/architecture/data-model.md` already contained and listed 19 tables, while the Task 1 Files note said “18→19”. Step 5 explicitly requires checking the current value and incrementing it, so the actual count was changed from 19 to 20.
- `docs/architecture/er-diagram.svg` is absent from the Task 1 Files list, but Step 5 explicitly requires regenerating it and Step 6 explicitly includes it in the commit. It was regenerated to keep the checked-in diagram synchronized with `er-diagram.dot`.
