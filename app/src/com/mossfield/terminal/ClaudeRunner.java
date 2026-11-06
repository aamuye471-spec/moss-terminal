package com.mossfield.terminal;

import android.util.Log;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;

/**
 * ClaudeRunner：拉起 Claude Code 会话。
 *
 * 两种模式：
 * - root 模式（默认，有 root 时）：整个会话以 root 身份跑（su -c），
 *   Claude Code 的 Bash/Read/Write/Edit 全部是 root 权限。
 *   权限模式锁死 default（不拼 --dangerously-skip-permissions，root 下会被门禁退出）。
 * - 普通模式（无 root 时）：app UID 直接跑；Android 10+ W^X 可能拦住
 *   getFilesDir() 里的二进制执行，此时会明确报错，提示用户开 root。
 *
 * v1.3 教训（真机 bug）：Termux 的 JNI createSubprocess
 * （terminal-emulator 0.118.0，termux.c）是把 Java 的 args 数组
 * 原样当成 argv，execvp(cmd, argv)——它不会把 cmd 再拼成 argv[0]。
 * v1.2 传的是 {"-c", "exec /system/bin/sh <脚本>"}，su 实际收到的
 * argv[0]="-c"、argv[1]="exec /system/bin/sh ..."，
 * clap 解析器把 argv[1] 当成子命令：error: unrecognized subcommand ...，
 * Usage: -c <COMMAND>，退出码 2。修法：args[0] 必须就是程序本身
 * （Termux 自己就是这么调的）。见 buildArgv()。
 */
public class ClaudeRunner {

    private static final String TAG = "ClaudeRunner";

    /** 会话回滚行数。 */
    private static final int TRANSCRIPT_ROWS = 10000;

    private final SetupManager setup;
    private final RootBridge rootBridge;

    public ClaudeRunner(SetupManager setup, RootBridge rootBridge) {
        this.setup = setup;
        this.rootBridge = rootBridge;
    }

    /** 创建一个新的 Claude Code 终端会话。 */
    public TerminalSession createSession(TerminalSessionClient client, boolean rootMode) {
        File script = setup.getLauncherScript();
        String cwd = setup.getWorkspaceDir().getAbsolutePath();
        String[] env = buildEnv();
        String shellPath;
        String[] args;

        if (rootMode) {
            String su = rootBridge.findSu();
            if (su == null) {
                throw new IllegalStateException("root 模式要求 su，但未找到 su");
            }
            // su -c "exec /system/bin/sh <脚本>"：整个会话以 root 身份跑。
            shellPath = su;
            args = buildArgv(true, su, script.getAbsolutePath());
            Log.i(TAG, "starting claude as root via " + su);
        } else {
            shellPath = "/system/bin/sh";
            args = buildArgv(false, null, script.getAbsolutePath());
            Log.i(TAG, "starting claude as app user");
        }

        return new TerminalSession(shellPath, cwd, args, env, TRANSCRIPT_ROWS, client);
    }

    /**
     * 组装传给 TerminalSession 的参数数组（即 execvp 的 argv）。
     *
     * Termux 的 JNI createSubprocess（termux.c v0.118.0）：
     * argv[i] = args[i]（原样照搬），然后 execvp(cmd, argv)。
     * 它不会把 cmd 再拼成 argv[0]。所以 args[0] 必须就是程序本身，
     * 否则 argv[0] 会变成 "-c"，su 收到的第一个实参就是
     * "exec /system/bin/sh ..."——clap 把它当成子命令报错
     * （v1.2 真机就是这么崩的）。Termux 自己也是这么调的。
     *
     * -c 的值是单个字符串，su 收到后由它的 shell 解析；脚本路径是
     * App 自己生成的固定路径，不含空格和特殊字符，不需要再加引号。
     *
     * @param rootMode root 模式还是普通模式
     * @param suPath   root 模式时 su 的绝对路径（普通模式传 null）
     * @param scriptPath 启动脚本的绝对路径
     * @return 完整的 argv 数组（argv[0] 即程序本身）
     */
    static String[] buildArgv(boolean rootMode, String suPath, String scriptPath) {
        if (rootMode) {
            return new String[]{suPath, "-c", "exec /system/bin/sh " + scriptPath};
        }
        return new String[]{"/system/bin/sh", scriptPath};
    }

