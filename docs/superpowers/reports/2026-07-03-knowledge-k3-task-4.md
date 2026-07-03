# Task 4 Report: knowledge async document processing pipeline

## Status

DONE

## Red / Green Evidence

Red:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='DocumentProcessJobTest'
Result: BUILD FAILURE
Key failures:
- DocumentProcessStore cannot be found
- ReembedGate cannot be found
- DocumentProcessJob cannot be found
Finished at: 2026-07-03T15:28:54+08:00
```

Green, pipeline core:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='DocumentProcessJobTest'
Results:
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-03T15:30:46+08:00
```

Green, upload/retry/controller:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='DocumentProcessJobTest,DocumentServiceTest,DocumentControllerTest'
Results:
Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-03T15:32:35+08:00
```

Full regression:

```text
Command: cd /home/wang/playlab/hify/server && mvn test
Results:
Tests run: 374, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-03T15:34:14+08:00
```

## Changes

- Added document status claim/ready/failed/reembed mapper SQL and chunk embedding SQL.
- Added `DocumentUploadedEvent`, `DocumentProcessStore`, `DocumentProcessJob`, `ReembedGate`, and `DocumentStartupHealer`.
- Changed upload response to `pending` with `chunkCount=0`; async job handles chunking and embedding after commit.
- Added failed document retry service/controller endpoint.
- Added `DocumentResponse.errorMessage`.
