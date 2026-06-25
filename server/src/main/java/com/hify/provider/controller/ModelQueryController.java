package com.hify.provider.controller;

import com.hify.common.Result;
import com.hify.provider.api.dto.ModelView;
import com.hify.provider.service.ModelQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成员侧模型查询接口（成员族 /api/v1/provider/**，任意登录用户可访问；非 admin 专属）。
 * 给前端应用弹窗的模型选择器提供「可用」模型列表。协议层：调 service 包 Result，无业务逻辑。
 */
@RestController
@RequestMapping("/api/v1/provider/models")
public class ModelQueryController {

    private final ModelQueryService modelQueryService;

    public ModelQueryController(ModelQueryService modelQueryService) {
        this.modelQueryService = modelQueryService;
    }

    /** 列出可用模型；type 默认且本轮仅 chat（embedding 留 knowledge 轮）。 */
    @GetMapping
    public Result<List<ModelView>> list(@RequestParam(defaultValue = "chat") String type) {
        return Result.ok(modelQueryService.listUsableChatModels(type));
    }
}
