package com.hify.workflow.service.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionEvaluatorTest {

    @Test
    void 相等与不等_按字符串比较() {
        assertTrue(ConditionEvaluator.evaluate("退款类", "==", "退款类"));
        assertFalse(ConditionEvaluator.evaluate("退款类", "==", "咨询类"));
        assertTrue(ConditionEvaluator.evaluate("1.0", "!=", "1"));   // 刻意：字符串语义，不做数值归一
    }

    @Test
    void 数值比较_BigDecimal语义() {
        assertTrue(ConditionEvaluator.evaluate("2", ">", "0"));
        assertTrue(ConditionEvaluator.evaluate("2.5", ">=", "2.50"));   // BigDecimal compareTo 视为相等
        assertTrue(ConditionEvaluator.evaluate("-1", "<", "0"));
        assertTrue(ConditionEvaluator.evaluate("3", "<=", "3"));
        assertFalse(ConditionEvaluator.evaluate("0", ">", "0"));
    }

    @Test
    void 数值比较_两侧允许空白() {
        assertTrue(ConditionEvaluator.evaluate(" 2 ", ">", " 1 "));
    }

    @Test
    void 包含与不包含() {
        assertTrue(ConditionEvaluator.evaluate("我要退款", "contains", "退款"));
        assertFalse(ConditionEvaluator.evaluate("我要退款", "contains", "发票"));
        assertTrue(ConditionEvaluator.evaluate("我要退款", "notContains", "发票"));
    }

    @Test
    void 数字运算符遇非数字_抛IllegalArgument_消息含实际值() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConditionEvaluator.evaluate("查到了", ">", "0"));
        assertTrue(ex.getMessage().contains("查到了"));
    }

    @Test
    void 空串参与数值比较_抛IllegalArgument() {   // 被跳过引用渲染为空串后误用数字比较的场景
        assertThrows(IllegalArgumentException.class,
                () -> ConditionEvaluator.evaluate("", ">", "0"));
    }

    @Test
    void 非法运算符_抛IllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConditionEvaluator.evaluate("a", "=~", "b"));
        assertEquals(true, ex.getMessage().contains("=~"));
    }
}
