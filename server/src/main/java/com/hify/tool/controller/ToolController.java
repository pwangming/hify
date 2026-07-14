package com.hify.tool.controller;

import com.hify.common.Result;
import com.hify.tool.dto.ToolView;
import com.hify.tool.service.ToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 工具接口（成员族 /api/v1/tool/**，任意登录用户可读）。协议层无业务逻辑、无 @Transactional。 */
@RestController
@RequestMapping("/api/v1/tool")
public class ToolController {

    private final ToolService toolService;

    public ToolController(ToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping("/tools")
    public Result<List<ToolView>> listTools() {
        return Result.ok(toolService.listEnabled());
    }
}
