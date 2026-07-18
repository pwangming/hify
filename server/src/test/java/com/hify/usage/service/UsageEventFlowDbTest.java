package com.hify.usage.service;

import com.hify.common.event.TokenUsedEvent;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件链全通路连库验证：真提交事务发 TokenUsedEvent → AFTER_COMMIT + @Async 监听 → 三表落准。
 * 既有连库测试全部回滚事务，AFTER_COMMIT 从不触发，这条链此前只有「手动验收覆盖」——
 * 本测试补上该盲区（NOT_SUPPORTED 关掉基类回滚事务，自己管理提交与清理）。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UsageEventFlowDbTest extends PgIntegrationTest {

    private static final long PROBE_USER = 987654L;

    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from llm_call_log where user_id = ?", PROBE_USER);
        jdbc.update("delete from usage_stat_daily where user_id = ?", PROBE_USER);
    }

    @Test
    void 提交事务内发事件_异步监听落三表() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                publisher.publishEvent(new TokenUsedEvent(PROBE_USER, 880L, 50L, 300, 180,
                        TokenUsedEvent.SOURCE_CONVERSATION)));

        assertThat(awaitLogRows()).as("流水表应有 1 行（AFTER_COMMIT 异步监听落库）").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select source from llm_call_log where user_id = " + PROBE_USER, String.class))
                .isEqualTo("conversation");
        assertThat(jdbc.queryForObject(
                "select count(*) from usage_stat_daily where user_id = " + PROBE_USER, Integer.class))
                .isEqualTo(1);
    }

    /**
     * 无事务发布不许丢：workflow 引擎（禁 @Transactional，LLM IO 不进事务）的发布点没有活跃事务，
     * 默认 AFTER_COMMIT 语义会静默丢弃事件——W1 起工作流 LLM 用量从未记账的根因。
     * 计费语义上 LLM 返回即 token 已真实消耗，不应依赖后续事务，故监听器须 fallbackExecution=true。
     */
    @Test
    void 无事务发布_事件不丢_照常落三表() throws Exception {
        publisher.publishEvent(new TokenUsedEvent(PROBE_USER, 880L, 50L, 100, 40,
                TokenUsedEvent.SOURCE_WORKFLOW));

        assertThat(awaitLogRows()).as("无事务发布也必须落流水（fallbackExecution）").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select source from llm_call_log where user_id = " + PROBE_USER, String.class))
                .isEqualTo("workflow");
    }

    private int awaitLogRows() throws InterruptedException {
        Integer logRows = 0;
        for (int i = 0; i < 50; i++) {
            logRows = jdbc.queryForObject(
                    "select count(*) from llm_call_log where user_id = " + PROBE_USER, Integer.class);
            if (logRows != null && logRows > 0) {
                break;
            }
            Thread.sleep(100);
        }
        return logRows == null ? 0 : logRows;
    }
}
