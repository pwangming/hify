package com.hify.knowledge.controller;

import com.hify.common.Result;
import com.hify.knowledge.service.ReembedService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** admin knowledge 接口（仅 Admin）。 */
@RestController
@RequestMapping("/api/v1/admin/knowledge")
public class AdminKnowledgeController {

    private final ReembedService reembedService;

    public AdminKnowledgeController(ReembedService reembedService) {
        this.reembedService = reembedService;
    }

    @PostMapping("/documents/reembed")
    public Result<Void> reembed() {
        reembedService.start();
        return Result.ok(null);
    }
}
