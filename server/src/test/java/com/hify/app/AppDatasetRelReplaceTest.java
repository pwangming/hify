package com.hify.app;

import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.dto.UpdateAppRequest;
import com.hify.app.service.AppService;
import com.hify.infra.security.CurrentUser;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 绑定全量替换的真库测试：软删旧行 + 重插同一 dataset 不撞部分唯一索引（mock 测不到索引行为）。 */
class AppDatasetRelReplaceTest extends PgIntegrationTest {

    @Autowired
    private AppService appService;
    @Autowired
    private JdbcTemplate jdbc;

    private final CurrentUser admin = new CurrentUser(1L, "admin", CurrentUser.ROLE_ADMIN);

    @Test
    void 编辑重复保存同一批绑定_软删加重插_不撞唯一索引() {
        Long dsId = jdbc.queryForObject(
                "insert into dataset(name, owner_id) values ('K4绑定库', 1) returning id", Long.class);
        AppResponse created = appService.create(
                new CreateAppRequest("K4绑定应用", null, "chat", null, null, List.of(dsId), List.of()), admin);
        // 两次保存同一绑定：第一次软删后重插，第二次亦然——部分唯一索引只看未删行，不应报冲突
        appService.update(created.id(), new UpdateAppRequest("K4绑定应用", null, null, null, List.of(dsId), List.of()), admin);
        appService.update(created.id(), new UpdateAppRequest("K4绑定应用", null, null, null, List.of(dsId), List.of()), admin);
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from app_dataset_rel where app_id = ? and deleted = false",
                Integer.class, created.id()));
        assertEquals(List.of(dsId), appService.get(created.id()).datasetIds());
    }
}
