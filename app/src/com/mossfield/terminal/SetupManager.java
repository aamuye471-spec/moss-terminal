package com.mossfield.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * SetupManager：首次启动设置。
 *
 * 把 APK 里的 Claude Code（二进制 + musl loader）释放到应用私有目录，
 * 写启动脚本、默认配置、root 玩法 skill；全部只写私有目录，绝不动 system。
 *
 * v1.1 改动（修闪退）：
 * - 资源改不压缩存放（构建期 zip -0），AssetManager 可直接 mmap，
 *   释放变成纯文件拷贝，不再实时解压 321MB。
 * - 释放写临时文件 + 原子改名，SHA-256 校验不过就删掉重来。
 * - 释放前检查剩余空间（<700MB 直接报明白的错）。
 * - chmod 改用 Java 文件权限 API，不再每次 spawn shell。
 * - 进度回调给界面显示，不再只有一个 Toast。
 *
 * 路径（包名固定，构建期 patchelf 已把二进制的 INTERP/RUNPATH 写死成这些绝对路径）：
 *   <files>/bin/claude
 *   <files>/lib/ld-musl-aarch64.so.1
 *   <files>/home/.claude/settings.json
 *   <files>/workspace
 */
public class SetupManager {

    private static final String TAG = "SetupManager";

    public static final String CLAUDE_VERSION = "2.1.318";
    /** 释放标记文件的版本号；升级二进制时改这个数字，触发重新释放。 */
    public static final int SETUP_VERSION = 2;

    /** 资源期望大小（字节），用于进度条和完整性检查。 */
    public static final long EXPECTED_CLAUDE_SIZE = 321810696L;
    public static final long EXPECTED_LOADER_SIZE = 723480L;
    /** Claude Code 二进制的 SHA-256（官方 npm 包提取后实测）。 */
    public static final String EXPECTED_CLAUDE_SHA256 =
            "3c9743b09f5c4b3b1d7dc0f303f8b8b7fc45f82df0c08debb52e5976e33ec423";
    /** 释放最少需要多少可用空间（321MB 二进制 + 临时文件 + 余量）。 */
    public static final long MIN_FREE_BYTES = 700L * 1024 * 1024;

    /** v1.6：DeepSeek 默认后端（官方文档 https://api.deepseek.com/anthropic）。 */
    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com/anthropic";
    /** v1.7：被彻底清除的地址——App 任何运行时路径都不许再连它。 */
    public static final String ANTHROPIC_API_HOST = "api.anthropic.com";
    /** DeepSeek 模型名（官方文档原文照抄；[1m] 是 1M 上下文版本）。 */
    public static final String DEEPSEEK_MODEL_FLASH_1M = "deepseek-flash[1m]";
    public static final String DEEPSEEK_MODEL_FLASH = "deepseek-flash";
    public static final String DEEPSEEK_EFFORT_LEVEL = "max";
    public static final int DEEPSEEK_AUTO_COMPACT_WINDOW = 786432;
    /** v1.6 一次性迁移标记（只跑一次）：清掉 v1.4 时代保存的中转地址。 */
    public static final String KEY_DEEPSEEK_MIGRATED = "deepseek_migrated_v16";

    /** 释放进度回调（后台线程调用，调用方自己切到主线程更新界面）。 */
    public interface ProgressListener {
        /** 阶段文字，例如“正在释放 Claude Code…”。 */
        void onStage(String stage);
        /** 总体进度（字节）。done/total，total 为两资源大小之和。 */
        void onProgress(long done, long total);
    }

    private final Context context;
    private final File filesDir;
    private final File binDir;
    private final File libDir;
    private final File homeDir;
    private final File workspaceDir;

    public SetupManager(Context context) {
        this.context = context;
        this.filesDir = context.getFilesDir();
        this.binDir = new File(filesDir, "bin");
        this.libDir = new File(filesDir, "lib");
        this.homeDir = new File(filesDir, "home");
        this.workspaceDir = new File(filesDir, "workspace");
    }

