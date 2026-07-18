package com.hify.usage.mapper;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** usage_stat_daily 三个聚合查询连库验证（含窗口过滤与分组）。 */
class UsageStatQueryDbTest extends PgIntegrationTest {

    @Autowired
    UsageStatQueryMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("insert into usage_stat_daily (stat_date, user_id, app_id, model_id, "
                + "prompt_tokens, completion_tokens, call_count) values "
                + "('2026-07-10', 1, 10, 5, 100, 50, 2), "
                + "('2026-07-11', 1, 10, 5, 200, 80, 1), "
                + "('2026-07-11', 2, 20, 6, 300, 90, 3), "
                + "('2026-06-01', 1, 10, 5, 999, 999, 9)");
    }

    @Test
    void 三查询窗口过滤与分组正确() {
        LocalDate start = LocalDate.parse("2026-07-01");
        LocalDate end = LocalDate.parse("2026-07-31");

        List<UsageStatQueryMapper.ModelAgg> byModel = mapper.aggregateByModel(start, end);
        assertThat(byModel).hasSize(2);
        assertThat(byModel.stream().filter(r -> r.modelId() == 5L).findFirst().orElseThrow()
                .promptTokens()).isEqualTo(300L);

        List<UsageStatQueryMapper.DailyModelAgg> daily = mapper.aggregateDaily(start, end);
        assertThat(daily).hasSize(3);
        assertThat(daily.get(0).statDate()).isEqualTo(LocalDate.parse("2026-07-10"));

        List<UsageStatQueryMapper.DimModelAgg> byUser =
                mapper.aggregateByDimension("user_id", start, end);
        assertThat(byUser).hasSize(2);
        assertThat(byUser.stream().filter(r -> r.targetId() == 2L).findFirst().orElseThrow()
                .callCount()).isEqualTo(3L);
    }
}
