package com.hify;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 模块内分层守护测试（code-organization.md 第 5.3 节），用 ArchUnit 校验「每段代码各司其职」。
 *
 * <p>{@code @AnalyzeClasses(packages = "com.hify")} 让 ArchUnit 加载该包下所有类做静态分析；
 * 每个 {@code @ArchTest static final ArchRule} 字段就是一条会被执行的规则。
 *
 * <p>注意包匹配通配符：{@code "..xxx.."} 匹配任意层级的 xxx 包（如 {@code com.hify.app.controller}）；
 * {@code "com.hify.*.dto.."} 中的单星只匹配一层，因此<b>只命中模块级 {@code dto/}，不会误伤 {@code api/dto/}</b>。
 */
@AnalyzeClasses(packages = "com.hify")
class LayerRulesTest {

    // 依赖「目标」包一律用 com.hify 前缀限定，避免把第三方库里同名段（如 MyBatis 的
    // org.mybatis.spring.mapper）误判为越界——我们只想约束「我们自己的」分层之间的依赖。

    /** 协议层（controller、模块级 dto）不得直接碰数据访问层（mapper、entity）。 */
    @ArchTest
    static final ArchRule 协议层不碰数据访问 = noClasses()
            .that().resideInAnyPackage("..controller..", "com.hify.*.dto..")
            .should().dependOnClassesThat().resideInAnyPackage("com.hify..mapper..", "com.hify..entity..");

    /** mapper 只能被 service 层使用。 */
    @ArchTest
    static final ArchRule mapper只被service使用 = noClasses()
            .that().resideOutsideOfPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("com.hify..mapper..");

    /** api 公开契约包不得依赖任何实现细节（service/mapper/entity/controller/模块级 dto）。 */
    @ArchTest
    static final ArchRule api包不依赖实现 = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.hify..service..", "com.hify..mapper..", "com.hify..entity..",
                    "com.hify..controller..", "com.hify.*.dto..");

    // code-organization.md 第 5.3 节把「@Transactional 只在 service 层」写成一条规则；
    // ArchUnit 的 fluent API 对「类上注解」与「方法上注解」是分开表达的，故拆成下面两条，意图一致。

    /** {@code @Transactional} 不得标注在 service 层之外的类上。 */
    @ArchTest
    static final ArchRule 事务注解不在service层之外的类 = noClasses()
            .that().resideOutsideOfPackage("..service..")
            .should().beAnnotatedWith(Transactional.class);

    /** {@code @Transactional} 不得标注在 service 层之外的方法上。 */
    @ArchTest
    static final ArchRule 事务注解不在service层之外的方法 = noMethods()
            .that().areDeclaredInClassesThat().resideOutsideOfPackage("..service..")
            .should().beAnnotatedWith(Transactional.class);
}
