package com.hify.support;

import com.hify.support.service.TransactionalPgIntegrationTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 连库集成测试基类（K4 引入，全模块复用）：静态单例 pgvector 容器 + 完整 Spring 上下文
 * （Flyway 全量迁移只跑一遍，顺带真验所有迁移脚本）。@Transactional 让每个测试自动回滚，
 * 各测试类共享容器但数据互不污染。运行前提：本机 Docker 已启动。
 */
@SpringBootTest
public abstract class PgIntegrationTest extends TransactionalPgIntegrationTest {

    // 官方 postgres 镜像无 vector 扩展，必须用 pgvector 镜像（与生产 docker-compose 同款）
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start(); // 单例：JVM 内所有子类共享；结束由 Testcontainers Ryuk 兜底回收
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
