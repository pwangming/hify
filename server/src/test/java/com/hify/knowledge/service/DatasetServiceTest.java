package com.hify.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.dto.CreateDatasetRequest;
import com.hify.knowledge.dto.DatasetResponse;
import com.hify.knowledge.dto.UpdateDatasetRequest;
import com.hify.knowledge.entity.Dataset;
import com.hify.knowledge.mapper.DatasetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetServiceTest {

    private DatasetMapper mapper;
    private DatasetService service;

    private final CurrentUser owner = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);
    private final CurrentUser other = new CurrentUser(8L, "carol", CurrentUser.ROLE_MEMBER);
    private final CurrentUser admin = new CurrentUser(1L, "admin", CurrentUser.ROLE_ADMIN);

    @BeforeEach
    void setUp() {
        mapper = mock(DatasetMapper.class);
        service = new DatasetService(mapper);
    }

    /** bob(7) 拥有的一条知识库记录。 */
    private Dataset owned() {
        Dataset d = new Dataset();
        d.setId(10L);
        d.setName("客服知识库");
        d.setDescription("售后答疑");
        d.setOwnerId(7L);
        return d;
    }

    @Test
    void 创建_owner取当前用户_字段落库() {
        ArgumentCaptor<Dataset> captor = ArgumentCaptor.forClass(Dataset.class);

        DatasetResponse resp = service.create(new CreateDatasetRequest("客服知识库", "售后答疑"), owner);

        verify(mapper).insert(captor.capture());
        Dataset saved = captor.getValue();
        assertEquals(7L, saved.getOwnerId());
        assertEquals("客服知识库", saved.getName());
        assertEquals("售后答疑", saved.getDescription());
        assertEquals("客服知识库", resp.name());
    }

    @Test
    void 创建_撞唯一索引_转CONFLICT() {
        when(mapper.insert(any(Dataset.class))).thenThrow(new DuplicateKeyException("dup"));
        BizException ex = assertThrows(BizException.class,
                () -> service.create(new CreateDatasetRequest("客服知识库", null), owner));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 详情_不存在_NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 分页_参数非法_PARAM_INVALID() {
        BizException ex = assertThrows(BizException.class, () -> service.page(null, 0, 20));
        assertEquals(CommonError.PARAM_INVALID, ex.errorCode());
        BizException deep = assertThrows(BizException.class, () -> service.page(null, 101, 100));
        assertEquals(CommonError.PARAM_INVALID, deep.errorCode());
    }

    @Test
    void 分页_返回PageResult() {
        Page<Dataset> page = Page.of(1, 20);
        page.setRecords(List.of(owned()));
        page.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        var result = service.page("客服", 1, 20);

        assertEquals(1, result.total());
        assertEquals("客服知识库", result.list().get(0).name());
    }

    @Test
    void 更新_不存在_NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> service.update(99L, new UpdateDatasetRequest("新名", null), owner));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 更新_非owner非admin_FORBIDDEN() {
        when(mapper.selectById(10L)).thenReturn(owned());
        BizException ex = assertThrows(BizException.class,
                () -> service.update(10L, new UpdateDatasetRequest("新名", null), other));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(mapper, never()).updateById(any(Dataset.class));
    }

    @Test
    void 更新_owner_全量覆盖_description传null置空() {
        when(mapper.selectById(10L)).thenReturn(owned());
        ArgumentCaptor<Dataset> captor = ArgumentCaptor.forClass(Dataset.class);

        service.update(10L, new UpdateDatasetRequest("新名", null), owner);

        verify(mapper).updateById(captor.capture());
        assertEquals("新名", captor.getValue().getName());
        assertEquals(null, captor.getValue().getDescription()); // PUT 全量：未传视为置空
    }

    @Test
    void 更新_admin可改他人() {
        when(mapper.selectById(10L)).thenReturn(owned());
        service.update(10L, new UpdateDatasetRequest("管理员改名", null), admin);
        verify(mapper).updateById(any(Dataset.class));
    }

    @Test
    void 更新_撞唯一索引_转CONFLICT() {
        when(mapper.selectById(10L)).thenReturn(owned());
        when(mapper.updateById(any(Dataset.class))).thenThrow(new DuplicateKeyException("dup"));
        BizException ex = assertThrows(BizException.class,
                () -> service.update(10L, new UpdateDatasetRequest("重名", null), owner));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 删除_不存在_幂等不抛() {
        when(mapper.selectById(99L)).thenReturn(null);
        service.delete(99L, owner); // 不抛异常即通过
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void 删除_非owner非admin_FORBIDDEN() {
        when(mapper.selectById(10L)).thenReturn(owned());
        BizException ex = assertThrows(BizException.class, () -> service.delete(10L, other));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void 删除_owner_软删() {
        when(mapper.selectById(10L)).thenReturn(owned());
        service.delete(10L, owner);
        verify(mapper).deleteById(10L); // @TableLogic 使 deleteById = update set deleted=true
    }
}
