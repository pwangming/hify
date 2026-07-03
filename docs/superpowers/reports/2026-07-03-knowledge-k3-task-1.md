# Task 1 Report: V15/V16 迁移 + SystemSetting + KbDocument.errorMessage + yml

## Status

DONE

## Red / Green Evidence

Task 1 is structural and has no new failing unit test step in the plan.

Green:

```text
Command: cd /home/wang/playlab/hify/server && mvn test
Results:
Tests run: 340, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-03T15:00:06+08:00
```

## Changes

- Added `V15__kb_chunk_embedding.sql`: `kb_chunk.embedding vector(1024)`, HNSW cosine index, `kb_document.error_message`.
- Added `V16__create_system_setting.sql`: soft-deletable `system_setting` KV table and partial unique index.
- Added `SystemSetting` entity and `SystemSettingMapper`.
- Added `KbDocument.errorMessage`.
- Added `hify.knowledge.embedding-batch-size` default config.
