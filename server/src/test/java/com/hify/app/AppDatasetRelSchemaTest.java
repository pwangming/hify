package com.hify.app;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V18 表结构与部分唯一索引行为验证（顺带首次真库跑通 V1~V18 全量 Flyway 迁移链）。 */
class AppDatasetRelSchemaTest extends PgIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Long appId;

    @BeforeEach
    void seedApp() {
        appId = jdbc.queryForObject(
                "insert into app(name, type, owner_id) values ('K4测试应用', 'chat', 1) returning id",
                Long.class);
    }

    @Test
    void 同一应用重复绑定同一知识库_违反部分唯一索引() {
        jdbc.update("insert into app_dataset_rel(app_id, dataset_id) values (?, ?)", appId, 100L);
        assertThrows(DuplicateKeyException.class, () ->
                jdbc.update("insert into app_dataset_rel(app_id, dataset_id) values (?, ?)", appId, 100L));
    }

    @Test
    void 软删后可重新绑定_部分唯一索引只看未删行() {
        jdbc.update("insert into app_dataset_rel(app_id, dataset_id, deleted) values (?, ?, true)", appId, 100L);
        jdbc.update("insert into app_dataset_rel(app_id, dataset_id) values (?, ?)", appId, 100L);
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from app_dataset_rel where app_id = ? and deleted = false",
                Integer.class, appId));
    }
}
