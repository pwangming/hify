package com.hify.tool.dto;

import jakarta.validation.constraints.NotBlank;

/** 鉴权头输入：name 必填；value 明文——create 必填、update 留空=保留原密文（在 service 分流校验）。 */
public record AuthHeaderInput(@NotBlank String name, String value) {}
