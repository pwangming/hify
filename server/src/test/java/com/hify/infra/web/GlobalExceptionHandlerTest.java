package com.hify.infra.web;

import com.hify.common.Result;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

/**
 * {@link GlobalExceptionHandler} 的测试。
 *
 * <p>用 {@code standaloneSetup}：只挂一个临时 Controller + 我们的 advice，不启动整个 Spring 容器。
 * 这是测试「Web 层行为」的轻量方式——能验证真实的 HTTP 状态码与 JSON 信封，又不需要数据库。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 业务异常_按错误码转信封并设HTTP状态() throws Exception {
        mockMvc.perform(get("/test/biz"))
                .andExpect(status().isNotFound())          // 10005 绑定的 HTTP 状态
                .andExpect(jsonPath("$.code").value(10005))
                .andExpect(jsonPath("$.message").value("知识库不存在"));
    }

    @Test
    void 参数校验失败_10001并带字段错误数组() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data[0].field").value("name"));
    }

    @Test
    void 未预期异常_兜底10000且不泄露细节() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(10000))
                .andExpect(jsonPath("$.message").value("系统繁忙"))
                // 原始异常信息绝不能出现在响应里
                .andExpect(content().string(not(containsString("secret detail"))));
    }

    @Test
    void 路径参数类型不匹配_转400_10001而非兜底500() throws Exception {
        // 给 Long 路径参数传非数字，Spring 抛 MethodArgumentTypeMismatchException，
        // 必须单独接住转 400/10001，否则被 Exception 兜底误判为 500/10000。
        mockMvc.perform(get("/test/typed/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                // 原始异常细节（类型名等）绝不出现在响应里
                .andExpect(content().string(not(containsString("Long"))));
    }

    @Test
    void 未知路径_转404而非被兜底吞成500() {
        // NoResourceFoundException 由 DispatcherServlet 在「无匹配处理器」时抛出，
        // 用 standaloneSetup 不易真实触发，这里直接调用处理方法验证映射逻辑。
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/v1/nope");

        ResponseEntity<Result<Object>> response = new GlobalExceptionHandler().handleNoResource(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(CommonError.NOT_FOUND.code(), response.getBody().code());
    }

    /** 仅用于测试的临时 Controller。 */
    @RestController
    static class TestController {

        @GetMapping("/test/biz")
        public void biz() {
            throw new BizException(CommonError.NOT_FOUND, "知识库不存在");
        }

        @PostMapping("/test/validate")
        public void validate(@Valid @RequestBody SampleRequest request) {
            // 走到这里说明校验通过；本测试只触发校验失败分支
        }

        @GetMapping("/test/boom")
        public void boom() {
            throw new RuntimeException("secret detail");
        }

        @GetMapping("/test/typed/{id}")
        public void typed(@PathVariable Long id) {
            // 仅用于触发路径参数类型转换失败；传非数字 id 即抛 MethodArgumentTypeMismatchException
        }
    }

    record SampleRequest(@NotBlank String name) {
    }
}
