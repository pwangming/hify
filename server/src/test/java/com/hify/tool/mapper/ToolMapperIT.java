package com.hify.tool.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.support.PgIntegrationTest;
import com.hify.tool.entity.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolMapperIT extends PgIntegrationTest {

    @Autowired
    private ToolMapper toolMapper;

    @Test
    void 播种的两个内置工具可读且字段正确() {
        List<Tool> builtins = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getSource, "builtin").orderByAsc(Tool::getName));
        assertThat(builtins).extracting(Tool::getName)
                .containsExactly("code_executor", "http_request");
        assertThat(builtins).allMatch(t -> Boolean.TRUE.equals(t.getEnabled()));
        assertThat(builtins).allMatch(t -> t.getDescription() != null && !t.getDescription().isBlank());
        assertThat(builtins).allMatch(t -> t.getOwnerId() == null);
    }
}
