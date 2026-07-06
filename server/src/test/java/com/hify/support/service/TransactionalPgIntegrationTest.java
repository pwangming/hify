package com.hify.support.service;

import org.springframework.transaction.annotation.Transactional;

/** 放在 service 包下承载测试事务注解，避免 ArchUnit 的生产分层规则误报测试 support 类。 */
@Transactional
public abstract class TransactionalPgIntegrationTest {
}
