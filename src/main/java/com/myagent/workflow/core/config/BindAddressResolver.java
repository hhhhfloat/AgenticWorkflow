// @anchor: bindAddressResolver_intro
// 绑定地址解析：AGENT_BIND 优先，否则探测 Tailscale，最后回退 127.0.0.1
package com.myagent.workflow.core.config;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

// @anchor: bindAddressResolver_class
// 绑定地址解析器：launcher 与主服务共用同一套策略
public final class BindAddressResolver {

    // @anchor: bindAddressResolver_constants
    // 环境变量 key 与默认回退地址
    public static final String BIND_ENV_KEY = "AGENT_BIND";
    public static final String DEFAULT_BIND = "127.0.0.1";

    private BindAddressResolver() {}

    // @anchor: bindAddressResolver_resolve
    // 解析绑定地址：环境变量优先，否则探测 Tailscale，最后回退本机回环
    public static String resolve() {
        String v = System.getenv(BIND_ENV_KEY);
        if (v != null && !v.isBlank()) return v.trim();

        String ts = detectTailscaleIp();
        if (ts != null) {
            System.out.println("📱 自动检测到 Tailscale IP: " + ts);
            return ts;
        }

        System.out.println("⚠️ 未检测到 Tailscale IP，回退到 127.0.0.1（仅本机可访问）");
        return DEFAULT_BIND;
    }

    // @anchor: bindAddressResolver_detectTailscaleIp
    // 遍历网卡，找 100.64.0.0/10 段（Tailscale CGNAT）的 IPv4 地址
    private static String detectTailscaleIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress ip = ia.getAddress();
                    if (ip instanceof Inet4Address && isTailscaleCgnat(ip)) {
                        return ip.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // @anchor: bindAddressResolver_isTailscaleCgnat
    // 判断是否落在 100.64.0.0/10 网段
    private static boolean isTailscaleCgnat(InetAddress ip) {
        byte[] b = ip.getAddress();
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }
}