package com.mossfield.terminal;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * AnthropicPurgeTest：v1.7 清除 api.anthropic.com 回归测试。
 *
 * 背景（真机上报）：v1.5 的启动脚本里没有 ANTHROPIC_BASE_URL 这一行，
 * Claude Code 用内置默认连 api.anthropic.com，报 EAI_AGAIN + 启动失败。
 * v1.6 虽然改了默认后端，但升级时旧脚本没被重写（SETUP_VERSION 没变，
 * 重写失败还被静默吞掉），旧脚本照样能活下来。
 * v1.7 的保证：每次启动都强制校验启动脚本内容，脏脚本一律重写；
 * getSavedBaseUrl / buildLauncherScript / buildEnv / 网络诊断全部走
 * resolveBaseUrl choke point；settings.json env 块里的 Anthropic 地址
 * 也一并清掉。
 *
 * 本测试模拟 v1.5 旧脚本残留在磁盘上，再调真实的
 * SetupManager.ensureLauncherScript()，验证脚本被强制重写干净。
 *
 * 注意：Key 只用明显是假的占位值（TEST-KEY-PLACEHOLDER），绝不用真 Key。
 *
 * 用法：javac -encoding UTF-8 -cp <android.jar>:<termux jars>:<tests/stub>:<tests/json-20240303.jar> -d <tests/classes>
 *          app/src/com/mossfield/terminal/{ClaudeRunner,SetupManager,RootBridge}.java
 *          tests/AnthropicPurgeTest.java
 *       java -cp <tests/classes>:<termux jars>:<tests/json-20240303.jar> com.mossfield.terminal.AnthropicPurgeTest
 * 全部通过退出 0，任一失败退出 1。
 * （运行期不要带 android.jar：它里面的 org.json 是 Stub，会抛 Stub!；
 *   真实现用 tests/json-20240303.jar，只供单测，不进 APK。）
 */
public class AnthropicPurgeTest {

    static int passed = 0, failed = 0;

    static final String FILES = "/data/user/0/com.mossfield.terminal/files";
    static final String DEEPSEEK = "https://api.deepseek.com/anthropic";
    static final String ANTHROPIC = "https://api.anthropic.com";
    static final String CUSTOM = "https://relay.example.com/anthropic";
    static final String FAKE_KEY = "TEST-KEY-PLACEHOLDER-1234";

    /** v1.5 的启动脚本（真实结构：根本没有 ANTHROPIC_BASE_URL 这一行）。 */
    static final String V15_SCRIPT_NO_BASE_URL = "#!/system/bin/sh\n"
            + "# Moss Terminal 启动脚本\n"
            + "export HOME=" + FILES + "/home\n"
            + "export PATH=" + FILES + "/bin:/system/bin:/system/xbin\n"
            + "export TERM=xterm-256color\n"
            + "cd " + FILES + "/workspace || exit 1\n"
            + "exec " + FILES + "/bin/claude \"$@\"\n";

    /** 旧脚本里有人手写/旧版本写进去的 Anthropic 地址。 */
    static final String OLD_SCRIPT_ANTHROPIC_URL = "#!/system/bin/sh\n"
            + "export HOME=" + FILES + "/home\n"
            + "export ANTHROPIC_BASE_URL=" + ANTHROPIC + "\n"
            + "export ANTHROPIC_MODEL=\"deepseek-flash[1m]\"\n"
            + "cd " + FILES + "/workspace || exit 1\n"
            + "exec " + FILES + "/bin/claude \"$@\"\n";

    /** 已经干净的 DeepSeek 脚本（v1.6 生成的）。 */
    static final String CLEAN_DEEPSEEK_SCRIPT =
            SetupManager.buildLauncherScript(FILES, DEEPSEEK);

    /** 合法自定义中转的脚本（不是 api.anthropic.com，不该被强制改掉）。 */
    static final String CLEAN_CUSTOM_SCRIPT =
            SetupManager.buildLauncherScript(FILES, CUSTOM);