    /**
     * 启动前校验：二进制、加载器、脚本、终端 JNI 库、root（root 模式时）。
     * 返回 null 表示通过，否则返回人话错误（调用方显示，不闪退）。
     */
    public String preflight(boolean rootMode) {
        File bin = setup.getClaudeBinary();
        if (!bin.exists()) {
            return "Claude Code 二进制不存在，请清除应用数据后重新打开（会重新释放）。";
        }
        if (!bin.canExecute()) {
            return "Claude Code 二进制不可执行（权限被改了），请清除应用数据后重新打开。";
        }
        File loader = setup.getLoader();
        if (!loader.exists()) {
            return "运行库不存在，请清除应用数据后重新打开。";
        }
        File script = setup.getLauncherScript();
        if (!script.exists()) {
            return "启动脚本不存在，请清除应用数据后重新打开。";
        }
        // 终端 JNI 库：提前加载一次，缺失就报明白的错而不是闪退。
        try {
            Class.forName("com.termux.terminal.JNI");
        } catch (Throwable t) {
            return "终端组件加载失败（" + t + "），请重装应用。";
        }
        if (rootMode) {
            String su = rootBridge.findSu();
            if (su == null) {
                return "root 模式要求 su，但未找到 su。请先获取 root，或在菜单里关掉 Root 模式。";
            }
        }
        return null;
    }

    /**
     * 组装会话环境变量（纯函数版，可单测）。
     * DeepSeek 环境变量按官方文档照抄；baseUrl 是生效的中转地址
     * （默认 DeepSeek）；apiKey 非空时以 ANTHROPIC_AUTH_TOKEN 传给进程
     * （只走内存，不写任何文件；和 settings.json env 块双保险，
     * 两边是同一个值，不会打架）。
     * 模型名里的 [1m] 在 env 数组里是字面值（不经过 shell，不用引号）。
     */
    static String[] buildEnv(String files, String baseUrl, String apiKey) {
        java.util.ArrayList<String> env = new java.util.ArrayList<>();
        env.add("HOME=" + files + "/home");
        env.add("PATH=" + files + "/bin:/system/bin:/system/xbin");
        env.add("TERM=xterm-256color");
        env.add("LANG=C.UTF-8");
        env.add("LC_ALL=C.UTF-8");
        env.add("SHELL=/system/bin/sh");
        env.add("TMPDIR=" + files + "/tmp");
        env.add("CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY=4");
        // v1.7：经 resolveBaseUrl 兜底——就算传进来的是 api.anthropic.com，
        // 也会被换成 DeepSeek；会话环境变量这一路再也带不回 Anthropic 地址。
        String v = SetupManager.resolveBaseUrl(baseUrl);
        env.add("ANTHROPIC_BASE_URL=" + v);
        env.add("ANTHROPIC_MODEL=" + SetupManager.DEEPSEEK_MODEL_FLASH_1M);
        env.add("ANTHROPIC_DEFAULT_OPUS_MODEL=" + SetupManager.DEEPSEEK_MODEL_FLASH_1M);
        env.add("ANTHROPIC_DEFAULT_SONNET_MODEL=" + SetupManager.DEEPSEEK_MODEL_FLASH_1M);
        env.add("ANTHROPIC_DEFAULT_HAIKU_MODEL=" + SetupManager.DEEPSEEK_MODEL_FLASH);
        env.add("CLAUDE_CODE_SUBAGENT_MODEL=" + SetupManager.DEEPSEEK_MODEL_FLASH);
        env.add("CLAUDE_CODE_EFFORT_LEVEL=" + SetupManager.DEEPSEEK_EFFORT_LEVEL);
        env.add("CLAUDE_CODE_AUTO_COMPACT_WINDOW=" + SetupManager.DEEPSEEK_AUTO_COMPACT_WINDOW);
        if (apiKey != null && !apiKey.isEmpty()) {
            env.add("ANTHROPIC_AUTH_TOKEN=" + apiKey);
        }
        return env.toArray(new String[0]);
    }

    private String[] buildEnv() {
        String files = setup.getFilesDir().getAbsolutePath();
        return buildEnv(files, setup.getSavedBaseUrl(), setup.getSavedApiKey());
    }
}
