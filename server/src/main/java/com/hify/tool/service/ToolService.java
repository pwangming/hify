package com.hify.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.tool.dto.ToolView;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 工具查询（成员族只读）：列出可选（enabled）工具供 Agent 配置页勾选。注册表增删改是 T3/T4 admin 活。 */
@Service
public class ToolService {

    private final ToolMapper toolMapper;

    public ToolService(ToolMapper toolMapper) {
        this.toolMapper = toolMapper;
    }

    public List<ToolView> listEnabled() {
        return toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getEnabled, true).orderByAsc(Tool::getName))
                .stream()
                .map(t -> new ToolView(t.getId(), t.getName(), t.getDescription(), t.getSource()))
                .toList();
    }
}
