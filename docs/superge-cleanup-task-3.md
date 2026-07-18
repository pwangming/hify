# 留账清理轮 Task 3

Status: DONE

## Red Evidence

`cd server && mvn -q test -Dtest='UsageLogServiceTest'; echo EXIT=$?` 在新增字段前预期编译失败；随后实现 CallLogRow/CallLogItem 后测试可编译运行。

## Green Evidence

`cd server && mvn -q -Dtest=UsageLogServiceTest test; echo EXIT=$?`（放权执行）→ `EXIT=0`。

调用日志响应以 additive 方式透出 durationMs/status/errorCode，历史 null 耗时保留。
