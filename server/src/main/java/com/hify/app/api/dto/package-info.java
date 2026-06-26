/**
 * app 模块对外 DTO：并入 "api" 命名接口，使依赖 app::api 的模块可引用本包下的 record。
 * Spring Modulith 的 @NamedInterface 仅作用于被注解包本身，sub-package 需各自并入同名接口。
 */
@org.springframework.modulith.NamedInterface("api")
package com.hify.app.api.dto;
