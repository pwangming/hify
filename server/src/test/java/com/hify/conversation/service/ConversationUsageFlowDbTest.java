package com.hify.conversation.service;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 非 Agent 聊天路径（8 参 appendAssistant，同步与 SSE 共用）的计量回归：经 Spring 代理真实调用后
 * usage 三表必须落账。历史 bug：8 参重载缺 @Transactional 且 this. 委托 9 参绕过代理，发布无事务
 * → AFTER_COMMIT 静默丢事件，自 Agent 轮引入重载起非 Agent 聊天漏记用量/配额。
 * 本测试锚定该行为不再回归（NOT_SUPPORTED 关掉基类回滚事务，走真提交）。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConversationUsageFlowDbTest extends PgIntegrationTest {

    private static final long PROBE_USER = 987655L;

    @Autowired
    ConversationStore store;
    @Autowired
    JdbcTemplate jdbc;

    private Long conversationId;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from llm_call_log where user_id = ?", PROBE_USER);
        jdbc.update("delete from usage_stat_daily where user_id = ?", PROBE_USER);
        if (conversationId != null) {
            jdbc.update("delete from message where conversation_id = ?", conversationId);
            jdbc.update("delete from conversation where id = ?", conversationId);
        }
    }

    @Test
    void 八参重载经代理调用_计量事件不丢_流水落账() throws Exception {
        conversationId = jdbc.queryForObject(
                "insert into conversation (app_id, user_id, title) values (880, " + PROBE_USER
                        + ", '计量探针') returning id", Long.class);
        store.appendAssistant(conversationId, "回答", 120, 60, PROBE_USER, 880L, 50L, List.of());

        Integer logRows = 0;
        for (int i = 0; i < 50; i++) {
            logRows = jdbc.queryForObject(
                    "select count(*) from llm_call_log where user_id = " + PROBE_USER, Integer.class);
            if (logRows != null && logRows > 0) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(logRows).as("非 Agent 聊天路径必须落用量流水").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select source from llm_call_log where user_id = " + PROBE_USER, String.class))
                .isEqualTo("conversation");
    }
}
