package com.hify.tool.dto;

import jakarta.validation.constraints.NotBlank;

/** 鉴权头输入（value 明文，服务端立即加密）。 */
public record AuthHeaderInput(@NotBlank String name, @NotBlank String value) {}
