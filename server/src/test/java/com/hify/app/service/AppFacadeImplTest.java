package com.hify.app.service;

import com.hify.app.api.AppRuntimeView;
import com.hify.app.api.dto.AppConfig;
import com.hify.app.constant.AppStatus;
import com.hify.app.constant.AppType;
import com.hify.app.entity.App;
import com.hify.app.mapper.AppMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppFacadeImplTest {

    private AppMapper mapper;
    private AppFacadeImpl facade;

    @BeforeEach
    void setUp() {
        mapper = mock(AppMapper.class);
        facade = new AppFacadeImpl(mapper);
    }

    private App app(String type, String status, Long modelId) {
        App a = new App();
        a.setId(10L);
        a.setType(type);
        a.setStatus(status);
        a.setModelId(modelId);
        a.setConfig(new AppConfig("你是客服"));
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
}
