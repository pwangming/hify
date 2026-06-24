package com.hify.app.service;

import com.hify.app.api.dto.AppConfig;
import com.hify.app.constant.AppError;
import com.hify.app.constant.AppStatus;
import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.entity.App;
import com.hify.app.mapper.AppMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppServiceTest {

    private AppMapper mapper;
    private AppService service;

    private final CurrentUser member = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);

    @BeforeEach
    void setUp() {
        mapper = mock(AppMapper.class);
        service = new AppService(mapper);
    }

    private CreateAppRequest chatReq() {
        return new CreateAppRequest("客服助手", "答疑", "chat", 5L, new AppConfig("你是客服"));
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
        CreateAppRequest wf = new CreateAppRequest("流程", null, "workflow", null, null);
        BizException ex = assertThrows(BizException.class, () -> service.create(wf, member));
        assertEquals(AppError.APP_TYPE_NOT_SUPPORTED, ex.errorCode());
        verify(mapper, never()).insert(any(App.class));
    }

    @Test
    void 创建_config缺省兜底为空配置() {
        CreateAppRequest noCfg = new CreateAppRequest("无配置", null, "chat", null, null);
        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);
        service.create(noCfg, member);
        verify(mapper).insert(captor.capture());
        assertEquals(null, captor.getValue().getConfig().systemPrompt());
    }

    @Test
    void 创建_撞唯一索引_转CONFLICT() {
        when(mapper.insert(any(App.class))).thenThrow(new DuplicateKeyException("dup"));
        BizException ex = assertThrows(BizException.class, () -> service.create(chatReq(), member));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }
}
