# Task 2 Report: provider embedding factory + batch resilience + registry

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='ChatClientFactoryTest,ResilienceRegistryTest'
Result: BUILD FAILURE
Key failures:
- buildEmbeddingModel(ModelProvider, AiModel) is undefined for ChatClientFactory
- getEmbeddingModel(long) is undefined for ResilienceRegistry
- ResilientEmbeddingModel cannot be resolved to a type
Tests run: 16, Failures: 0, Errors: 16, Skipped: 0
Finished at: 2026-07-03T15:11:54+08:00
```

Green:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='ChatClientFactoryTest,ResilienceRegistryTest,ProviderErrorTest,ResilienceBundleTest'
Results:
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-03T15:13:20+08:00
```

## Changes

- Added provider errors `12005 EMBEDDING_DIMENSION_MISMATCH` and `12006 EMBEDDING_MODEL_NOT_CONFIGURED`.
- Added `ChatClientFactory.EMBEDDING_DIMENSION` and `buildEmbeddingModel`.
- Added `ResilienceBundle.buildBatch`.
- Added `ResilientEmbeddingModel`.
- Extended `ResilienceRegistry` with embedding model cache, batch bundle cache, and invalidation.
- Added/updated provider tests, including batch concurrency in the test provider helper.
