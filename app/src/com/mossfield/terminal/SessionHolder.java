package com.mossfield.terminal;

import com.termux.terminal.TerminalSession;

import java.nio.charset.StandardCharsets;

/**
 * SessionHolder：当前终端会话的静态持有者。
 *
 * MainActivity 创建会话时 set()，销毁时 clear()。
 * 悬浮窗（FloatingWindowService 在另一个组件里）通过这里把文字写进终端，
 * 不用跨进程 IPC（同一进程内静态持有即可）。
 */
public class SessionHolder {

    private static volatile TerminalSession session;

    public static void set(TerminalSession s) {
        session = s;
    }

    public static void clear() {
        session = null;
    }

    public static TerminalSession get() {
        return session;
    }

    public static boolean isRunning() {
        TerminalSession s = session;
        return s != null && s.isRunning();
    }

    /** 把文字写进终端。返回是否成功写出（会话不存在或已结束返回 false）。 */
    public static boolean writeText(String text) {
        if (text == null) return false;
        return writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 把原始字节写进终端（用于 Ctrl+C 这类控制字符）。 */
    public static boolean writeBytes(byte[] bytes) {
        TerminalSession s = session;
        if (s == null || !s.isRunning() || bytes == null) return false;
        try {
            s.write(bytes, 0, bytes.length);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
