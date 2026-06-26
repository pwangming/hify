package com.hify.conversation.controller;

import com.hify.common.Result;
import com.hify.conversation.dto.MessageView;
import com.hify.conversation.dto.SendMessageRequest;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.service.ConversationService;
import com.hify.infra.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话消息接口（成员族 /api/v1/conversation/**，按当前用户过滤）。
 * 协议层：@Valid → 取当前用户 → 调 service → 包 Result；无业务逻辑、无 @Transactional。
 * 本轮一次性 .call() 返回（SSE 留下一轮）。
 */
@RestController
@RequestMapping("/api/v1/conversation/messages")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public Result<SendMessageResponse> send(@Valid @RequestBody SendMessageRequest request) {
        return Result.ok(conversationService.send(
                request.appId(), request.conversationId(), request.content(), CurrentUserHolder.current()));
    }

    @GetMapping
    public Result<List<MessageView>> history(@RequestParam Long conversationId) {
        return Result.ok(conversationService.history(conversationId, CurrentUserHolder.current()));
    }
}
