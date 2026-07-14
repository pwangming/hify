package com.hify.tool.service;

import com.hify.common.exception.BizException;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ToolFacadeImplTest {

    private final ToolMapper mapper = Mockito.mock(ToolMapper.class);
    private final ToolFacadeImpl facade = new ToolFacadeImpl(new ToolRegistry(mapper, List.of()));

    private static Tool enabled(long id) {
        Tool t = new Tool(); t.setId(id); t.setEnabled(true); t.setSource("builtin"); t.setName("n" + id);
        return t;
    }

    @Test
    void 全部现存启用_校验通过() {
        when(mapper.selectList(any())).thenReturn(List.of(enabled(1), enabled(2)));
        assertThatCode(() -> facade.validateToolIds(List.of(1L, 2L))).doesNotThrowAnyException();
    }

    @Test
    void 含不存在id_抛PARAM_INVALID() {
        when(mapper.selectList(any())).thenReturn(List.of(enabled(1)));
        assertThatThrownBy(() -> facade.validateToolIds(List.of(1L, 99L)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 空集合_直接通过() {
        assertThatCode(() -> facade.validateToolIds(List.of())).doesNotThrowAnyException();
        assertThat(facade.getToolCallbacks(List.of())).isEmpty();
    }
}
