package com.hify.workflow.service.engine;

import java.math.BigDecimal;

/**
 * 条件求值（spec §2）：==/!= 按字符串；> >= < <= 按 BigDecimal（解析失败即错，
 * 明确报错优于静默字典序）；contains/notContains 按字符串包含。无状态纯函数。
 */
final class ConditionEvaluator {

    private ConditionEvaluator() { }

    static boolean evaluate(String left, String operator, String right) {
        return switch (operator) {
            case "==" -> left.equals(right);
            case "!=" -> !left.equals(right);
            case "contains" -> left.contains(right);
            case "notContains" -> !left.contains(right);
            case ">", ">=", "<", "<=" -> numeric(left, operator, right);
            default -> throw new IllegalArgumentException("不支持的运算符：" + operator);   // validator 已拦，纯防御
        };
    }

    private static boolean numeric(String left, String operator, String right) {
        int c = parse(left, "左值").compareTo(parse(right, "右值"));
        return switch (operator) {
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "<" -> c < 0;
            default -> c <= 0;
        };
    }

    private static BigDecimal parse(String value, String side) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值比较要求" + side + "是数字，实际为：\"" + value + "\"");
        }
    }
}
