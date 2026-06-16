package com.hify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Hify 应用入口（整个模块化单体只有这一个 main 方法）。
 *
 * <p>为什么必须有它：Spring Boot 应用需要一个带 {@code @SpringBootApplication} 的启动类作为根。
 * 它所在的包 {@code com.hify} 会成为「基础包」，Spring 会自动扫描该包及其所有子包下的组件
 * （{@code @Service}/{@code @Controller} 等），这正好覆盖了 com.hify.common、com.hify.identity…
 * 全部 10 个模块。没有这个类，工程连编译/启动都无从谈起。
 *
 * <p>{@code @SpringBootApplication} 是三个注解的合体：
 * <ul>
 *   <li>{@code @SpringBootConfiguration} —— 标记这是配置类；</li>
 *   <li>{@code @EnableAutoConfiguration} —— 按 classpath 上的依赖自动装配（如见到 PostgreSQL 驱动就配数据源）；</li>
 *   <li>{@code @ComponentScan} —— 从本包向下扫描所有 Spring 组件。</li>
 * </ul>
 */
@SpringBootApplication
public class HifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(HifyApplication.class, args);
    }
}
