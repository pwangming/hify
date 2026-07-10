package com.hify.workflow.mapper;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V21 迁移的真库验证：三表存在、node_run 是分区表且当月分区可写、(app_id,version) 部分唯一、status check。 */
class WorkflowSchemaTest extends PgIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 三张表存在() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_name in "
                        + "('workflow_def','workflow_run','workflow_node_run') order by table_name",
                String.class);
        assertEquals(List.of("workflow_def", "workflow_node_run", "workflow_run"), tables);
    }

    @Test
    void node_run按月分区且当月分区可写并能返回自增id() {
        // 分区表：relkind = 'p'
        String relkind = jdbc.queryForObject(
                "select relkind from pg_class where relname = 'workflow_node_run'", String.class);
        assertEquals("p", relkind);
        Long id = jdbc.queryForObject(
                "insert into workflow_node_run (run_id, node_id, node_type, status) "
                        + "values (1, 'llm_1', 'llm', 'running') returning id",
                Long.class);
        assertNotNull(id);
    }

    @Test
    void def的app加version软删内唯一() {
        jdbc.update("insert into workflow_def (app_id, version, graph) values (99, 1, '{}')");
        assertThrows(Exception.class, () ->
                jdbc.update("insert into workflow_def (app_id, version, graph) values (99, 1, '{}')"));
    }

    @Test
    void run状态check约束拒绝非法值() {
        // 先合法后非法：PG 里失败语句会把当前事务打进 aborted 状态，之后的语句全报错，顺序不能反
        int ok = jdbc.update(
                "insert into workflow_run (app_id, def_id, user_id, status) values (1, 1, 1, 'running')");
        assertEquals(1, ok);
        assertThrows(Exception.class, () -> jdbc.update(
                "insert into workflow_run (app_id, def_id, user_id, status) values (1, 1, 1, 'bogus')"));
    }

    @Test
    void run表autovacuum已调密() {
        String opts = jdbc.queryForObject(
                "select array_to_string(reloptions, ',') from pg_class where relname = 'workflow_run'",
                String.class);
        assertNotNull(opts);
        assertTrue(opts.contains("autovacuum_vacuum_scale_factor=0.05"));
    }
}