    public File getFilesDir() { return filesDir; }
    public File getBinDir() { return binDir; }
    public File getLibDir() { return libDir; }
    public File getHomeDir() { return homeDir; }
    public File getWorkspaceDir() { return workspaceDir; }
    public File getClaudeBinary() { return new File(binDir, "claude"); }
    public File getLoader() { return new File(libDir, "ld-musl-aarch64.so.1"); }
    /** v1.4：用户填的中转地址（ANTHROPIC_BASE_URL），存在 SharedPreferences 里。 */
    public static final String PREFS_NAME = "moss_terminal";
    public static final String KEY_BASE_URL = "anthropic_base_url";
    /** v1.4：用户填的 API Key（存在 SharedPreferences 里；值是 DeepSeek Key）。 */
    public static final String KEY_API_KEY = "anthropic_api_key";

    /**
     * 读用户保存的 API Key；没填返回 ""。
     * 注意：返回值只传给 Claude Code 进程（会话环境变量）和 settings.json 的
     * env 块，绝不写进 APK、脚本、日志或任何别的文件。
     */
    public String getSavedApiKey() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String v = prefs.getString(KEY_API_KEY, "");
            return v == null ? "" : v.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 读生效的中转地址（ANTHROPIC_BASE_URL）。
     * v1.6：没填返回 DeepSeek 默认地址，不再是 ""（不再回落到 api.anthropic.com）。
     * v1.7：经 resolveBaseUrl 兜底——任何情况下都不返回 api.anthropic.com
     * （哪怕 SharedPreferences 里还存着它，也一律按 DeepSeek 处理）。
     */
    public String getSavedBaseUrl() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return resolveBaseUrl(prefs.getString(KEY_BASE_URL, ""));
        } catch (Exception ignored) {
            return DEEPSEEK_BASE_URL;
        }
    }

    /**
     * v1.7：这个地址是不是 Anthropic 官方 API（api.anthropic.com）。
     * host 精确相等才算（忽略大小写）；api.anthropic.com.evil.com、
     * evil-api.anthropic.com 这类近似域名不算。URI 解析失败时退化为
     * 子串判断（宁可误杀，不可漏过）。
     */
    public static boolean isAnthropicApiUrl(String url) {
        if (url == null) return false;
        String v = url.trim();
        if (v.isEmpty()) return false;
        try {
            java.net.URI u = new java.net.URI(v);
            String host = u.getHost();
            if (host != null) return ANTHROPIC_API_HOST.equalsIgnoreCase(host);
        } catch (Exception ignored) {
        }
        return v.toLowerCase(java.util.Locale.ROOT).contains(ANTHROPIC_API_HOST);
    }

    /**
     * v1.7：解析生效的中转地址——全 App 唯一的 choke point。
     * 空、非法、指向 api.anthropic.com 的，一律回落到 DeepSeek 默认地址；
     * 合法的自定义中转地址原样保留（去尾部斜杠）。
     * 启动脚本、会话环境变量、网络诊断、保存入口全部走这里，
     * 保证代码层面再也不会有任何运行时路径产出 api.anthropic.com。
     */
    public static String resolveBaseUrl(String saved) {
        String v = (saved == null) ? "" : saved.trim();
        if (!isValidBaseUrl(v) || isAnthropicApiUrl(v)) return DEEPSEEK_BASE_URL;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /**
     * v1.6 一次性迁移：清掉 v1.4 时代保存的中转地址，
     * 让 DeepSeek 成为生效的默认地址（旧设置不再覆盖它）。
     * 只跑一次（靠 KEY_DEEPSEEK_MIGRATED 标记）；之后用户在菜单里
     * 新填的地址不会被清。调用方在启动时调一次即可。
     */
    public void migrateToDeepSeekDefaults() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_DEEPSEEK_MIGRATED, false)) return;
            SharedPreferences.Editor ed = prefs.edit();
            ed.remove(KEY_BASE_URL);
            ed.putBoolean(KEY_DEEPSEEK_MIGRATED, true);
            ed.apply();
            Log.i(TAG, "deepseek migration: cleared old base url, default is now DeepSeek");
        } catch (Exception ignored) {
        }
    }

    /**
     * 中转地址是否合法（写进 shell 脚本前必须过这一关）：
     * http(s) 开头、无空白、无 shell 元字符（$ ` " ' \ ; | & < > 换行），
     * 防止恶意输入拼进脚本。非法返回 false，调用方不写、不报错，只当没填。
     */
    public static boolean isValidBaseUrl(String url) {
        if (url == null) return false;
        String v = url.trim();
        if (v.isEmpty()) return false;
        if (!v.startsWith("https://") && !v.startsWith("http://")) return false;
        if (v.length() > 200) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c <= ' ' || c == '$' || c == '`' || c == '"' || c == '\''
                    || c == '\\' || c == ';' || c == '|' || c == '&'
                    || c == '<' || c == '>') {
                return false;
            }
        }
        return true;
    }

    /** 按保存的中转地址重写启动脚本（用户改地址/恢复默认后调用；已装过的机器也生效）。 */
    public void rewriteLauncherScript() throws IOException {
        writeLauncherScript();
    }

    /**
     * v1.7：这份启动脚本要不要重写（纯函数，可单测）。
     * 三种情况必须重写：
     * - 脚本不存在/读不到（null）；
     * - 脚本里出现 api.anthropic.com（v1.5 升级残留或被人手改过）；
     * - 脚本里没有 "export ANTHROPIC_BASE_URL=" 这一行（v1.5 的脚本根本
     *   没这行，Claude Code 会用内置默认连 api.anthropic.com）。
     * 合法的自定义中转地址不触发重写（它不是 api.anthropic.com）。
     */
    public static boolean scriptNeedsRewrite(String content) {
        if (content == null) return true;
        if (content.contains(ANTHROPIC_API_HOST)) return true;
        return !content.contains("export ANTHROPIC_BASE_URL=");
    }

    /**
     * v1.7：保证启动脚本干净（静态版，可单测；实例方法委托它）。
     * 读现有脚本 → 需要重写就按当前生效地址重新生成并写盘 →
     * 写完再读一遍校验（无 api.anthropic.com 且有 ANTHROPIC_BASE_URL= 行）。
     * 返回 true 表示脚本已确认干净；false 表示写盘或校验失败，
     * 调用方绝不能用这份脚本启动会话（要报错重试，而不是静默跑旧脚本）。
     */
    public static boolean ensureLauncherScript(File scriptFile, String filesDir, String baseUrl) {
        try {
            String current = null;
            if (scriptFile.exists()) {
                try {
                    byte[] data = new byte[(int) scriptFile.length()];
                    try (FileInputStream in = new FileInputStream(scriptFile)) {
                        int read = in.read(data);
                        current = new String(data, 0, read, StandardCharsets.UTF_8);
                    }
                } catch (IOException ignored) {
                    current = null; // 读不到就当不存在，重写。
                }
            }
            if (!scriptNeedsRewrite(current)) return true;
            String script = buildLauncherScript(filesDir, baseUrl);
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(scriptFile), StandardCharsets.UTF_8)) {
                w.write(script);
            }
            scriptFile.setExecutable(true, false);
            // 写完读回校验：必须干净，否则算失败。
            byte[] data = new byte[(int) scriptFile.length()];
            try (FileInputStream in = new FileInputStream(scriptFile)) {
                int read = in.read(data);
                String written = new String(data, 0, read, StandardCharsets.UTF_8);
                if (scriptNeedsRewrite(written)) {
                    Log.w(TAG, "launcher script still dirty after rewrite");
                    return false;
                }
            }
            Log.i(TAG, "launcher script ensured clean");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ensure launcher script failed", e);
            return false;
        }
    }

    /** v1.7：保证启动脚本干净（实例版，每次启动都调）。 */
    public boolean ensureLauncherScript() {
        return ensureLauncherScript(getLauncherScript(),
                filesDir.getAbsolutePath(), getSavedBaseUrl());
    }

    public File getLauncherScript() { return new File(filesDir, "run-claude.sh"); }
    public File getSetupMarker() { return new File(filesDir, ".setup_done"); }

    /** 是否已完成首次设置（且版本匹配、二进制存在）。 */
    public boolean isSetupDone() {
        File marker = getSetupMarker();
        if (!marker.exists()) return false;
        if (!getClaudeBinary().exists() || !getLoader().exists()) return false;
        if (!getLauncherScript().exists()) return false;
        try {
            String content = readFile(marker).trim();
            return content.equals("v" + SETUP_VERSION);
        } catch (IOException e) {
            return false;
        }
    }

    /** 执行首次设置（无进度回调）。抛 IOException 说明失败，调用方应提示用户并重试。 */
    public void runSetup() throws IOException {
        runSetup(null);
    }

    /** 执行首次设置（带进度回调）。 */
    public void runSetup(ProgressListener listener) throws IOException {
        checkFreeSpace();
        mkdirs(binDir, libDir, homeDir, workspaceDir,
                new File(filesDir, "tmp"), new File(filesDir, "logs"));
        mkdirs(new File(homeDir, ".claude"),
                new File(homeDir, ".claude/skills"),
                new File(homeDir, ".claude/projects"));

        long total = EXPECTED_CLAUDE_SIZE + EXPECTED_LOADER_SIZE;

        // 清掉上次闪退/失败留下的临时文件，避免残留占空间。
        deleteQuietly(new File(getClaudeBinary().getAbsolutePath() + ".tmp"));
        deleteQuietly(new File(getLoader().getAbsolutePath() + ".tmp"));

        // 1. 释放二进制与 loader（写临时文件，成功后原子改名）
        if (listener != null) listener.onStage("正在释放 Claude Code…");
        extractAsset("claude", getClaudeBinary(), true,
                listener, 0, total, EXPECTED_CLAUDE_SIZE);
        if (listener != null) listener.onStage("正在释放运行库…");
        extractAsset("ld-musl-aarch64.so.1", getLoader(), true,
                listener, EXPECTED_CLAUDE_SIZE, total, EXPECTED_LOADER_SIZE);

        // 2. libc 软链（musl 的 loader 本身就是 libc，二者是同一文件）
        File libcLink = new File(libDir, "libc.musl-aarch64.so.1");
        if (!libcLink.exists()) {
            try {
                new ProcessBuilder("/system/bin/sh", "-c",
                        "ln -sf ld-musl-aarch64.so.1 " + libcLink.getAbsolutePath()).start().waitFor();
            } catch (Exception ignored) {
            }
            if (!libcLink.exists()) {
                copyFile(getLoader(), libcLink);
                makeExecutable(libcLink);
            }
        }

        // 3. 启动脚本
        if (listener != null) listener.onStage("正在写配置…");
        writeLauncherScript();

        // 4. 默认配置（含 system 分区 deny 规则）
        writeDefaultSettings();

        // 5. root 玩法 skill
        writeSkills();

        // 6. 校验 + 标记（SHA-256 不对说明释放坏了，删掉二进制让下次重试）
        if (listener != null) listener.onStage("正在校验文件…");
        String sha = sha256(getClaudeBinary());
        Log.i(TAG, "claude sha256=" + sha);
        if (!EXPECTED_CLAUDE_SHA256.equals(sha)) {
            deleteQuietly(getClaudeBinary());
            throw new IOException("文件校验失败（可能解压不完整），请点重试");
        }
        writeFile(getSetupMarker(), "v" + SETUP_VERSION + "\n" + sha + "\n");
        if (listener != null) listener.onProgress(total, total);
    }

    /** 释放前检查剩余空间，不够就直接报明白的错，不走到一半才失败。 */
    private void checkFreeSpace() throws IOException {
        long free = new StatFs(filesDir.getAbsolutePath()).getAvailableBytes();
        if (free < MIN_FREE_BYTES) {
            throw new IOException("空间不足：释放需要至少 700 MB 可用空间，当前可用 "
                    + (free / 1024 / 1024) + " MB，请清理后重试");
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Exception ignored) {
        }
    }

    /** 启动时自检：system 是否还是 ro、文件是否被改过。 */
    public String selfCheck() {
        StringBuilder report = new StringBuilder();
        try {
            Process mount = new ProcessBuilder("/system/bin/sh", "-c", "mount | grep ' /system '").start();
            String out = readAll(mount);
            mount.waitFor();
            if (out.contains("rw,")) {
                report.append("警告：/system 处于 rw（可写）状态，说明别的工具动过 system，已拒绝 root 模式。\n");
            } else {
                report.append("/system: ro（正常）\n");
            }
        } catch (Exception e) {
            report.append("/system 检查失败：").append(e.getMessage()).append("\n");
        }
        return report.toString();
    }

    /**
     * 生成启动脚本内容（纯函数，不碰 Context，可单测）。
     * DeepSeek 环境变量按官方文档照抄；baseUrl 是生效的中转地址
     * （默认 DeepSeek；用户在菜单里改了就用用户填的）。
     * 模型名里的 [1m] 在 shell 赋值里是字面值（赋值不做 glob 展开），
     * 这里再加一层双引号，双保险。
     * 注意：API Key 绝不写进脚本——Key 只走 settings.json env 块和会话环境变量。
     */
    public static String buildLauncherScript(String files, String baseUrl) {
        // v1.7：经 resolveBaseUrl 兜底——baseUrl 就算传进来的是
        // api.anthropic.com，也会被换成 DeepSeek。
        String v = resolveBaseUrl(baseUrl);
        String script = "#!/system/bin/sh\n"
                + "# Moss Terminal 启动脚本：给 Claude Code 准备环境，然后 exec 它。\n"
                + "# 由 SetupManager 生成；不要手改（每次启动都会重写）。\n"
                + "# v1.7：默认后端是 DeepSeek；本脚本只连 DeepSeek，不连 Anthropic 官方地址。\n"
                + "export HOME=" + files + "/home\n"
                + "export PATH=" + files + "/bin:/system/bin:/system/xbin\n"
                + "export TERM=xterm-256color\n"
                + "export LANG=C.UTF-8\n"
                + "export LC_ALL=C.UTF-8\n"
                + "export SHELL=/system/bin/sh\n"
                + "export TMPDIR=" + files + "/tmp\n"
                + "export CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY=4\n"
                + "export ANTHROPIC_BASE_URL=" + v + "\n"
                + "export ANTHROPIC_MODEL=\"" + DEEPSEEK_MODEL_FLASH_1M + "\"\n"
                + "export ANTHROPIC_DEFAULT_OPUS_MODEL=\"" + DEEPSEEK_MODEL_FLASH_1M + "\"\n"
                + "export ANTHROPIC_DEFAULT_SONNET_MODEL=\"" + DEEPSEEK_MODEL_FLASH_1M + "\"\n"
                + "export ANTHROPIC_DEFAULT_HAIKU_MODEL=\"" + DEEPSEEK_MODEL_FLASH + "\"\n"
                + "export CLAUDE_CODE_SUBAGENT_MODEL=\"" + DEEPSEEK_MODEL_FLASH + "\"\n"
                + "export CLAUDE_CODE_EFFORT_LEVEL=\"" + DEEPSEEK_EFFORT_LEVEL + "\"\n"
                + "export CLAUDE_CODE_AUTO_COMPACT_WINDOW=\"" + DEEPSEEK_AUTO_COMPACT_WINDOW + "\"\n"
                + "cd " + files + "/workspace || exit 1\n"
                + "exec " + files + "/bin/claude \"$@\"\n";
        return script;
    }

    private void writeLauncherScript() throws IOException {
        String files = filesDir.getAbsolutePath();
        String script = buildLauncherScript(files, getSavedBaseUrl());
        File scriptFile = getLauncherScript();
        writeFile(scriptFile, script);
        makeExecutable(scriptFile);
    }

    /**
     * 把 ANTHROPIC_AUTH_TOKEN 写进 settings.json 文本的 env 块（纯函数，可单测）。
     * 用 org.json（Android 运行时是真实现；JVM 单测用 tests/json-20240303.jar，
     * 因为 android.jar 里的 org.json 是 Stub，跑不起来）。
     * 规则：
     * - key 为空：删掉 ANTHROPIC_AUTH_TOKEN；同时删掉旧的 ANTHROPIC_API_KEY
     *   （v1.5 之前写错的名字，迁移掉，不让它留着）。
     * - key 非空：先删旧的 ANTHROPIC_API_KEY；再写 ANTHROPIC_AUTH_TOKEN。
     * - v1.7：env 块里如果有指向 api.anthropic.com 的 ANTHROPIC_BASE_URL
     *   也一并删掉（Claude Code 会读 settings.json 的 env 块，别让它从
     *   这里把 Anthropic 地址带回来；指向 DeepSeek 或别的合法中转的不动）。
     * - 绝不产生重复的 "env" 键（v1.4 的 bug：清掉 key 后再存会插第二个 env 块）。
     * - 已经是要的样子时返回原文（不改格式、不动文件）。
     * - 原文本解析失败时抛 IOException（调用方记日志，不闪退）。
     */
    public static String applyAuthTokenToSettingsJson(String json, String key) throws IOException {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONObject env = root.optJSONObject("env");
            boolean hasOld = env != null && env.has("ANTHROPIC_API_KEY");
            String current = (env == null) ? null : env.optString("ANTHROPIC_AUTH_TOKEN", null);
            boolean wantEmpty = (key == null || key.isEmpty());
            // v1.7：env 里指向 api.anthropic.com 的 ANTHROPIC_BASE_URL 是脏数据，
            // 有它在就不算“已经是要的样子”，必须走下面的清理。
            boolean hasHostileBaseUrl = env != null && env.has("ANTHROPIC_BASE_URL")
                    && isAnthropicApiUrl(env.optString("ANTHROPIC_BASE_URL", ""));
            // 已经是要的样子：直接返回原文，不改格式、不动文件。
            if (!hasOld && !hasHostileBaseUrl
                    && (wantEmpty ? (current == null) : key.equals(current))) {
                return json;
            }
            if (env != null) env.remove("ANTHROPIC_API_KEY"); // 迁移旧名
            if (hasHostileBaseUrl) env.remove("ANTHROPIC_BASE_URL"); // v1.7：清 Anthropic 地址
            if (wantEmpty) {
                if (env != null) env.remove("ANTHROPIC_AUTH_TOKEN");
            } else {
                if (env == null) {
                    env = new org.json.JSONObject();
                    root.put("env", env);
                }
                env.put("ANTHROPIC_AUTH_TOKEN", key);
            }
            return root.toString(2);
        } catch (org.json.JSONException e) {
            throw new IOException("settings.json 解析失败：" + e.getMessage(), e);
        }
    }

    private void writeDefaultSettings() throws IOException {
        File settings = new File(homeDir, ".claude/settings.json");
        if (settings.exists()) return; // 用户已改过就不覆盖
        // deny 恒胜 allow；system 分区相关路径一律拒绝（L3 兜底）。
        String json = "{\n"
                + "  \"permissions\": {\n"
                + "    \"deny\": [\n"
                + "      \"Bash(rm -rf /system*)\",\n"
                + "      \"Bash(rm -rf /vendor*)\",\n"
                + "      \"Bash(rm -rf /product*)\",\n"
                + "      \"Bash(mount *remount*)\",\n"
                + "      \"Bash(* /system/*)\",\n"
                + "      \"Bash(* /vendor/*)\",\n"
                + "      \"Write(/system/**)\",\n"
                + "      \"Write(/vendor/**)\",\n"
                + "      \"Write(/product/**)\",\n"
                + "      \"Edit(/system/**)\",\n"
                + "      \"Edit(/vendor/**)\",\n"
                + "      \"Edit(/product/**)\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"hooks\": {},\n"
                + "  \"cleanupPeriodDays\": 30\n"
                + "}\n";
        writeFile(settings, json);
        makePrivate(settings);
    }

    private void writeSkills() throws IOException {
        writeSkill("phone-doctor", "手机体检",
                "生成整机健康报告。\n\n"
                + "做法：读 dumpsys battery / dumpsys meminfo / cat /proc/meminfo / dumpsys netstats / getprop，"
                + "汇总电量、温度、内存、存储、后台耗电排行、权限索取排行。\n\n"
                + "只读，不写任何东西。输出中文报告。\n");
        writeSkill("phone-ops", "自然语言手机操作",
                "按用户的话操作手机。\n\n"
                + "可用命令（全部走 su，每条执行前先把完整命令回显给用户确认）：\n"
                + "- 冻结/解冻 app：pm disable-user --user 0 <包名> / pm enable <包名>。\n"
                + "  绝不冻结 launcher、systemui、telephony 等关键组件（命中直接拒绝）。\n"
                + "- 亮度：settings put system screen_brightness <0-255>。\n"
                + "- 勿扰：cmd notification set_dnd on/off。\n"
                + "- 截图：screencap -p /sdcard/Android/data/com.mossfield.terminal/files/shot.png。\n"
                + "- 点按/滑动：input tap x y / input swipe x1 y1 x2 y2 / input keyevent <键>。\n\n"
                + "红线：绝不碰 /system、/vendor、/product。冻结/卸载类操作必须二次确认。\n");
        writeSkill("phone-backup", "备份管家",
                "备份用户自己的 app 数据。\n\n"
                + "做法：root 下 tar -czf /sdcard/Android/data/com.mossfield.terminal/files/backup/<包>-<日期>.tar.gz /data/data/<包>。\n"
                + "恢复时解回。只备用户点名的包。备份含账号 token，只存本机，不上传。\n\n"
                + "红线：绝不碰 /system、/vendor、/product。\n");
        writeSkill("phone-net", "流量与防火墙",
                "看流量、按 app 断网。\n\n"
                + "看流量：dumpsys netstats。\n"
                + "断网某 app：iptables -A OUTPUT -m owner --uid-owner <uid> -j DROP；放行则 -D 删除该规则。\n"
                + "规则先 iptables -C 查重；提供一键清空。只动 OUTPUT 链，不动系统自带规则。\n\n"
                + "红线：绝不碰 /system、/vendor、/product。\n");
        writeSkill("phone-audit", "权限审计",
                "找出过度索权的 app。\n\n"
                + "做法：cmd appops / dumpsys appops / dumpsys permission 拉清单，出中文报告。\n"
                + "只建议、不自动执行；收回权限（pm revoke <包> <权限>）需用户逐条确认。\n\n"
                + "红线：绝不碰 /system、/vendor、/product。\n");
    }

    private void writeSkill(String name, String title, String body) throws IOException {
        File dir = new File(homeDir, ".claude/skills/" + name);
        mkdirs(dir);
        String md = "# " + title + "\n\n" + body;
        writeFile(new File(dir, "SKILL.md"), md);
    }

    /**
     * 释放一个资源文件：写临时文件 → 校验大小 → 原子改名。
     * 进度每 2MB 上报一次，避免频繁切主线程。
     *
     * @param progressBase 已完成的前序文件字节数（总体进度用）。
     * @param progressTotal 两资源大小之和（总体进度用）。
     * @param expectedSize 该资源期望大小，写完校验用。
     */
    private void extractAsset(String assetName, File dest, boolean executable,
                              ProgressListener listener, long progressBase,
                              long progressTotal, long expectedSize) throws IOException {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        deleteQuietly(tmp);
        AssetManager am = context.getAssets();
        long copied = 0;
        try (InputStream in = am.open(assetName);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1024 * 256];
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                copied += n;
                if (listener != null && copied - lastReport >= 2 * 1024 * 1024) {
                    listener.onProgress(progressBase + copied, progressTotal);
                    lastReport = copied;
                }
            }
            out.flush();
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new IOException("释放 " + assetName + " 失败：" + e.getMessage(), e);
        }
        if (tmp.length() != expectedSize) {
            deleteQuietly(tmp);
            throw new IOException("释放 " + assetName + " 不完整（写出 " + tmp.length()
                    + " 字节，期望 " + expectedSize + " 字节），请点重试");
        }
        if (executable) makeExecutable(tmp);
        if (dest.exists() && !dest.delete()) {
            deleteQuietly(tmp);
            throw new IOException("无法覆盖旧文件，请清除应用数据后重试");
        }
        if (!tmp.renameTo(dest)) {
            // 改名失败（极少数情况）→ 直接拷贝兜底
            try {
                copyFile(tmp, dest);
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw new IOException("释放 " + assetName + " 失败：" + e.getMessage(), e);
            }
            deleteQuietly(tmp);
            if (executable) makeExecutable(dest);
        }
        Log.i(TAG, "extracted " + assetName + " -> " + dest.getAbsolutePath());
    }

    /** 0755：优先用 Java 文件权限 API，不 spawn shell；不行再用 shell 兜底。 */
    private void makeExecutable(File f) throws IOException {
        f.setExecutable(true, false);
        f.setReadable(true, false);
        if (!f.canExecute()) {
            try {
                Process p = new ProcessBuilder("/system/bin/sh", "-c",
                        "chmod 755 " + f.getAbsolutePath()).start();
                p.waitFor();
            } catch (Exception e) {
                throw new IOException("chmod failed: " + f, e);
            }
            if (!f.canExecute()) throw new IOException("chmod failed: " + f);
        }
    }

    /** 0600：仅所有者可读写（文件本来就在应用私有目录里，这里是双保险）。 */
    private void makePrivate(File f) {
        try {
            f.setReadable(true, true);
            f.setWritable(true, true);
            f.setExecutable(false, false);
        } catch (Exception ignored) {
        }
    }

    private void copyFile(File src, File dest) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1024 * 256];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private void mkdirs(File... dirs) {
        for (File d : dirs) {
            if (!d.exists()) d.mkdirs();
        }
    }

    private void writeFile(File f, String content) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    private String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = in.read(data);
            return new String(data, 0, read, StandardCharsets.UTF_8);
        }
    }

    private String readAll(Process proc) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = proc.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 文件的 SHA-256（小写 hex）。 */
    public static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[1024 * 256];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("sha256 failed", e);
        }
    }
}
