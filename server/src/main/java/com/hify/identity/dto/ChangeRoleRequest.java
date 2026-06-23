package com.hify.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 改角色请求。角色限 admin|member。 */
public record ChangeRoleRequest(@NotBlank @Pattern(regexp = "admin|member") String role) {
}
