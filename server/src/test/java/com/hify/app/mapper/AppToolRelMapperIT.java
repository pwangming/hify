package com.hify.app.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.app.api.dto.AppConfig;
import com.hify.app.entity.App;
import com.hify.app.entity.AppToolRel;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppToolRelMapperIT extends PgIntegrationTest {

    @Autowired
    private AppToolRelMapper mapper;

    @Autowired
    private AppMapper appMapper;

    @Test
    void 插入与按appId读取有序() {
        App app = new App();
        app.setName("tool-rel-it");
        app.setType("chat");
        app.setModelId(1L);
        app.setConfig(new AppConfig(null, false));
        app.setOwnerId(1L);
        app.setStatus("enabled");
        appMapper.insert(app);

        AppToolRel a = new AppToolRel(); a.setAppId(app.getId()); a.setToolId(1L);
        AppToolRel b = new AppToolRel(); b.setAppId(app.getId()); b.setToolId(2L);
        mapper.insert(a); mapper.insert(b);

        List<Long> toolIds = mapper.selectList(new LambdaQueryWrapper<AppToolRel>()
                        .eq(AppToolRel::getAppId, app.getId()).orderByAsc(AppToolRel::getId))
                .stream().map(AppToolRel::getToolId).toList();
        assertThat(toolIds).containsExactly(1L, 2L);
    }
}
