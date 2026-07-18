package com.hify.usage.service;

import com.hify.common.event.TokenUsedEvent;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** recordUsage 双写连库验证：流水（含观测列）+ usage_stat_daily；失败事件只落流水；daily_usage 已废弃（V27）。 */
class UsageRecordDbTest extends PgIntegrationTest {

    @Autowired
    UsageService usageService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void recordUsage_成败分流_流水全记_聚合仅成功累加() {
        usageService.recordUsage(TokenUsedEvent.success(
                987660L, 88L, 5L, 300, 180, TokenUsedEvent.SOURCE_WORKFLOW, 1500L));
        usageService.recordUsage(TokenUsedEvent.failure(
                987660L, 88L, 5L, TokenUsedEvent.SOURCE_WORKFLOW, 800L, new IllegalStateException("x")));

        Map<String, Object> ok = jdbc.queryForMap(
                "select duration_ms, status, error_code, source from llm_call_log "
                        + "where user_id = 987660 and status = 'success'");
        assertThat(((Number) ok.get("duration_ms")).intValue()).isEqualTo(1500);
        assertThat(ok.get("error_code")).isNull();
        assertThat(ok.get("source")).isEqualTo("workflow");

        Map<String, Object> failed = jdbc.queryForMap(
                "select duration_ms, status, error_code, prompt_tokens from llm_call_log "
                        + "where user_id = 987660 and status = 'failed'");
        assertThat(((Number) failed.get("duration_ms")).intValue()).isEqualTo(800);
        assertThat(failed.get("error_code")).isEqualTo("IllegalStateException");
        assertThat(((Number) failed.get("prompt_tokens")).longValue()).isZero();

        // 聚合仅成功累加：call_count=1（失败不计），token 只含成功轮——配额与看板不被失败污染
        Map<String, Object> stat = jdbc.queryForMap(
                "select prompt_tokens, completion_tokens, call_count from usage_stat_daily "
                        + "where user_id = 987660 and app_id = 88 and model_id = 5");
        assertThat(((Number) stat.get("prompt_tokens")).longValue()).isEqualTo(300L);
        assertThat(((Number) stat.get("completion_tokens")).longValue()).isEqualTo(180L);
        assertThat(((Number) stat.get("call_count")).longValue()).isEqualTo(1L);

        // V27 已 drop daily_usage
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'daily_usage'",
                Integer.class);
        assertThat(tables).isZero();
    }
}
