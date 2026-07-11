package com.hify.infra.outbound;

import com.hify.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsrfValidatorTest {

    private static InetAddress[] addr(String ip) {
        try {
            return new InetAddress[]{InetAddress.getByName(ip)};
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private SsrfValidator withResolved(String ip) {
        return new SsrfValidator(host -> addr(ip));
    }

    @Test
    void 公网IP_放行() {
        assertDoesNotThrow(() -> withResolved("93.184.216.34").validate("example.com"));
    }

    @Test
    void 回环_拦截() {
        assertThrows(BizException.class, () -> withResolved("127.0.0.1").validate("localhost"));
        assertThrows(BizException.class, () -> withResolved("::1").validate("ip6-localhost"));
    }

    @Test
    void RFC1918三段_全拦截() {
        assertThrows(BizException.class, () -> withResolved("10.1.2.3").validate("a"));
        assertThrows(BizException.class, () -> withResolved("172.16.0.1").validate("b"));
        assertThrows(BizException.class, () -> withResolved("172.31.255.254").validate("c"));
        assertThrows(BizException.class, () -> withResolved("192.168.1.1").validate("d"));
    }

    @Test
    void linkLocal云元数据_拦截() {
        assertThrows(BizException.class, () -> withResolved("169.254.169.254").validate("metadata"));
    }

    @Test
    void any与组播_拦截() {
        assertThrows(BizException.class, () -> withResolved("0.0.0.0").validate("any"));
        assertThrows(BizException.class, () -> withResolved("224.0.0.1").validate("mcast"));
    }

    @Test
    void IPv6_ULA_fc00段_拦截() {
        assertThrows(BizException.class, () -> withResolved("fd12:3456::1").validate("ula"));
    }

    @Test
    void 域名解析出多IP_任一命中即拦截() {
        SsrfValidator v = new SsrfValidator(host -> new InetAddress[]{
                addr("93.184.216.34")[0], addr("192.168.1.1")[0]});   // 公网+私网混合（DNS 指回内网）
        BizException ex = assertThrows(BizException.class, () -> v.validate("evil.example.com"));
        assertTrue(ex.getMessage().contains("evil.example.com"));
    }

    @Test
    void 域名无法解析_拦截且提示() {
        SsrfValidator v = new SsrfValidator(host -> { throw new IllegalStateException("unknown host"); });
        BizException ex = assertThrows(BizException.class, () -> v.validate("no-such.example"));
        assertTrue(ex.getMessage().contains("no-such.example"));
    }
}
