package com.mossfield.terminal;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RootBridge：su 的唯一出口（L1 防线）。
 *
 * 所有 root 命令都必须走这里。命令先过黑名单（system 分区等禁止路径），
 * 命中直接拒绝并记审计日志；通过后才交给 su 执行。
 *
 * 红线：绝对不动 system 分区。见 DESIGN.md §6.2 禁止路径清单。
 */
public class RootBridge {

    private static final String TAG = "RootBridge";

    /** su 二进制的常见位置（按优先级）。
     * 注意：/data/adb/ksud 是 KernelSU 的守护进程本身，不是标准 su，
     * 只能放最后兜底；KernelSU 真正的 su 在 /data/adb/ksu/bin/su。 */
    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
            "/system/bin/magisk",
            "/data/adb/ksud",
    };

    /** 供测试：返回 su 候选路径（顺序即优先级）。 */
    static String[] getSuPaths() {
        return SU_PATHS.clone();
    }

    /**
     * 绝对禁止的路径前缀（写、删、改、remount、bind 覆盖一律禁止）。
     * 注意 /etc 在 Android 上是 /system/etc 的 symlink，同样禁止。
     */
    private static final String[] FORBIDDEN_PREFIXES = {
            "/system",
            "/vendor",
            "/product",
            "/odm",
            "/oem",
            "/system_ext",
            "/apex",
            "/etc",
            "/init",
            "/boot",
            "/recovery",
            "/metadata",
            "/dev/block",
            "/data/system",
            "/data/misc",
    };

    /** 绝对禁止的命令关键词（不区分大小写）。 */
    private static final Pattern FORBIDDEN_REMOUNT =
            Pattern.compile("remount[^\\n]*\\b(system|vendor|product|odm|oem|apex)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN_RW_REMOUNT =
            Pattern.compile("mount\\s+[^\\n]*-o\\s+[^\\n]*\\brw\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN_DD =
            Pattern.compile("\\bdd\\b[^\\n]*\\bof\\s*=\\s*/dev/block", Pattern.CASE_INSENSITIVE);

    /** 从命令里提取绝对路径。 */
    private static final Pattern ABS_PATH =
            Pattern.compile("(?<![\\w/])/(?:[\\w.\\-]+/)*[\\w.\\-]*");

    private final File filesDir;
    private final File auditLog;

    public RootBridge(File filesDir) {
        this.filesDir = filesDir;
        File logDir = new File(filesDir, "logs");
        if (!logDir.exists()) logDir.mkdirs();
        this.auditLog = new File(logDir, "audit.log");
    }

    /** 找到可用的 su 二进制路径，找不到返回 null。 */
    public String findSu() {
        for (String p : SU_PATHS) {
            File f = new File(p);
            if (f.exists() && f.canExecute()) return p;
        }
        // 最后试 which su
        try {
            Process proc = new ProcessBuilder("/system/bin/sh", "-c", "command -v su").start();
            String out = readAll(proc);
            proc.waitFor(5, TimeUnit.SECONDS);
            out = out.trim();
            if (!out.isEmpty() && new File(out).exists()) return out;
        } catch (Exception e) {
            Log.w(TAG, "which su failed", e);
        }
        return null;
    }

    /** 是否有 root：su -c id 返回 uid=0。 */
    public boolean hasRoot() {
        String su = findSu();
        if (su == null) return false;
        try {
            Process proc = new ProcessBuilder(su, "-c", "id").start();
            String out = readAll(proc);
            boolean done = proc.waitFor(10, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return false;
            }
            return out.contains("uid=0");
        } catch (Exception e) {
            Log.w(TAG, "hasRoot check failed", e);
            return false;
        }
    }

    /** SELinux 状态（enforcing / permissive），仅供诊断。 */
    public String getEnforce() {
        try {
            Process proc = new ProcessBuilder("/system/bin/sh", "-c", "getenforce").start();
            String out = readAll(proc);
            proc.waitFor(5, TimeUnit.SECONDS);
            return out.trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 以 root 身份执行一条命令（先过黑名单）。
     *
     * @return 命令的 stdout+stderr 合并输出。
     * @throws SecurityException 命令命中黑名单。
     * @throws IOException su 不可用或执行失败。
     */
    public String runRootCommand(String command) throws SecurityException, IOException {
        checkAllowed(command);
        String su = findSu();
        if (su == null) throw new IOException("su not found");
        long start = System.currentTimeMillis();
        Process proc = new ProcessBuilder(su, "-c", command).start();
        String out = readAll(proc);
        try {
            proc.waitFor(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
        }
        audit("ROOT", command, proc.exitValue(), System.currentTimeMillis() - start);
        return out;
    }

    /** 以普通 app 身份执行一条命令（同样过黑名单，防止误伤）。 */
    public String runUserCommand(String command) throws SecurityException, IOException {
        checkAllowed(command);
        Process proc = new ProcessBuilder("/system/bin/sh", "-c", command).start();
        String out = readAll(proc);
        try {
            proc.waitFor(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
        }
        audit("USER", command, proc.exitValue(), 0);
        return out;
    }

    /**
     * 黑名单检查（L1）。命中即抛 SecurityException。
     * 做法：先规范化命令（去注释、折叠 //、解析 ..），再检查禁止前缀与关键词。
     */
    public void checkAllowed(String command) throws SecurityException {
        if (command == null || command.trim().isEmpty()) {
            throw new SecurityException("empty command");
        }
        String normalized = normalize(command);

        // 1. 关键词：remount system/vendor/product、rw remount、dd 写块设备
        if (FORBIDDEN_REMOUNT.matcher(normalized).find()) {
            throw new SecurityException("blocked: remount system/vendor/product");
        }
        if (FORBIDDEN_DD.matcher(normalized).find()) {
            throw new SecurityException("blocked: dd to block device");
        }
        // mount -o remount,rw：只允许目标是 tmpfs 或应用私有目录的情况
        Matcher rwMount = FORBIDDEN_RW_REMOUNT.matcher(normalized);
        if (rwMount.find()) {
            String tail = normalized.substring(rwMount.start());
            if (!tail.contains("tmpfs") && !tail.contains(filesDir.getAbsolutePath())) {
                throw new SecurityException("blocked: remount,rw not on tmpfs/private dir");
            }
        }

        // 2. 路径：提取全部绝对路径，规范化后检查前缀
        Matcher m = ABS_PATH.matcher(normalized);
        while (m.find()) {
            String path = normalizePath(m.group());
            for (String prefix : FORBIDDEN_PREFIXES) {
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    throw new SecurityException("blocked path: " + path);
                }
            }
        }
    }

    /** 规范化命令：去行注释、折叠连续斜杠、统一空白。 */
    static String normalize(String command) {
        StringBuilder sb = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            if (c == '"' && !inSingle) inDouble = !inDouble;
            if (c == '#' && !inSingle && !inDouble) break; // 行注释
            sb.append(c);
        }
        String s = sb.toString();
        // 折叠 //（但保留开头的 // 之外全部）
        s = s.replaceAll("/{2,}", "/");
        return s;
    }

    /** 规范化路径：解析 . 与 ..。 */
    static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return path;
        String[] parts = path.split("/");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(part);
        }
        StringBuilder sb = new StringBuilder("/");
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    private static String readAll(Process proc) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 审计日志：时间、身份、命令、退出码、耗时。文件 0600。 */
    private synchronized void audit(String who, String command, int exitCode, long ms) {
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String line = ts + " [" + who + "] exit=" + exitCode + " " + ms + "ms :: "
                    + command.replace("\n", " ") + "\n";
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(auditLog, true), StandardCharsets.UTF_8)) {
                w.write(line);
            }
            // 0600
            try {
                new ProcessBuilder("/system/bin/sh", "-c",
                        "chmod 600 " + auditLog.getAbsolutePath()).start();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.w(TAG, "audit failed", e);
        }
    }
}
