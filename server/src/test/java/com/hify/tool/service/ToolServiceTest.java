package com.hify.tool.service;

import com.hify.tool.dto.ToolView;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ToolServiceTest {

    private final ToolMapper mapper = Mockito.mock(ToolMapper.class);
    private final ToolService service = new ToolService(mapper);

    private static Tool row(long id, String name) {
        Tool t = new Tool(); t.setId(id); t.setName(name); t.setDescription(name + "说明");
        t.setSource("builtin"); t.setEnabled(true);
        return t;
    }

    @Test
    void 列出enabled工具映射为view() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1, "code_executor"), row(2, "http_request")));
        List<ToolView> views = service.listEnabled();
        assertThat(views).extracting(ToolView::name).containsExactly("code_executor", "http_request");
        assertThat(views.get(0).id()).isEqualTo(1L);
        assertThat(views.get(0).source()).isEqualTo("builtin");
    }
}
