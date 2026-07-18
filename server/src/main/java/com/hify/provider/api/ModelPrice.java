package com.hify.provider.api;

import java.math.BigDecimal;

/**
 * 模型单价视图（元/百万 token；null=未配置）。跨模块 DTO 放 api 顶层包
 * （Modulith 1.4.1 不暴露 api/dto 子包）。是否「已配价」（两价均非空）由消费方判定。
 */
public record ModelPrice(BigDecimal inputPrice, BigDecimal outputPrice) {
}
