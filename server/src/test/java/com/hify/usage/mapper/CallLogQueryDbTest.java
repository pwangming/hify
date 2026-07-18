package com.hify.usage.mapper;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** llm_call_log 游标分页查询连库验证：时间窗必选、可选过滤、(create_time,id) 双键降序翻页。 */
class CallLogQueryDbTest extends PgIntegrationTest {

    @Autowired
    LlmCallLogMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    OffsetDateTime start = OffsetDateTime.parse("2026-07-17T00:00:00+08:00");
    OffsetDateTime end = OffsetDateTime.parse("2026-07-18T00:00:00+08:00");

    @BeforeEach
    void seed() {
        jdbc.update("insert into llm_call_log (user_id, app_id, model_id, prompt_tokens, "
                + "completion_tokens, source, create_time) values "
                + "(1, 10, 5, 100, 50, 'conversation', '2026-07-17 10:00:00+08'), "
                + "(1, 10, 5, 200, 60, 'workflow',     '2026-07-17 11:00:00+08'), "
                + "(2, 20, 6, 300, 70, 'conversation', '2026-07-17 12:00:00+08')");
    }

    @Test
    void 无游标首页降序_source过滤生效() {
        List<LlmCallLogMapper.CallLogRow> all = mapper.selectPage(start, end,
                null, null, null, null, null, null, 10);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).userId()).isEqualTo(2L);

        List<LlmCallLogMapper.CallLogRow> conv = mapper.selectPage(start, end,
                null, null, null, "conversation", null, null, 10);
        assertThat(conv).hasSize(2);
    }

    @Test
    void 游标翻页不重不漏() {
        List<LlmCallLogMapper.CallLogRow> page1 = mapper.selectPage(start, end,
                null, null, null, null, null, null, 2);
        LlmCallLogMapper.CallLogRow last = page1.get(1);
        List<LlmCallLogMapper.CallLogRow> page2 = mapper.selectPage(start, end,
                null, null, null, null, last.createTime(), last.id(), 2);
        assertThat(page2).hasSize(1);
        assertThat(page2.get(0).id())
                .isNotIn(page1.stream().map(LlmCallLogMapper.CallLogRow::id).toList());
    }
}
