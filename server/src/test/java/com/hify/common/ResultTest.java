package com.hify.common;

import com.hify.common.exception.CommonError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link Result} 工厂方法的单元测试。
 *
 * <p>这是「纯单元测试」：不启动 Spring 容器、不连数据库，直接 new/调静态方法再断言。
 * 跑得飞快，是练手测试的最佳起点。
 */
class ResultTest {

    /** 每个测试后清掉 MDC，避免线程复用时 traceId 串味（coding-standards.md 第 20 条）。 */
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void ok_应返回code200与success提示() {
        Result<String> result = Result.ok("hello");

        assertEquals(200, result.code());
        assertEquals("success", result.message());
        assertEquals("hello", result.data());
    }

    @Test
    void ok_data可为null() {
        // 删除、动作类接口成功但无数据返回
        Result<Void> result = Result.ok(null);

        assertEquals(200, result.code());
        assertNull(result.data());
    }

    @Test
    void fail_用错误码默认提示() {
        Result<Object> result = Result.fail(CommonError.NOT_FOUND);

        assertEquals(10005, result.code());
        assertEquals("资源不存在", result.message());
        assertNull(result.data(), "失败响应 data 应为 null");
    }

    @Test
    void fail_自定义提示覆盖默认提示() {
        Result<Object> result = Result.fail(CommonError.NOT_FOUND, "知识库不存在");

        assertEquals(10005, result.code());
        assertEquals("知识库不存在", result.message());
    }

    @Test
    void traceId_取自MDC() {
        MDC.put("traceId", "trace-abc");

        Result<String> result = Result.ok("x");

        assertEquals("trace-abc", result.traceId());
    }

    @Test
    void traceId_无MDC上下文时为null() {
        // 没 put 过 traceId（如单元测试、非请求线程），应为 null 而不是报错
        Result<String> result = Result.ok("x");

        assertNull(result.traceId());
    }
}
