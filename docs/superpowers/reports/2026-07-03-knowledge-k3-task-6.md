# Task 6 Report: frontend API base for K3

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/web && pnpm vitest run src/api
Result: FAIL
Key failures:
- retryDocument is not a function
- listEmbeddingModels is not a function
- getEmbeddingSetting/saveEmbeddingSetting are not functions
- @/api/admin/knowledge cannot be resolved
- uploadDocument missing { timeout: 120_000 }
Test Files: 4 failed | 4 passed
Tests: 5 failed | 32 passed
```

Green:

```text
Command: cd /home/wang/playlab/hify/web && pnpm vitest run src/api && pnpm typecheck
Results:
Test Files: 8 passed (8)
Tests: 38 passed (38)
vue-tsc --noEmit exited 0
```

## Changes

- Added `KbDocument.errorMessage` and `EmbeddingSetting` types.
- Added `config.uploadTimeoutMs`.
- Added upload timeout, `retryDocument`, `listEmbeddingModels`, embedding setting APIs, and admin `reembedAll`.
- Added 10001 no-field-errors fallback toast in request interceptor.
