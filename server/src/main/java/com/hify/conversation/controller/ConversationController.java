package com.hify.conversation.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.Result;
import com.hify.common.exception.BizException;
import com.hify.conversation.dto.ConversationView;
import com.hify.conversation.dto.MessageView;
import com.hify.conversation.dto.RenameConversationRequest;
import com.hify.conversation.dto.SendMessageRequest;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.dto.StreamPayloads;
import com.hify.conversation.service.ConversationService;
import com.hify.conversation.service.StreamEvent;
import com.hify.infra.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * 会话接口（成员族 /api/v1/conversation/**，按当前用户过滤）。
 * 协议层：@Valid → 取当前用户 → 调 service → 包 Result；无业务逻辑、无 @Transactional。
 * {@code POST /messages} 一次性 JSON；{@code POST /messages/stream} 流式 SSE（四事件）。
 */
@RestController
@RequestMapping("/api/v1/conversation")
public class ConversationController {

    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    public ConversationController(ConversationService conversationService, ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/messages")
    public Result<SendMessageResponse> send(@Valid @RequestBody SendMessageRequest request) {
        return Result.ok(conversationService.send(
                request.appId(), request.conversationId(), request.content(), CurrentUserHolder.current()));
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendStream(@Valid @RequestBody SendMessageRequest request) {
        Flux<ServerSentEvent<String>> events = conversationService.sendStream(
                        request.appId(), request.conversationId(), request.content(), CurrentUserHolder.current())
                .map(this::toSse)
                .onErrorResume(ex -> Flux.just(toErrorSse(ex)));   // 连接后错误→error 事件，再正常完成
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
        // 合并心跳，终态（done/error）一到即完成（取消心跳与上游）
        return Flux.merge(events, heartbeat)
                .takeUntil(sse -> "done".equals(sse.event()) || "error".equals(sse.event()));
    }

    @GetMapping("/messages")
    public Result<List<MessageView>> history(@RequestParam Long conversationId) {
        return Result.ok(conversationService.history(conversationId, CurrentUserHolder.current()));
    }

    @GetMapping("/conversations")
    public Result<List<ConversationView>> listConversations(@RequestParam Long appId) {
        return Result.ok(conversationService.listConversations(appId, CurrentUserHolder.current()));
    }

    @DeleteMapping("/conversations/{id}")
    public Result<Void> deleteConversation(@PathVariable Long id) {
        conversationService.deleteConversation(id, CurrentUserHolder.current());
        return Result.ok(null);
    }

    @PostMapping("/conversations/{id}/rename")
    public Result<Void> renameConversation(@PathVariable Long id,
                                           @Valid @RequestBody RenameConversationRequest req) {
        conversationService.renameConversation(id, req.title(), CurrentUserHolder.current());
        return Result.ok(null);
    }

    private ServerSentEvent<String> toSse(StreamEvent e) {
        if (e instanceof StreamEvent.Delta d) {
            return sse("message", new StreamPayloads.Delta(d.text()));
        } else if (e instanceof StreamEvent.Done done) {
            return sse("done", new StreamPayloads.Done(done.conversationId(), done.messageId(),
                    new StreamPayloads.Usage(done.promptTokens(), done.completionTokens())));
        } else {
            throw new IllegalStateException("Unknown StreamEvent: " + e.getClass());
        }
    }

    private ServerSentEvent<String> toErrorSse(Throwable ex) {
        int code = ex instanceof BizException b ? b.errorCode().code() : 10000;
        String msg = ex instanceof BizException b ? b.getMessage() : "系统繁忙";
        return sse("error", new StreamPayloads.Error(code, msg, org.slf4j.MDC.get("traceId")));
    }

    private ServerSentEvent<String> sse(String event, Object payload) {
        try {
            return ServerSentEvent.<String>builder().event(event).data(objectMapper.writeValueAsString(payload)).build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex); // 载荷为内部 record，不应失败
        }
    }
}
