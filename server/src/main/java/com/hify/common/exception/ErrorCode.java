package com.hify.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 错误码契约（api-standards.md 第 5 节）。
 *
 * <p>设计要点：错误码与 HTTP 状态码<b>在枚举定义处一次绑定</b>。这样业务代码只管抛
 * {@code BizException(某错误码)}，由 infra 的全局异常处理器读取 {@link #status()} 设置响应状态，
 * 业务层永远不直接碰 HTTP 状态码。
 *
 * <p>谁来实现它：
 * <ul>
 *   <li>通用段（10xxx）由 {@link CommonError} 实现，全模块复用；</li>
 *   <li>各模块特有的错误码（如 usage 的 14xxx、workflow 的 18xxx）在各自模块的
 *       {@code constant/} 下定义自己的枚举，同样 implements 本接口。</li>
 * </ul>
 * 这种「一个接口，多个枚举实现」的写法，让分散在各模块的错误码共享同一套形态，
 * 而全局异常处理器只依赖本接口、无需认识每个模块的具体枚举。
 */
public interface ErrorCode {

    /** 5 位整数 MMXXX：前 2 位模块段，后 3 位模块内自增；0 表示成功。 */
    int code();

    /** 该错误码对应的 HTTP 响应状态，由全局异常处理器据此设置。 */
    HttpStatus status();

    /** 默认用户可读提示；抛异常时可被自定义 message 覆盖。 */
    String defaultMessage();
}
