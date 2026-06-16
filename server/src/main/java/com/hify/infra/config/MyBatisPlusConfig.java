package com.hify.infra.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>{@code @MapperScan} 扫描各模块 {@code mapper/} 包下的 Mapper 接口（继承 {@code BaseMapper}），
 * 自动生成实现并注册为 Bean，供 service 注入。
 *
 * <p>{@link MybatisPlusInterceptor} 是 MyBatis-Plus 的插件入口，这里挂上分页内插件并指定
 * {@link DbType#POSTGRE_SQL}——它会把 {@code Page} 查询自动改写成 PostgreSQL 的
 * {@code limit/offset} 语句并补一条 count。注意 3.5.9+ 起分页依赖的 JSqlParser 已是独立坐标
 * （pom 里的 {@code mybatis-plus-jsqlparser}）。
 */
@Configuration
@MapperScan("com.hify.**.mapper")
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
