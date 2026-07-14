package com.hify.app.service;

import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.mapper.AppDatasetRelMapper;
import com.hify.app.mapper.AppMapper;
import com.hify.app.mapper.AppToolRelMapper;
import com.hify.common.exception.BizException;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.provider.api.ProviderFacade;
import com.hify.tool.api.ToolFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** W1 解锁：type=workflow 可创建（modelId/datasetIds 与其无关可为空）；非法 type 仍 16001。 */
class AppServiceCreateWorkflowTest {

    private AppMapper appMapper;
    private AppService service;
    private final CurrentUser member = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        service = new AppService(appMapper, mock(ProviderFacade.class),
                mock(AppDatasetRelMapper.class), mock(KnowledgeFacade.class),
                mock(AppToolRelMapper.class), mock(ToolFacade.class));
    }

    @Test
    void 创建workflow应用_放行并落库() {
        AppResponse resp = service.create(
                new CreateAppRequest("工单分类器", null, "workflow", null, null, null, null), member);
        verify(appMapper).insert(any(com.hify.app.entity.App.class));
        assertEquals("workflow", resp.type());
    }

    @Test
    void 非法type_仍报16001() {
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new CreateAppRequest("x", null, "bogus", null, null, null, null), member));
        assertEquals(16001, ex.errorCode().code());
    }
}
