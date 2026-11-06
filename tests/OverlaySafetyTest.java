package com.mossfield.terminal;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OverlaySafetyTest：悬浮窗安全回归测试（v1.2 新增）。
 *
 * 1. 悬浮窗 QUICK_ACTIONS 里的每一条自动填入文字，都必须过 RootBridge 黑名单
 *    （不含 system 分区路径、不含 remount/dd 等危险关键词）。悬浮窗只是终端输入，
 *    但它绝不能成为绕过防线的口子。
 * 2. SU_PATHS 顺序：KernelSU 真正的 su（/data/adb/ksu/bin/su）必须排在
 *    守护进程 /data/adb/ksud 前面（ksud 不是标准 su，v1.1 排错了会导致
 *    hasRoot 误判、无 root 时进不了 root 模式）。
 *
 * 用法：java -cp <tests/classes> com.mossfield.terminal.OverlaySafetyTest <FloatingWindowService.java 路径>
 * RootBridge 用源码编译（配合 tests/stub 里的 android.util.Log 桩）。
 */
public class OverlaySafetyTest {
    public static void main(String[] args) throws Exception {
        String servicePath = args.length > 0 ? args[0]
                : "app/src/com/mossfield/terminal/FloatingWindowService.java";
        String src = new String(Files.readAllBytes(Paths.get(servicePath)), StandardCharsets.UTF_8);

        // 从源码里提取 QUICK_ACTIONS 的填入文字：{"文字", "按钮名"} 形式
        List<String> actions = new ArrayList<>();
        Matcher m = Pattern.compile("\\{\"([^\"]*)\",\\s*\"[^\"]*\"\\}").matcher(src);
        while (m.find()) actions.add(m.group(1));
        System.out.println("QUICK_ACTIONS 共 " + actions.size() + " 条：" + actions);
        if (actions.isEmpty()) {
            System.out.println("FAIL: 没从源码里提取到 QUICK_ACTIONS");
            System.exit(1);
        }

        RootBridge bridge = new RootBridge(new File("/tmp"));
        int failures = 0;
        for (String action : actions) {
            try {
                bridge.checkAllowed(action);
                System.out.println("OK: " + action);
            } catch (SecurityException e) {
                System.out.println("FAIL: 悬浮窗指令被黑名单拦住：" + action + "（" + e.getMessage() + "）");
                failures++;
            }
        }

        // SU_PATHS 顺序检查
        String[] paths = RootBridge.getSuPaths();
        int ksu = indexOf(paths, "/data/adb/ksu/bin/su");
        int ksud = indexOf(paths, "/data/adb/ksud");
        System.out.println("SU_PATHS: " + String.join(", ", paths));
        if (ksu < 0) {
            System.out.println("FAIL: SU_PATHS 缺少 /data/adb/ksu/bin/su（KernelSU 真正的 su）");
            failures++;
        } else if (ksud >= 0 && ksu > ksud) {
            System.out.println("FAIL: /data/adb/ksu/bin/su 排在 /data/adb/ksud 后面（ksud 不是标准 su）");
            failures++;
        } else {
            System.out.println("OK: KernelSU su 排在 ksud 前面");
        }

        if (failures > 0) {
            System.out.println("RESULT: FAIL（" + failures + " 处）");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }
}
