# 留账清理轮 Task 1

Status: DONE

## Red Evidence

`cd server && mvn -q test -Dtest='ConversationStoreTest'; echo EXIT=$?`

关键输出：`incompatible types: long cannot be converted to List<MessageSource>`、`cannot find symbol method durationMs()/success()/errorCode()`；`EXIT=1`。

## Green Evidence

`cd server && mvn -q test-compile; echo EXIT=$?` → `EXIT=0`。

`cd server && mvn -q -Dtest=UsageServiceTest test; echo EXIT=$?`（放权执行）→ `EXIT=0`。

实现了 TokenUsedEvent 扩字段/工厂、ConversationStore durationMs、同步/流式/Agent/Workflow 失败事件发布与耗时计时。
