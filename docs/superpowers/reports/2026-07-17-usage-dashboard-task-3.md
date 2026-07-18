# Task 3 Report: provider 模型单价

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='AiModelServiceTest,ModelQueryServiceTest' | tail -8; echo exit=${PIPESTATUS[0]}
Results:
cannot find symbol: class ModelPrice
BUILD FAILURE
exit=1
```

Green:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='com.hify.provider.**.*Test' | tail -8; echo exit=${PIPESTATUS[0]}
Results:
Tests run: 138, Failures: 0, Errors: 0, Skipped: 0
exit=0
```

## Changes

- Added nullable non-negative input/output prices to provider entity and admin DTOs.
- Used `FieldStrategy.ALWAYS` so PUT requests can clear prices with null.
- Persisted and projected prices in create/update/list flows.
- Added the top-level provider API `ModelPrice` record and batch price lookup.
- Added `ProviderFacade.getModelPrices` and its thin implementation delegation.
- Updated all affected provider tests and added price persistence/query/delegation coverage.

## Deviations

- Modified `server/src/test/java/com/hify/provider/controller/AdminModelControllerTest.java`, which is outside the Task 3 Files list, because `ModelResponse` gained two canonical record components and Maven test compilation requires its existing direct constructor call to add `null, null`.
- The plan's `ModelQueryServiceTest` snippet called `verifyNoMoreInteractions(modelMapper)` after an unverified expected `selectBatchIds` call, which necessarily fails Mockito verification. Added `verify(modelMapper).selectBatchIds(List.of(5L))` first, preserving and strengthening the intended assertion that the subsequent empty-input call performs no query.
