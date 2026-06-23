package com.hify.infra.web;

import com.hify.common.Result;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 全局异常处理器（api-standards.md 第 5.4 节的「兜底链」）。
 *
 * <p>{@code @RestControllerAdvice} = 把这里的 {@code @ExceptionHandler} 方法应用到所有 Controller，
 * 并把返回值按 JSON 写回。有了它，Controller 里就<b>不用写 try-catch、不用手写失败 Result</b>，
 * 业务只管 {@code throw new BizException(...)}。
 *
 * <p>三条兜底分支：
 * <ol>
 *   <li>{@link BizException} —— 业务主动抛的，按其错误码转信封、设对应 HTTP 状态；这是<b>预期内</b>
 *       的失败，不打 error 日志（否则全是噪声）。</li>
 *   <li>{@link MethodArgumentNotValidException} —— {@code @Valid} 校验失败，统一 10001，
 *       data 携带字段错误数组供前端逐项标红。</li>
 *   <li>其余 {@link Exception} —— 未预期异常，统一 10000「系统繁忙」，
 *       <b>原始异常只进日志</b>（带 traceId），细节绝不出现在响应里。</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：按错误码绑定的 HTTP 状态返回。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> handleBizException(BizException ex) {
        return ResponseEntity
                .status(ex.errorCode().status())
                .body(Result.fail(ex.errorCode(), ex.getMessage()));
    }

    /** 参数校验失败：10001 + 字段错误数组。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorItem> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toItem)
                .toList();
        Result<Object> body = new Result<>(
                CommonError.PARAM_INVALID.code(),
                CommonError.PARAM_INVALID.defaultMessage(),
                fieldErrors,
                currentTraceId());
        return ResponseEntity.status(CommonError.PARAM_INVALID.status()).body(body);
    }

    /**
     * 路径参数/查询参数类型转换失败（如把非数字传给 {@code Long id}），Spring 抛
     * {@link MethodArgumentTypeMismatchException}。这属于「请求格式错」，归 10001/400，
     * 否则会被下面的 {@code Exception} 兜底误判为 500。message 只点出参数名（用户可读），
     * 不带类型/类名等内部细节。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .status(CommonError.PARAM_INVALID.status())
                .body(Result.fail(CommonError.PARAM_INVALID, "参数 '" + ex.getName() + "' 格式错误"));
    }

    /**
     * 访问的 URL 没有对应处理器（Spring 抛 {@link NoResourceFoundException}）。
     * 必须单独接住转成 404，否则会被下面的 {@code Exception} 兜底误判为 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Object>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity
                .status(CommonError.NOT_FOUND.status())
                .body(Result.fail(CommonError.NOT_FOUND));
    }

    /** 兜底：未预期异常。原始异常只打日志，响应只给「系统繁忙」。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity
                .status(CommonError.INTERNAL_ERROR.status())
                .body(Result.fail(CommonError.INTERNAL_ERROR));
    }

    private static FieldErrorItem toItem(FieldError fe) {
        return new FieldErrorItem(fe.getField(), fe.getDefaultMessage());
    }

    /** 取当前 traceId（与 {@link TraceIdFilter} 写入的键一致）。 */
    private static String currentTraceId() {
        return org.slf4j.MDC.get(TraceIdFilter.TRACE_ID_KEY);
    }

    /** 校验失败时每个字段的错误项，序列化进 data 数组：{@code {"field":"name","message":"不能为空"}}。 */
    public record FieldErrorItem(String field, String message) {
    }
}
