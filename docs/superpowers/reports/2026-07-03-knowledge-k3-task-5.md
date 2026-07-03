# Task 5 Report: admin full re-embedding endpoint

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='ReembedServiceTest,AdminKnowledgeControllerTest'
Result: BUILD FAILURE
Key failures:
- ReembedService cannot be found
- AdminKnowledgeController cannot be found
Finished at: 2026-07-03T15:37:02+08:00
```

Green:

```text
Command: cd /home/wang/playlab/hify/server && mvn test
Results:
Tests run: 379, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-03T15:38:03+08:00
```

## Changes

- Added `ReembedService.start()` with embedding model precheck and `ReembedGate` mutual exclusion.
- Added `POST /api/v1/admin/knowledge/documents/reembed`.
- Added service and controller tests for admin/member access and in-progress conflict.
