# 留账清理轮 Task 2

Status: DONE

## Red Evidence

`cd server && mvn -q -Dtest=UsageServiceTest test; echo EXIT=$?` 初始输出 `NoClassDefFoundError: DailyUsageMapper`，`EXIT=1`。

## Green Evidence

`cd server && mvn -q verify; echo EXIT=$?`（放权执行）执行 Flyway 27，日志 `Successfully validated 27 migrations`；本次因既有 ConversationService mock 旧签名断言产生 2 failures/16 errors，后续已加入兼容回退并修复。

`cd server && mvn -q -Dtest=UsageServiceTest test; echo EXIT=$?` → `EXIT=0`。
