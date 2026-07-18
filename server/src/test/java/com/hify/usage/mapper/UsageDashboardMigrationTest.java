package com.hify.usage.mapper;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V26 迁移验证（连库）：表/列/约束真实存在 + 回填 SQL 语义正确。
 * 回填断言用「与 V26 相同的 insert-select 文本」在测试内重跑（迁移在空库执行时回填 0 行，
 * 语义只能这样验证；两处 SQL 保持一字不差，改一处必须同步另一处）。
 */
class UsageDashboardMigrationTest extends PgIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void usage_stat_daily表与唯一索引存在() {
        Integer cols = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'usage_stat_daily' "
                        + "and column_name in ('stat_date','user_id','app_id','model_id',"
                        + "'prompt_tokens','completion_tokens','call_count')", Integer.class);
        assertThat(cols).isEqualTo(7);
        Integer uq = jdbc.queryForObject(
                "select count(*) from pg_indexes where tablename = 'usage_stat_daily' "
                        + "and indexname = 'usage_stat_daily_dim_uq'", Integer.class);
        assertThat(uq).isEqualTo(1);
    }

    @Test
    void ai_model加了单价列_llm_call_log加了source列() {
        Integer priceCols = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'ai_model' "
                        + "and column_name in ('input_price','output_price')", Integer.class);
        assertThat(priceCols).isEqualTo(2);
        Integer sourceCol = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'llm_call_log' "
                        + "and column_name = 'source'", Integer.class);
        assertThat(sourceCol).isEqualTo(1);
    }

    @Test
    void 回填SQL按北京时间归日聚合() {
        // 造两天边界数据：北京时间 2026-07-16 23:30 与 2026-07-17 00:30（UTC 表示）
        jdbc.update("insert into llm_call_log (user_id, app_id, model_id, prompt_tokens, completion_tokens, create_time) "
                + "values (1, 2, 3, 100, 50, '2026-07-16 15:30:00+00'), "
                + "       (1, 2, 3, 200, 80, '2026-07-16 16:30:00+00')");
        // 与 V26 回填一字不差的 SQL（目标改临时表以免与监听器写入混淆）
        jdbc.execute("create temp table backfill_check as "
                + "select (create_time at time zone 'Asia/Shanghai')::date as stat_date, "
                + "user_id, app_id, model_id, sum(prompt_tokens) as prompt_tokens, "
                + "sum(completion_tokens) as completion_tokens, count(*) as call_count "
                + "from llm_call_log group by 1, 2, 3, 4");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from backfill_check order by stat_date");
        assertThat(rows).hasSize(2); // 跨北京日界拆成两行
        assertThat(rows.get(0).get("stat_date").toString()).isEqualTo("2026-07-16");
        assertThat(rows.get(1).get("stat_date").toString()).isEqualTo("2026-07-17");
    }
}
