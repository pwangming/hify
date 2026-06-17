package com.hify.infra.security;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前用户的访问入口——业务代码取"当前登录用户"的唯一正道。
 *
 * <p>它把 Spring Security 的上下文封在内部，业务代码只需 {@code CurrentUserHolder.current()}
 * 即可拿到 {@link CurrentUser}，<b>无需 import 任何 Spring Security 类</b>，符合 code-organization.md
 * "当前用户从 infra 获取"的约定。
 *
 * <p>命名说明：code-organization.md 把该职责称作"infra 的 SecurityContextHolder"，但该类名已被
 * Spring Security 占用（本类内部正是读它），为避免每次 import 混淆，这里命名为 {@code CurrentUserHolder}，
 * 职责与文档所述一致。
 */
public final class CurrentUserHolder {

    private CurrentUserHolder() {
    }

    /**
     * 取当前登录用户；取不到（未认证）直接抛 401。
     *
     * <p>{@code /api/v1/**} 受保护路由进到 Controller 时一定已认证，所以正常业务里 current() 必有值；
     * 取不到通常意味着在放行路由或非请求线程里误用，属编程/配置问题，按未认证处理。
     */
    public static CurrentUser current() {
        CurrentUser user = currentOrNull();
        if (user == null) {
            throw new BizException(CommonError.UNAUTHORIZED);
        }
        return user;
    }

    /** 取当前登录用户，未认证时返回 null（用于"登录可选"的少数场景）。 */
    public static CurrentUser currentOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser;
        }
        return null;
    }
}
