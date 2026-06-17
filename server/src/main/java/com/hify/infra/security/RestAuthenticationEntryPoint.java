package com.hify.infra.security;

import com.hify.common.exception.CommonError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 认证入口：未认证用户访问受保护路由时，Spring Security 会调用这里。
 * 把默认的 401 页面替换为统一 {@link com.hify.common.Result} 信封（10002）。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityResponseWriter responseWriter;

    public RestAuthenticationEntryPoint(SecurityResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        responseWriter.write(response, CommonError.UNAUTHORIZED);
    }
}
