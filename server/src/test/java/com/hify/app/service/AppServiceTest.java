package com.hify.app.service;

import com.hify.app.api.dto.AppConfig;
import com.hify.app.constant.AppError;
import com.hify.app.constant.AppStatus;
import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.dto.UpdateAppRequest;
import com.hify.app.entity.App;
import com.hify.app.entity.AppDatasetRel;
import com.hify.app.mapper.AppDatasetRelMapper;
import com.hify.app.mapper.AppMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.provider.api.ProviderFacade;
import com.hify.provider.api.dto.ModelView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppServiceTest {

    private AppMapper mapper;
    private ProviderFacade providerFacade;
    private AppDatasetRelMapper relMapper;
    private KnowledgeFacade knowledgeFacade;
    private AppService service;

    private final CurrentUser member = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);

    @BeforeEach
    void setUp() {
        mapper = mock(AppMapper.class);
        providerFacade = mock(ProviderFacade.class);
        relMapper = mock(AppDatasetRelMapper.class);
        knowledgeFacade = mock(KnowledgeFacade.class);
        // 默认桩：任意 modelId 视为可用，让带 modelId 的既有用例不因校验红；针对性用例各自覆盖。
        when(providerFacade.findUsableChatModel(any()))
                .thenReturn(Optional.of(new ModelView(5L, "GPT-4o", "chat", "通义千问")));
        when(providerFacade.getModelNames(any())).thenReturn(java.util.Map.of());
        when(providerFacade.filterUsableChatModelIds(any())).thenReturn(java.util.Set.of());
        service = new AppService(mapper, providerFacade, relMapper, knowledgeFacade);
    }

    private CreateAppRequest chatReq() {
        return new CreateAppRequest("客服助手", "答疑", "chat", 5L, new AppConfig("你是客服"), List.of());
    }

    @Test
    void 创建_owner取当前用户_状态默认启用_字段落库() {
        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);

        AppResponse resp = service.create(chatReq(), member);

        verify(mapper).insert(captor.capture());
        App saved = captor.getValue();
        assertEquals(7L, saved.getOwnerId());
        assertEquals(AppStatus.ENABLED.value(), saved.getStatus());
        assertEquals("chat", saved.getType());
        assertEquals(5L, saved.getModelId());
        assertEquals("你是客服", saved.getConfig().systemPrompt());
        assertEquals("客服助手", resp.name());
    }

    @Test
    void 创建_工作流型_拒绝16001() {
        CreateAppRequest wf = new CreateAppRequest("流程", null, "workflow", null, null, List.of());
        BizException ex = assertThrows(BizException.class, () -> service.create(wf, member));
        assertEquals(AppError.APP_TYPE_NOT_SUPPORTED, ex.errorCode());
        verify(mapper, never()).insert(any(App.class));
    }

    @Test
    void 创建_config缺省兜底为空配置() {
        CreateAppRequest noCfg = new CreateAppRequest("无配置", null, "chat", null, null, List.of());
        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);
        service.create(noCfg, member);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getConfig().systemPrompt());
    }

    @Test
    void 创建_模型不可用_拒绝16002() {
        when(providerFacade.findUsableChatModel(5L)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> service.create(chatReq(), member));
        assertEquals(AppError.MODEL_NOT_USABLE, ex.errorCode());
        verify(mapper, never()).insert(any(App.class));
    }

    @Test
    void 创建_modelId为null_不校验模型_放行() {
        CreateAppRequest noModel = new CreateAppRequest("无模型", null, "chat", null, null, List.of());
        service.create(noModel, member);
        verify(providerFacade, never()).findUsableChatModel(any());
        verify(mapper).insert(any(App.class));
    }

    @Test
    void 创建_带模型_响应回显modelName() {
        when(providerFacade.getModelNames(java.util.List.of(5L)))
                .thenReturn(java.util.Map.of(5L, "GPT-4o"));
        AppResponse resp = service.create(chatReq(), member); // chatReq modelId=5
        assertEquals("GPT-4o", resp.modelName());
    }

    @Test
    void 创建_撞唯一索引_转CONFLICT() {
        when(mapper.insert(any(App.class))).thenThrow(new DuplicateKeyException("dup"));
        BizException ex = assertThrows(BizException.class, () -> service.create(chatReq(), member));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @org.junit.jupiter.api.Test
    void 详情_不存在抛NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @org.junit.jupiter.api.Test
    void 分页_映射total与列表_不按owner过滤() {
        App a = new App();
        a.setId(1L); a.setName("x"); a.setType("chat"); a.setOwnerId(999L);
        a.setStatus("enabled"); a.setConfig(new com.hify.app.api.dto.AppConfig(null));
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<App> pg =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        pg.setRecords(java.util.List.of(a));
        pg.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(pg);

        com.hify.common.page.PageResult<AppResponse> result = service.page(null, null, 1, 20);
        assertEquals(1, result.total());
        assertEquals("x", result.list().get(0).name());
    }

    @org.junit.jupiter.api.Test
    void 分页_批量回显modelName() {
        App a = new App();
        a.setId(1L); a.setName("x"); a.setType("chat"); a.setOwnerId(7L); a.setModelId(5L);
        a.setStatus("enabled"); a.setConfig(new com.hify.app.api.dto.AppConfig(null));
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<App> pg =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        pg.setRecords(java.util.List.of(a));
        pg.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(pg);
        when(providerFacade.getModelNames(java.util.List.of(5L)))
                .thenReturn(java.util.Map.of(5L, "GPT-4o"));

        com.hify.common.page.PageResult<AppResponse> result = service.page(null, null, 1, 20);
        assertEquals("GPT-4o", result.list().get(0).modelName());
    }

    @org.junit.jupiter.api.Test
    void 分页_模型停用_modelName有值但modelUsable为false() {
        App a = new App();
        a.setId(1L); a.setName("x"); a.setType("chat"); a.setOwnerId(7L); a.setModelId(5L);
        a.setStatus("enabled"); a.setConfig(new com.hify.app.api.dto.AppConfig(null));
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<App> pg =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        pg.setRecords(java.util.List.of(a));
        pg.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(pg);
        when(providerFacade.getModelNames(java.util.List.of(5L)))
                .thenReturn(java.util.Map.of(5L, "GPT-4o"));
        when(providerFacade.filterUsableChatModelIds(java.util.List.of(5L)))
                .thenReturn(java.util.Set.of()); // 停用：不在可用集合

        AppResponse r = service.page(null, null, 1, 20).list().get(0);
        assertEquals("GPT-4o", r.modelName());
        org.junit.jupiter.api.Assertions.assertFalse(r.modelUsable());
    }

    @org.junit.jupiter.api.Test
    void 分页_页深超限抛PARAM_INVALID() {
        BizException ex = assertThrows(BizException.class, () -> service.page(null, null, 1000, 20));
        assertEquals(CommonError.PARAM_INVALID, ex.errorCode());
    }

    @org.junit.jupiter.api.Test
    void 分页_负数页_抛PARAM_INVALID() {
        BizException ex = assertThrows(BizException.class, () -> service.page(null, null, -1, 20));
        assertEquals(CommonError.PARAM_INVALID, ex.errorCode());
    }

    private App stored(long id, long ownerId, String status) {
        App a = new App();
        a.setId(id); a.setName("app" + id); a.setType("chat"); a.setOwnerId(ownerId);
        a.setStatus(status); a.setConfig(new com.hify.app.api.dto.AppConfig(null));
        return a;
    }

    private final CurrentUser admin = new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN);
    private UpdateAppRequest upd() {
        return new UpdateAppRequest("新名", "新描述", 9L, new com.hify.app.api.dto.AppConfig("改了"), List.of());
    }

    @org.junit.jupiter.api.Test
    void 更新_owner放行() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 7L, "enabled")); // owner=bob(7)
        AppResponse r = service.update(10L, upd(), member);
        assertEquals("新名", r.name());
        verify(mapper).updateById(any(App.class));
    }

    @org.junit.jupiter.api.Test
    void 更新_admin放行他人应用() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 999L, "enabled"));
        service.update(10L, upd(), admin);
        verify(mapper).updateById(any(App.class));
    }

    @org.junit.jupiter.api.Test
    void 更新_他人非admin_拒绝FORBIDDEN() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 999L, "enabled"));
        BizException ex = assertThrows(BizException.class, () -> service.update(10L, upd(), member));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(mapper, never()).updateById(any(App.class));
    }

    @org.junit.jupiter.api.Test
    void 更新_模型不可用_拒绝16002() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 7L, "enabled")); // owner=bob(7)
        when(providerFacade.findUsableChatModel(9L)).thenReturn(Optional.empty()); // upd() 的 modelId=9
        BizException ex = assertThrows(BizException.class, () -> service.update(10L, upd(), member));
        assertEquals(AppError.MODEL_NOT_USABLE, ex.errorCode());
        verify(mapper, never()).updateById(any(App.class));
    }

    @org.junit.jupiter.api.Test
    void 更新_modelId为null_不校验模型_放行() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 7L, "enabled"));
        UpdateAppRequest noModel = new UpdateAppRequest("新名", "新描述", null,
                new com.hify.app.api.dto.AppConfig("改了"), List.of());
        service.update(10L, noModel, member);
        verify(providerFacade, never()).findUsableChatModel(any());
        verify(mapper).updateById(any(App.class));
    }

    @org.junit.jupiter.api.Test
    void 更新_不存在_NOT_FOUND() {
        when(mapper.selectById(10L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.update(10L, upd(), member));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @org.junit.jupiter.api.Test
    void 删除_不存在_幂等返回不报错() {
        when(mapper.selectById(10L)).thenReturn(null);
        service.delete(10L, member);
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @org.junit.jupiter.api.Test
    void 删除_他人非admin_FORBIDDEN() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 999L, "enabled"));
        BizException ex = assertThrows(BizException.class, () -> service.delete(10L, member));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @org.junit.jupiter.api.Test
    void 停用_owner放行_写disabled() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 7L, "enabled"));
        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);
        service.disable(10L, member);
        verify(mapper).updateById(captor.capture());
        assertEquals("disabled", captor.getValue().getStatus());
    }

    @org.junit.jupiter.api.Test
    void 启用_owner放行_写enabled() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 7L, "disabled"));
        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);
        service.enable(10L, member);
        verify(mapper).updateById(captor.capture());
        assertEquals("enabled", captor.getValue().getStatus());
    }

    @org.junit.jupiter.api.Test
    void 停用_他人非admin_拒绝FORBIDDEN() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 999L, "enabled"));
        BizException ex = assertThrows(BizException.class, () -> service.disable(10L, member));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(mapper, never()).updateById(any(App.class));
    }

    @Test
    void 创建_带datasetIds_先校验再落绑定行() {
        when(mapper.insert(any(App.class))).thenAnswer(inv -> {
            inv.getArgument(0, App.class).setId(7L);
            return 1;
        });
        CreateAppRequest req = new CreateAppRequest("知识助手", null, "chat", null, null, List.of(9L, 8L));
        AppResponse resp = service.create(req, admin);
        verify(knowledgeFacade).validateDatasetIds(List.of(9L, 8L));
        verify(relMapper, times(2)).insert(any(AppDatasetRel.class));
        assertEquals(List.of(9L, 8L), resp.datasetIds());
    }

    @Test
    void 创建_datasetIds校验失败_透传异常不落库() {
        doThrow(new BizException(CommonError.NOT_FOUND, "知识库不存在或已删除"))
                .when(knowledgeFacade).validateDatasetIds(List.of(404L));
        CreateAppRequest req = new CreateAppRequest("知识助手", null, "chat", null, null, List.of(404L));
        assertThrows(BizException.class, () -> service.create(req, admin));
        verify(mapper, never()).insert(any(App.class));
    }

    @Test
    void 更新_全量替换绑定_先软删后插入() {
        when(mapper.selectById(7L)).thenReturn(stored(7L, 1L, "enabled"));
        UpdateAppRequest req = new UpdateAppRequest("改名", null, null, null, List.of(9L));
        service.update(7L, req, admin);
        InOrder inOrder = inOrder(relMapper);
        inOrder.verify(relMapper).delete(any());
        inOrder.verify(relMapper).insert(any(AppDatasetRel.class));
    }

    @Test
    void 更新_datasetIds为null_清空绑定_响应空列表() {
        when(mapper.selectById(7L)).thenReturn(stored(7L, 1L, "enabled"));
        UpdateAppRequest req = new UpdateAppRequest("改名", null, null, null, null);
        AppResponse resp = service.update(7L, req, admin);
        verify(relMapper).delete(any());
        verify(relMapper, never()).insert(any(AppDatasetRel.class));
        assertEquals(List.of(), resp.datasetIds());
    }

    @Test
    void 创建_重复datasetIds_去重后落库() {
        when(mapper.insert(any(App.class))).thenAnswer(inv -> {
            inv.getArgument(0, App.class).setId(7L);
            return 1;
        });
        CreateAppRequest req = new CreateAppRequest("知识助手", null, "chat", null, null, List.of(9L, 9L));
        service.create(req, admin);
        verify(relMapper, times(1)).insert(any(AppDatasetRel.class));
    }
}
