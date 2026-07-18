# Task 2 Report: TokenUsedEvent source + 三步双写

## Status

DONE

## Red / Green Evidence

Event-signature green:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='UsageServiceTest,UsageEventListenerTest,ConversationStoreTest,LlmNodeExecutorTest' | tail -8; echo exit=${PIPESTATUS[0]}
Results:
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
exit=0
```

Red:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest=UsageRecordDbTest | tail -10; echo exit=${PIPESTATUS[0]}
Results:
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
expected: "workflow" but was: null
exit=1
```

Green:

```text
Command: cd /home/wang/playlab/hify/server && mvn test -Dtest='UsageServiceTest,UsageRecordDbTest' | tail -8; echo exit=${PIPESTATUS[0]}
Results:
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
exit=0
```

## Changes

- Added `source` and its two legal-value constants to `TokenUsedEvent`.
- Updated all four event construction sites and asserted conversation/workflow sources.
- Persisted `source` in `llm_call_log`.
- Added the `usage_stat_daily` UPSERT mapper.
- Added the third `recordUsage` write in the existing transaction while leaving `checkQuota` unchanged.
- Added a real-database test covering all three writes and aggregate accumulation.

## Deviations

- The two existing captor tests use JUnit `assertEquals` rather than AssertJ. Their new source assertions follow the existing file style instead of the plan snippet's `assertThat`; assertion strength and coverage are unchanged.
