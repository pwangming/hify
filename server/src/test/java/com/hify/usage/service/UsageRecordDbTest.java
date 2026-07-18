package com.hify.usage.service;

import com.hify.common.event.TokenUsedEvent;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** recordUsage 双写连库验证：流水 + usage_stat_daily 同事务落准。 */
class UsageRecordDbTest extends PgIntegrationTest {

    @Autowired
    UsageService usageService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void recordUsage_三表落准_重复调用聚合累加() {
        usageService.recordUsage(new TokenUsedEvent(
                7L, 88L, 5L, 300, 180, TokenUsedEvent.SOURCE_WORKFLOW));
        usageService.recordUsage(new TokenUsedEvent(
                7L, 88L, 5L, 100, 20, TokenUsedEvent.SOURCE_WORKFLOW));

        Map<String, Object> log = jdbc.queryForMap(
                "select source, prompt_tokens from llm_call_log where user_id = 7 order by id limit 1");
        assertThat(log.get("source")).isEqualTo("workflow");
        assertThat(((Number) log.get("prompt_tokens")).longValue()).isEqualTo(300L);

        Map<String, Object> stat = jdbc.queryForMap(
                "select prompt_tokens, completion_tokens, call_count from usage_stat_daily "
                        + "where user_id = 7 and app_id = 88 and model_id = 5");
        assertThat(((Number) stat.get("prompt_tokens")).longValue()).isEqualTo(400L);
        assertThat(((Number) stat.get("completion_tokens")).longValue()).isEqualTo(200L);
        assertThat(((Number) stat.get("call_count")).longValue()).isEqualTo(2L);
    }
}
