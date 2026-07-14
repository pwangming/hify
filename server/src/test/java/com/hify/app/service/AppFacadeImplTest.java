package com.hify.app.service;

import com.hify.app.api.AppRuntimeView;
import com.hify.app.api.dto.AppConfig;
import com.hify.app.constant.AppStatus;
import com.hify.app.constant.AppType;
import com.hify.app.entity.App;
import com.hify.app.entity.AppDatasetRel;
import com.hify.app.entity.AppToolRel;
import com.hify.app.mapper.AppDatasetRelMapper;
import com.hify.app.mapper.AppMapper;
import com.hify.app.mapper.AppToolRelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppFacadeImplTest {

    private AppMapper mapper;
    private AppDatasetRelMapper relMapper;
    private AppToolRelMapper toolRelMapper;
    private AppFacadeImpl facade;

    @BeforeEach
    void setUp() {
        mapper = mock(AppMapper.class);
        relMapper = mock(AppDatasetRelMapper.class);
        toolRelMapper = mock(AppToolRelMapper.class);
        facade = new AppFacadeImpl(mapper, relMapper, toolRelMapper);
    }

    private App app(String type, String status, Long modelId) {
        return app(type, status, modelId, new AppConfig("你是客服", false));
    }

    private App app(String type, String status, Long modelId, AppConfig config) {
        App a = new App();
        a.setId(10L);
        a.setType(type);
        a.setStatus(status);
        a.setModelId(modelId);
        a.setConfig(config);
        return a;
    }

    @Test
    void 可对话应用_返回视图含modelId与config() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app(AppType.CHAT.value(), AppStatus.ENABLED.value(), 5L));
        Optional<AppRuntimeView> v = facade.findRunnableChatApp(10L);
        assertTrue(v.isPresent());
        assertEquals(5L, v.get().modelId());
        assertEquals("你是客服", v.get().systemPrompt());
    }

    @Test
    void config_agentEnabled_true_透传到运行视图() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app(AppType.CHAT.value(), AppStatus.ENABLED.value(), 5L,
                        new AppConfig("你是客服", true)));
        Optional<AppRuntimeView> v = facade.findRunnableChatApp(10L);
        assertTrue(v.isPresent());
        assertTrue(v.get().agentEnabled());
    }

    @Test
    void config缺省agentEnabled_运行视图默认为false() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app(AppType.CHAT.value(), AppStatus.ENABLED.value(), 5L,
                        new AppConfig("你是客服", false)));
        Optional<AppRuntimeView> v = facade.findRunnableChatApp(10L);
        assertTrue(v.isPresent());
        assertEquals(false, v.get().agentEnabled());
    }

    @Test
    void 应用不存在_空() {
        when(mapper.selectById(eq(10L))).thenReturn(null);
        assertTrue(facade.findRunnableChatApp(10L).isEmpty());
    }

    @Test
    void 已停用_空() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app(AppType.CHAT.value(), AppStatus.DISABLED.value(), 5L));
        assertTrue(facade.findRunnableChatApp(10L).isEmpty());
    }

    @Test
    void 非对话型_空() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app("workflow", AppStatus.ENABLED.value(), 5L));
        assertTrue(facade.findRunnableChatApp(10L).isEmpty());
    }

    @Test
    void 未绑定模型_空() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app(AppType.CHAT.value(), AppStatus.ENABLED.value(), null));
        assertTrue(facade.findRunnableChatApp(10L).isEmpty());
    }

    @Test
    void 入参null_空() {
        assertTrue(facade.findRunnableChatApp(null).isEmpty());
    }

    @Test
    void findRunnableChatApp_带绑定的知识库ids() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app(AppType.CHAT.value(), AppStatus.ENABLED.value(), 5L));
        when(relMapper.selectList(any())).thenReturn(List.of(rel(10L, 9L), rel(10L, 8L)));
        Optional<AppRuntimeView> view = facade.findRunnableChatApp(10L);
        assertEquals(List.of(9L, 8L), view.orElseThrow().datasetIds());
    }

    @Test
    void findRunnableChatApp_带绑定的工具ids() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app(AppType.CHAT.value(), AppStatus.ENABLED.value(), 5L));
        when(toolRelMapper.selectList(any())).thenReturn(List.of(toolRel(10L, 1L), toolRel(10L, 2L)));
        Optional<AppRuntimeView> view = facade.findRunnableChatApp(10L);
        assertEquals(List.of(1L, 2L), view.orElseThrow().toolIds());
    }

    private static AppDatasetRel rel(Long appId, Long datasetId) {
        AppDatasetRel r = new AppDatasetRel();
        r.setAppId(appId);
        r.setDatasetId(datasetId);
        return r;
    }

    private static AppToolRel toolRel(Long appId, Long toolId) {
        AppToolRel r = new AppToolRel();
        r.setAppId(appId);
        r.setToolId(toolId);
        return r;
    }
}
