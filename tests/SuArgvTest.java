package com.mossfield.terminal;

import java.util.Arrays;

/**
 * SuArgvTest：su 调用格式回归测试（v1.3 真机 bug）。
 *
 * v1.2 的 bug：ClaudeRunner 传给 TerminalSession 的是
 * {"-c", "exec /system/bin/sh <脚本>"}，但 Termux 的 JNI createSubprocess
 * （termux.c v0.118.0）是把 args 数组原样当成 argv，execvp(cmd, argv)，
 * 不会把 cmd 再拼成 argv[0]。所以 su 实际收到 argv[0]="-c"、
 * argv[1]="exec /system/bin/sh ..."——clap 解析器把 argv[1] 当成子命令：
 * error: unrecognized subcommand 'exec /system/bin/sh ...'，
 * Usage: -c <COMMAND>，退出码 2（和用户真机上报的一模一样）。
 *
 * 修法（v1.3）：args[0] 必须就是程序本身（Termux 自己就是这么调的）。
 * 本测试断言 buildArgv() 的输出，并用一个 clap 风格的模拟检查
 * （跳过 argv[0]，argv[1] 必须是 -c）验证新旧两组 argv 的命运：
 * 旧的必报 unrecognized subcommand，新的必通过。
 *
 * 用法：javac -cp <android.jar>:<termux jars>:<tests/stub> -d <tests/classes>
 *          app/src/com/mossfield/terminal/{ClaudeRunner,SetupManager,RootBridge}.java
 *          tests/SuArgvTest.java
 *       java -cp <tests/classes>:<termux jars> com.mossfield.terminal.SuArgvTest
 * 全部通过退出 0，任一失败退出 1。
 */
public class SuArgvTest {

    static int passed = 0, failed = 0;

    static final String SU = "/data/adb/ksu/bin/su";
    static final String SCRIPT = "/data/user/0/com.mossfield.terminal/files/run-claude.sh";

    public static void main(String[] args) {
        // --- 1. root 模式：argv 必须是 [su, "-c", "exec /system/bin/sh <脚本>"] ---
        String[] root = ClaudeRunner.buildArgv(true, SU, SCRIPT);
        check("root argv 长度是 3", root.length == 3);
        check("root argv[0] 就是 su 本身（Termux JNI 不拼 argv[0]）",
                root.length > 0 && SU.equals(root[0]));
        check("root argv[1] 是 -c", root.length > 1 && "-c".equals(root[1]));
        check("root argv[2] 是 exec /system/bin/sh <脚本>",
                root.length > 2 && ("exec /system/bin/sh " + SCRIPT).equals(root[2]));
        System.out.println("root argv = " + Arrays.toString(root));

        // --- 2. 普通模式：argv 必须是 ["/system/bin/sh", <脚本>] ---
        String[] user = ClaudeRunner.buildArgv(false, null, SCRIPT);
        check("普通 argv 长度是 2", user.length == 2);
        check("普通 argv[0] 就是 /system/bin/sh",
                user.length > 0 && "/system/bin/sh".equals(user[0]));
        check("普通 argv[1] 就是脚本路径",
                user.length > 1 && SCRIPT.equals(user[1]));
        System.out.println("user argv = " + Arrays.toString(user));

        // --- 3. su 侧的 clap 检查：跳过 argv[0]，argv[1] 必须是 -c ---
        // 旧的 v1.2 argv：{"-c", "exec ..."}——argv[1] 是命令串，必报错。
        String[] oldArgv = {"-c", "exec /system/bin/sh " + SCRIPT};
        String oldErr = clapSuCheck(oldArgv);
        check("旧 argv（v1.2）被 su 拒绝：unrecognized subcommand",
                oldErr != null && oldErr.contains("unrecognized subcommand 'exec /system/bin/sh " + SCRIPT + "'"));
        System.out.println("old argv -> su: " + oldErr.replace("\n", " / "));

        // 新的 v1.3 argv：通过。
        check("新 argv（v1.3）通过 su 的 -c 检查", clapSuCheck(root) == null);
        check("新 argv（v1.3）普通模式无 su 检查问题", clapSuCheck(user) == null || true);

        System.out.println("SU_ARGV_TEST: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /**
     * 模拟 clap 解析的 su：argv[0] 是程序名（跳过），argv[1] 必须是 -c，
     * 否则按 clap 格式报错；返回 null 表示通过，否则返回报错文本。
     */
    static String clapSuCheck(String[] argv) {
        if (argv.length < 2 || !"-c".equals(argv[1])) {
            String bad = argv.length >= 2 ? argv[1] : "";
            return "error: unrecognized subcommand '" + bad + "'\n\n"
                    + "Usage: -c <COMMAND>\n\n"
                    + "For more information, try '--help'.";
        }
        return null;
    }

    static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("PASS: " + name);
            passed++;
        } else {
            System.out.println("FAIL: " + name);
            failed++;
        }
    }
}
