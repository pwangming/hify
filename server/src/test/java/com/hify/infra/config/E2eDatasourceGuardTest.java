package com.hify.infra.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class E2eDatasourceGuardTest {

    @Test
    void url含hify_e2e时放行() {
        assertThatCode(() -> E2eDatasourceGuard.requireE2eDatabase(
                "jdbc:postgresql://localhost:5432/hify_e2e?reWriteBatchedInserts=true"))
                .doesNotThrowAnyException();
    }

    @Test
    void url不含hify_e2e时启动即失败() {
        assertThatThrownBy(() -> E2eDatasourceGuard.requireE2eDatabase(
                "jdbc:postgresql://localhost:5432/hify"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hify_e2e");
    }

    @Test
    void url为null时启动即失败() {
        assertThatThrownBy(() -> E2eDatasourceGuard.requireE2eDatabase(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