    public static void main(String[] args) throws Exception {
        testIsAnthropicApiUrl();
        testResolveBaseUrl();
        testScriptNeedsRewrite();
        testEnsureRewritesV15Script();
        testEnsureRewritesAnthropicUrlScript();
        testEnsureKeepsCleanScript();
        testEnsureKeepsCustomRelayScript();
        testEnsureCreatesMissingScript();
        testBuildLauncherScriptWithAnthropicInput();
        testBuildEnvWithAnthropicInput();
        testSettingsJsonScrub();
        System.out.println("ANTHROPIC_PURGE_TEST: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    static void check(boolean cond, String name) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    /** 新脚本里 8 个 DeepSeek 变量全在、api.anthropic.com 零出现、exec 行正常。 */
    static void assertCleanDeepSeekScript(String script, String name) {
        check(script.contains("export ANTHROPIC_BASE_URL=" + DEEPSEEK), name + " base url");
        check(script.contains("export ANTHROPIC_MODEL=\"deepseek-flash[1m]\""), name + " model");
        check(script.contains("export ANTHROPIC_DEFAULT_OPUS_MODEL=\"deepseek-flash[1m]\""), name + " opus");
        check(script.contains("export ANTHROPIC_DEFAULT_SONNET_MODEL=\"deepseek-flash[1m]\""), name + " sonnet");
        check(script.contains("export ANTHROPIC_DEFAULT_HAIKU_MODEL=\"deepseek-flash\""), name + " haiku");
        check(script.contains("export CLAUDE_CODE_SUBAGENT_MODEL=\"deepseek-flash\""), name + " subagent");
        check(script.contains("export CLAUDE_CODE_EFFORT_LEVEL=\"max\""), name + " effort");
        check(script.contains("export CLAUDE_CODE_AUTO_COMPACT_WINDOW=\"786432\""), name + " compact window");
        check(!script.contains("api.anthropic.com"), name + " no api.anthropic.com");
        check(!script.contains("ANTHROPIC_API_KEY"), name + " no ANTHROPIC_API_KEY");
        check(script.contains("exec " + FILES + "/bin/claude \"$@\""), name + " exec line");
    }

    static String readFile(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void writeFile(File f, String content) throws Exception {
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- isAnthropicApiUrl ----------

    static void testIsAnthropicApiUrl() {
        check(SetupManager.isAnthropicApiUrl("https://api.anthropic.com"), "exact https");
        check(SetupManager.isAnthropicApiUrl("http://api.anthropic.com/v1"), "http + path");
        check(SetupManager.isAnthropicApiUrl("https://API.ANTHROPIC.COM/anthropic"), "case-insensitive");
        check(SetupManager.isAnthropicApiUrl("https://api.anthropic.com:443/x"), "with port");
        check(!SetupManager.isAnthropicApiUrl("https://api.deepseek.com/anthropic"), "deepseek is not anthropic");
        check(!SetupManager.isAnthropicApiUrl("https://api.anthropic.com.evil.com/"), "suffix domain not anthropic");
        check(!SetupManager.isAnthropicApiUrl("https://evil-api.anthropic.com/"), "prefix subdomain not anthropic");
        check(!SetupManager.isAnthropicApiUrl("https://relay.example.com/anthropic"), "custom relay not anthropic");
        check(!SetupManager.isAnthropicApiUrl(null), "null not anthropic");
        check(!SetupManager.isAnthropicApiUrl(""), "empty not anthropic");
        check(!SetupManager.isAnthropicApiUrl("   "), "blank not anthropic");
    }

    // ---------- resolveBaseUrl ----------

    static void testResolveBaseUrl() {
        check(SetupManager.resolveBaseUrl(null).equals(DEEPSEEK), "null -> deepseek");
        check(SetupManager.resolveBaseUrl("").equals(DEEPSEEK), "empty -> deepseek");
        check(SetupManager.resolveBaseUrl("   ").equals(DEEPSEEK), "blank -> deepseek");
        check(SetupManager.resolveBaseUrl(ANTHROPIC).equals(DEEPSEEK), "anthropic -> deepseek");
        check(SetupManager.resolveBaseUrl("https://API.ANTHROPIC.COM").equals(DEEPSEEK), "anthropic case -> deepseek");
        check(SetupManager.resolveBaseUrl(ANTHROPIC + "/").equals(DEEPSEEK), "anthropic trailing slash -> deepseek");
        check(SetupManager.resolveBaseUrl("not a url").equals(DEEPSEEK), "invalid -> deepseek");
        check(SetupManager.resolveBaseUrl("ftp://api.anthropic.com").equals(DEEPSEEK), "ftp anthropic -> deepseek");
        check(SetupManager.resolveBaseUrl(CUSTOM).equals(CUSTOM), "custom relay kept");
        check(SetupManager.resolveBaseUrl(CUSTOM + "/").equals(CUSTOM), "custom trailing slash stripped");
        check(SetupManager.resolveBaseUrl(DEEPSEEK).equals(DEEPSEEK), "deepseek kept");
    }

    // ---------- scriptNeedsRewrite ----------

    static void testScriptNeedsRewrite() {
        check(SetupManager.scriptNeedsRewrite(null), "null needs rewrite");
        check(SetupManager.scriptNeedsRewrite(V15_SCRIPT_NO_BASE_URL), "v1.5 no-base-url needs rewrite");
        check(SetupManager.scriptNeedsRewrite(OLD_SCRIPT_ANTHROPIC_URL), "anthropic url needs rewrite");
        check(!SetupManager.scriptNeedsRewrite(CLEAN_DEEPSEEK_SCRIPT), "clean deepseek kept");
        check(!SetupManager.scriptNeedsRewrite(CLEAN_CUSTOM_SCRIPT), "clean custom relay kept");
    }

    // ---------- ensureLauncherScript：模拟 v1.5 旧脚本残留 ----------

    static void testEnsureRewritesV15Script() throws Exception {
        File dir = Files.createTempDirectory("purge-v15").toFile();
        File script = new File(dir, "run-claude.sh");
        writeFile(script, V15_SCRIPT_NO_BASE_URL);
        boolean ok = SetupManager.ensureLauncherScript(script, FILES, "");
        check(ok, "v1.5 script ensure returns true");
        assertCleanDeepSeekScript(readFile(script), "v1.5 script rewritten");
    }

    static void testEnsureRewritesAnthropicUrlScript() throws Exception {
        File dir = Files.createTempDirectory("purge-legacy-url").toFile();
        File script = new File(dir, "run-claude.sh");
        writeFile(script, OLD_SCRIPT_ANTHROPIC_URL);
        // 即使调用方传进来的 baseUrl 本身就是 Anthropic 地址，也必须被换掉。
        boolean ok = SetupManager.ensureLauncherScript(script, FILES, ANTHROPIC);
        check(ok, "anthropic-url script ensure returns true");
        assertCleanDeepSeekScript(readFile(script), "anthropic-url script rewritten");
    }

    static void testEnsureKeepsCleanScript() throws Exception {
        File dir = Files.createTempDirectory("purge-clean").toFile();
        File script = new File(dir, "run-claude.sh");
        writeFile(script, CLEAN_DEEPSEEK_SCRIPT);
        boolean ok = SetupManager.ensureLauncherScript(script, FILES, DEEPSEEK);
        check(ok, "clean script ensure returns true");
        check(readFile(script).equals(CLEAN_DEEPSEEK_SCRIPT), "clean script byte-identical");
    }

    static void testEnsureKeepsCustomRelayScript() throws Exception {
        File dir = Files.createTempDirectory("purge-custom").toFile();
        File script = new File(dir, "run-claude.sh");
        writeFile(script, CLEAN_CUSTOM_SCRIPT);
        boolean ok = SetupManager.ensureLauncherScript(script, FILES, CUSTOM);
        check(ok, "custom relay script ensure returns true");
        check(readFile(script).equals(CLEAN_CUSTOM_SCRIPT), "custom relay script untouched");
        check(readFile(script).contains("export ANTHROPIC_BASE_URL=" + CUSTOM), "custom relay kept in script");
    }

    static void testEnsureCreatesMissingScript() throws Exception {
        File dir = Files.createTempDirectory("purge-missing").toFile();
        File script = new File(dir, "run-claude.sh");
        check(!script.exists(), "script missing before ensure");
        boolean ok = SetupManager.ensureLauncherScript(script, FILES, "");
        check(ok, "missing script ensure returns true");
        check(script.exists() && script.canExecute(), "missing script created executable");
        assertCleanDeepSeekScript(readFile(script), "missing script created clean");
    }

    // ---------- buildLauncherScript / buildEnv：Anthropic 输入也被换掉 ----------

    static void testBuildLauncherScriptWithAnthropicInput() {
        String s = SetupManager.buildLauncherScript(FILES, ANTHROPIC);
        assertCleanDeepSeekScript(s, "buildLauncherScript anthropic input");
        String s2 = SetupManager.buildLauncherScript(FILES, null);
        assertCleanDeepSeekScript(s2, "buildLauncherScript null input");
    }

    static void testBuildEnvWithAnthropicInput() {
        String[] env = ClaudeRunner.buildEnv(FILES, ANTHROPIC, FAKE_KEY);
        String joined = String.join("\n", env);
        check(joined.contains("ANTHROPIC_BASE_URL=" + DEEPSEEK), "buildEnv anthropic -> deepseek");
        check(!joined.contains("api.anthropic.com"), "buildEnv no api.anthropic.com");
        check(joined.contains("ANTHROPIC_AUTH_TOKEN=" + FAKE_KEY), "buildEnv key still passed");
        check(joined.contains("ANTHROPIC_MODEL=deepseek-flash[1m]"), "buildEnv model deepseek");
        String[] envNoKey = ClaudeRunner.buildEnv(FILES, ANTHROPIC, "");
        check(!String.join("\n", envNoKey).contains("ANTHROPIC_AUTH_TOKEN"), "buildEnv no key when empty");
    }

    // ---------- settings.json：Anthropic 地址被清掉 ----------

    static void testSettingsJsonScrub() throws Exception {
        String hostile = "{\n"
                + "  \"permissions\": {\"deny\": [\"Bash(rm -rf /system*)\"]},\n"
                + "  \"env\": {\n"
                + "    \"ANTHROPIC_BASE_URL\": \"https://api.anthropic.com\",\n"
                + "    \"ANTHROPIC_AUTH_TOKEN\": \"" + FAKE_KEY + "\",\n"
                + "    \"MY_VAR\": \"keep-me\"\n"
                + "  },\n"
                + "  \"cleanupPeriodDays\": 30\n"
                + "}\n";
        String out = SetupManager.applyAuthTokenToSettingsJson(hostile, FAKE_KEY);
        check(!out.contains("api.anthropic.com"), "hostile base url scrubbed");
        check(out.contains("ANTHROPIC_AUTH_TOKEN"), "auth token kept after scrub");
        check(out.contains("MY_VAR"), "other env vars kept after scrub");
        check(out.contains("cleanupPeriodDays"), "other settings kept after scrub");

        // 指向 DeepSeek 的 base url 不动（已正确时原文返回）。
        String deepseekEnv = "{\n"
                + "  \"env\": {\n"
                + "    \"ANTHROPIC_BASE_URL\": \"" + DEEPSEEK + "\",\n"
                + "    \"ANTHROPIC_AUTH_TOKEN\": \"" + FAKE_KEY + "\"\n"
                + "  }\n"
                + "}\n";
        String out2 = SetupManager.applyAuthTokenToSettingsJson(deepseekEnv, FAKE_KEY);
        check(out2.equals(deepseekEnv), "deepseek base url untouched");

        // 没有 env 块的 JSON 不动。
        String noEnv = "{\"permissions\": {}, \"cleanupPeriodDays\": 30}";
        String out3 = SetupManager.applyAuthTokenToSettingsJson(noEnv, "");
        check(out3.equals(noEnv), "no-env json untouched");
    }
}
