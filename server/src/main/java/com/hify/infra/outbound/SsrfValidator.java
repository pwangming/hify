package com.hify.infra.outbound;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Function;

/**
 * SSRF 防护（deployment.md §5）：对 DNS 解析后的<b>全部</b> IP 校验，任一命中禁区即拒绝——
 * 回环 / RFC1918 私网 / link-local(169.254 云元数据) / any / 组播 / IPv6 ULA(fc00::/7)。
 * 容器服务名（postgres/sandbox）解析结果是容器网段私网 IP，被私网规则自动覆盖。
 * 已知边界（spec §3）：不做 DNS pinning，一期威胁模型（内网部署+受信团队）可接受。
 * 内网白名单为推迟项（spec §1），真有需求时在此查 system_setting 放行。
 */
@Component
public class SsrfValidator {

    private final Function<String, InetAddress[]> resolver;

    public SsrfValidator() {
        this(SsrfValidator::resolveAll);
    }

    /** 测试注入假解析用。 */
    SsrfValidator(Function<String, InetAddress[]> resolver) {
        this.resolver = resolver;
    }

    /** host 合法则静默返回；解析失败或任一解析 IP 命中禁区抛 BizException(10001)。 */
    public void validate(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.apply(host);
        } catch (RuntimeException e) {
            throw new BizException(CommonError.PARAM_INVALID, "目标域名无法解析：" + host);
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || a.isAnyLocalAddress() || a.isMulticastAddress() || isIpv6UniqueLocal(a)) {
                throw new BizException(CommonError.PARAM_INVALID,
                        "目标地址禁止访问（内网/保留地址）：" + host);
            }
        }
    }

    /** IPv6 ULA fc00::/7——Java 的 isSiteLocalAddress 只认已废弃的 fec0::/10，须手动补。 */
    private static boolean isIpv6UniqueLocal(InetAddress a) {
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
    }

    private static InetAddress[] resolveAll(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
