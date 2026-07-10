package com.hify.workflow.service.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunContextTest {

    private RunContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new RunContext(7L, 42L);
        ctx.putOutput("start", Map.of("query", "我要退货"));
        ctx.putOutput("llm_1", Map.of("text", "退款类"));
    }

    @Test
    void 持有触发者与应用() {
        assertEquals(7L, ctx.userId());
        assertEquals(42L, ctx.appId());
    }

    @Test
    void 单变量替换() {
        assertEquals("请分类：我要退货", ctx.render("请分类：{{start.query}}"));
    }

    @Test
    void 同串多变量与空白容忍() {
        assertEquals("我要退货→退款类", ctx.render("{{ start.query }}→{{llm_1.text}}"));
    }

    @Test
    void 无变量原样返回_null入参返回null() {
        assertEquals("plain", ctx.render("plain"));
        assertNull(ctx.render(null));
    }

    @Test
    void 引用不存在的节点_抛IllegalState() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ctx.render("{{ghost.text}}"));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void 引用不存在的字段_抛IllegalState() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ctx.render("{{llm_1.score}}"));
        assertTrue(ex.getMessage().contains("score"));
    }

    @Test
    void 非字符串输出值转字符串拼入() {
        ctx.putOutput("n", Map.of("count", 3));
        assertEquals("共3条", ctx.render("共{{n.count}}条"));
    }
}
