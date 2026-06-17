package com.hify.infra.security;

import com.hify.common.exception.CommonError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 权限不足处理器：已认证但无权访问（如 Member 调 {@code /api/v1/admin/**}）时，Spring Security
 * 调用这里。把默认的 403 页面替换为统一 {@link com.hify.common.Result} 信封（10004）。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityResponseWriter responseWriter;

    public RestAccessDeniedHandler(SecurityResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        responseWriter.write(response, CommonError.FORBIDDEN);
    }
}
