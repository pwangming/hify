package com.hify.app.service;

import com.hify.app.api.WorkflowAppView;
import com.hify.app.entity.App;
import com.hify.app.mapper.AppDatasetRelMapper;
import com.hify.app.mapper.AppMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** findWorkflowApp：存在且 type=workflow 才有值；enabled 如实透传；chat 应用/不存在返回 empty。 */
class AppFacadeWorkflowViewTest {

    private AppMapper appMapper;
    private AppFacadeImpl facade;

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        facade = new AppFacadeImpl(appMapper, mock(AppDatasetRelMapper.class));
    }

    private App app(String type, String status) {
        App a = new App();
        a.setId(42L);
        a.setType(type);
        a.setStatus(status);
        a.setOwnerId(7L);
        return a;
    }

    @Test
    void workflow应用_返回视图含owner与enabled() {
        when(appMapper.selectById(42L)).thenReturn(app("workflow", "enabled"));
        WorkflowAppView v = facade.findWorkflowApp(42L).orElseThrow();
        assertEquals(42L, v.appId());
        assertEquals(7L, v.ownerId());
        assertTrue(v.enabled());
    }

    @Test
    void 停用的workflow应用_enabled为false但仍返回() {
        when(appMapper.selectById(42L)).thenReturn(app("workflow", "disabled"));
        assertFalse(facade.findWorkflowApp(42L).orElseThrow().enabled());
    }

    @Test
    void chat应用_返回empty() {
        when(appMapper.selectById(42L)).thenReturn(app("chat", "enabled"));
        assertTrue(facade.findWorkflowApp(42L).isEmpty());
    }

    @Test
    void 不存在或入参null_返回empty() {
        when(appMapper.selectById(any())).thenReturn(null);
        assertTrue(facade.findWorkflowApp(42L).isEmpty());
        assertTrue(facade.findWorkflowApp(null).isEmpty());
    }
}
