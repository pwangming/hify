package com.hify.tool.service.mcp;

import com.hify.infra.outbound.SsrfValidator;

import java.net.InetAddress;
import java.util.function.Function;

/**
 * 测试用：放行任意 host 的 SsrfValidator。
 *
 * <p>为什么需要：FakeMcpServer 跑在 127.0.0.1 = 回环地址，真 SsrfValidator 一定拒。
 * SsrfValidator 留了包私有构造器接受自定义解析函数（设计好的测试缝），但它在 com.hify.infra.outbound
 * 包下、本测试在 com.hify.tool.service.mcp 包，跨包访问不到，故走反射。
 *
 * <p>解析结果固定给 192.0.2.1（TEST-NET-1，RFC5737 文档保留段）——是公网地址不会被规则拒，
 * 而真正的连接仍由 URL 里的 127.0.0.1 发起（SsrfValidator 只做校验、不参与连接）。
 */
final class TestSsrf {

    private TestSsrf() {}

    static SsrfValidator permissive() {
        Function<String, InetAddress[]> publicResolver = host -> {
            try {
                return new InetAddress[]{ InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1}) };
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        try {
            var ctor = SsrfValidator.class.getDeclaredConstructor(Function.class);
            ctor.setAccessible(true);
            return ctor.newInstance(publicResolver);
        } catch (Exception e) {
            throw new IllegalStateException("构造放行版 SsrfValidator 失败", e);
        }
    }
}
