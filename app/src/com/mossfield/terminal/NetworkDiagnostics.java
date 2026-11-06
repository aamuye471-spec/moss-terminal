package com.mossfield.terminal;

import java.net.InetAddress;
import java.net.Socket;
import java.util.TimeZone;

/**
 * NetworkDiagnostics：v1.7 网络诊断（一键测连通性，结果说人话）。
 *
 * v1.6：默认后端改成 DeepSeek（api.deepseek.com/anthropic），不再连 Anthropic。
 * v1.7：入口经 SetupManager.resolveBaseUrl 兜底——就算传进来的是
 * api.anthropic.com，也按 DeepSeek 测；诊断本身永远不连 Anthropic 地址。
 * 测两样：生效的中转地址（默认 DeepSeek）的域名能不能解析、443 端口通不通。
 * 本方法要在后台线程调用（有网络 IO，带 5 秒超时）。
 */
public class NetworkDiagnostics {

    private static final int TIMEOUT_MS = 5000;

    /** 跑完整诊断，返回人话报告（多行文本，直接显示给用户）。 */
    public static String run(String effectiveBaseUrl) {
        // v1.7：先过 choke point——Anthropic 地址一律按 DeepSeek 测。
        String base = SetupManager.resolveBaseUrl(effectiveBaseUrl);
        String host = hostOf(base);
        StringBuilder sb = new StringBuilder();
        sb.append("当前后端：").append(base).append("\n");
        sb.append("（v1.7 默认走 DeepSeek，本 App 不再连 Anthropic 的 api.anthropic.com。）\n");
        sb.append("手机时区：").append(TimeZone.getDefault().getID()).append("\n\n");
        if (host == null) {
            sb.append("中转地址格式不对：要以 http:// 或 https:// 开头。\n");
            sb.append("当前用的是默认 DeepSeek 地址，不用填也能用。\n");
            return sb.toString();
        }

        // 1. 生效地址的 DNS
        sb.append("1）").append(host).append(" 的 DNS 解析：");
        InetAddress[] addrs = null;
        try {
            addrs = InetAddress.getAllByName(host);
            sb.append("能解析\n   解析到：");
            for (int i = 0; i < Math.min(addrs.length, 3); i++) {
                if (i > 0) sb.append("、");
                sb.append(addrs[i].getHostAddress());
            }
            sb.append("\n");
        } catch (Exception e) {
            sb.append("解析失败\n   原因：").append(shortErr(e)).append("\n");
            sb.append("   DNS 都问不到这个地址：换个网络（比如切 Wi-Fi/流量）再测一次；\n");
            sb.append("   或者确认中转地址填得对不对。\n");
        }

        // 2. 生效地址的 443 连通
        sb.append("\n2）").append(host).append(":443 连通：");
        if (addrs != null && addrs.length > 0) {
            try (Socket s = new Socket()) {
                s.connect(new java.net.InetSocketAddress(addrs[0], 443), TIMEOUT_MS);
                sb.append("能连上\n   说明到 DeepSeek（或你填的中转站）是通的。\n");
                sb.append("   （真正能不能用，还要看你的 DeepSeek Key 是否有效，\n");
                sb.append("    重启会话后进终端试一次才知道。）\n");
            } catch (Exception e) {
                sb.append("连不上\n   原因：").append(shortErr(e)).append("\n");
                sb.append("   DNS 通但连不上：可能是网络本身不通，或运营商/地区网络问题。\n");
            }
        } else {
            sb.append("没测（DNS 都失败了，连的目标地址都没有）\n");
        }

        sb.append("\n结论：\n");
        if (addrs != null && addrs.length > 0) {
            sb.append("后端地址能解析；如果上面第 2 步也通，网络就没问题，\n");
            sb.append("连不上就是 Key 的问题（去菜单 → 设置 API Key 检查）。\n");
        } else {
            sb.append("后端地址解析失败，这是连不上的直接原因。换网络重测。\n");
        }
        return sb.toString();
    }

    /** 从 http(s)://host[:port][/path] 里取 host；取不到返回 null。 */
    static String hostOf(String url) {
        try {
            java.net.URI u = new java.net.URI(url);
            return u.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.toString();
        if (m.length() > 120) m = m.substring(0, 120) + "…";
        return m;
    }
}
