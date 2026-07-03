# Task 3 Report: embedding setting service + facade + admin API

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='EmbeddingSettingServiceTest,AdminSettingControllerTest,ProviderFacadeImplTest'
Result: BUILD FAILURE
Key failures:
- EmbeddingSettingResponse cannot be found
- EmbeddingSettingService cannot be found
- AdminSettingController cannot be found
Finished at: 2026-07-03T15:23:08+08:00
```

Green:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='EmbeddingSettingServiceTest,AdminSettingControllerTest,ProviderFacadeImplTest'
Results:
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-03T15:24:47+08:00
```

## Changes

- Added `EmbeddingSettingService` backed by `system_setting`.
- Added setting DTOs and admin controller endpoints:
  `GET/PUT /api/v1/admin/provider/settings/embedding-model`.
- Added save-time embedding probe with 1024 dimension validation.
- Added `ProviderFacade.getEmbeddingModel()` and implementation using the configured model id.
- Added controller/service/facade tests.
