package com.mossfield.terminal;

/**
 * DeepSeekTest：v1.6 DeepSeek 后端切换测试。
 *
 * 覆盖三件事：
 * 1. 启动脚本里有全部 8 个 DeepSeek 环境变量（官方文档照抄），
 *    默认地址就是 DeepSeek，自定义地址只换 ANTHROPIC_BASE_URL，模型变量不变，
 *    非法/空地址回落到 DeepSeek。
 * 2. 会话环境变量里也有全部 DeepSeek 变量；有 Key 时带 ANTHROPIC_AUTH_TOKEN，
 *    没 Key 时不带；新代码任何地方都不再出现 ANTHROPIC_API_KEY。
 * 3. API Key 的 settings.json 流向：写的是 ANTHROPIC_AUTH_TOKEN（不是
 *    ANTHROPIC_API_KEY），旧的 ANTHROPIC_API_KEY 会被迁移掉，清 key 再存
 *    不会插出第二个 env 块（v1.4 的 bug），已经正确时不改文件。
 *
 * 注意：Key 只用明显是假的占位值（TEST-KEY-PLACEHOLDER），绝不用真 Key。
 *
 * 用法：javac -encoding UTF-8 -cp <android.jar>:<termux jars>:<tests/stub>:<tests/json-20240303.jar> -d <tests/classes>
 *          app/src/com/mossfield/terminal/{ClaudeRunner,SetupManager,RootBridge}.java
 *          tests/DeepSeekTest.java
 *       java -cp <tests/classes>:<termux jars>:<tests/json-20240303.jar> com.mossfield.terminal.DeepSeekTest
 * 全部通过退出 0，任一失败退出 1。
 * （运行期不要带 android.jar：它里面的 org.json 是 Stub，会抛 Stub!；
 *   真实现用 tests/json-20240303.jar，只供单测，不进 APK。）
 */
public class DeepSeekTest {

    static int passed = 0, failed = 0;

    static final String FILES = "/data/user/0/com.mossfield.terminal/files";
    static final String DEEPSEEK = "https://api.deepseek.com/anthropic";
    static final String CUSTOM = "https://relay.example.com/anthropic";
    static final String FAKE_KEY = "TEST-KEY-PLACEHOLDER-1234";
    static final String FAKE_KEY_2 = "TEST-KEY-PLACEHOLDER-5678";

    /** 默认 settings.json（和 SetupManager.writeDefaultSettings 写出的一致结构）。 */
    static final String DEFAULT_SETTINGS = "{\n"
            + "  \"permissions\": {\n"
            + "    \"deny\": [\n"
            + "      \"Bash(rm -rf /system*)\",\n"
            + "      \"Write(/system/**)\"\n"
            + "    ]\n"
            + "  },\n"
            + "  \"hooks\": {},\n"
            + "  \"cleanupPeriodDays\": 30\n"
            + "}\n";

    public static void main(String[] args) throws Exception {
        testLauncherScriptDefault();
        testLauncherScriptCustom();
        testLauncherScriptInvalidFallback();
        testBuildEnv();
        testApplyAuthToken();
        System.out.println("DEEPSEEK_TEST: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ---------- 1. 启动脚本 ----------

    static void testLauncherScriptDefault() {
        String s = SetupManager.buildLauncherScript(FILES, "");
        check("脚本：默认就有 ANTHROPIC_BASE_URL=DeepSeek",
                s.contains("export ANTHROPIC_BASE_URL=" + DEEPSEEK));
        check("脚本：ANTHROPIC_MODEL=deepseek-flash[1m]",
                s.contains("export ANTHROPIC_MODEL=\"deepseek-flash[1m]\""));
        check("脚本：ANTHROPIC_DEFAULT_OPUS_MODEL=deepseek-flash[1m]",
                s.contains("export ANTHROPIC_DEFAULT_OPUS_MODEL=\"deepseek-flash[1m]\""));
        check("脚本：ANTHROPIC_DEFAULT_SONNET_MODEL=deepseek-flash[1m]",
                s.contains("export ANTHROPIC_DEFAULT_SONNET_MODEL=\"deepseek-flash[1m]\""));
        check("脚本：ANTHROPIC_DEFAULT_HAIKU_MODEL=deepseek-flash",
                s.contains("export ANTHROPIC_DEFAULT_HAIKU_MODEL=\"deepseek-flash\""));
        check("脚本：CLAUDE_CODE_SUBAGENT_MODEL=deepseek-flash",
                s.contains("export CLAUDE_CODE_SUBAGENT_MODEL=\"deepseek-flash\""));
        check("脚本：CLAUDE_CODE_EFFORT_LEVEL=max",
                s.contains("export CLAUDE_CODE_EFFORT_LEVEL=\"max\""));
        check("脚本：CLAUDE_CODE_AUTO_COMPACT_WINDOW=786432",
                s.contains("export CLAUDE_CODE_AUTO_COMPACT_WINDOW=\"786432\""));
        check("脚本：不再出现 api.anthropic.com",
                !s.contains("api.anthropic.com"));
        check("脚本：不出现 ANTHROPIC_API_KEY（Key 绝不写进脚本）",
                !s.contains("ANTHROPIC_API_KEY"));
        check("脚本：不出现 ANTHROPIC_AUTH_TOKEN（Key 绝不写进脚本）",
                !s.contains("ANTHROPIC_AUTH_TOKEN"));
        check("脚本：仍以 exec 启动 claude",
                s.contains("exec " + FILES + "/bin/claude \"$@\""));
        System.out.println("--- default script ---\n" + s + "---");
    }

    static void testLauncherScriptCustom() {
        String s = SetupManager.buildLauncherScript(FILES, CUSTOM);
        check("脚本：自定义地址只换 ANTHROPIC_BASE_URL",
                s.contains("export ANTHROPIC_BASE_URL=" + CUSTOM));
        check("脚本：自定义地址下模型变量仍是 DeepSeek 的",
                s.contains("export ANTHROPIC_MODEL=\"deepseek-flash[1m]\"")
                        && s.contains("export CLAUDE_CODE_SUBAGENT_MODEL=\"deepseek-flash\""));
        check("脚本：自定义地址不含 DeepSeek 默认地址",
                !s.contains(DEEPSEEK));
    }

    static void testLauncherScriptInvalidFallback() {
        String s1 = SetupManager.buildLauncherScript(FILES, "not a url");
        check("脚本：非法地址回落到 DeepSeek",
                s1.contains("export ANTHROPIC_BASE_URL=" + DEEPSEEK));
        String s2 = SetupManager.buildLauncherScript(FILES, null);
        check("脚本：null 地址回落到 DeepSeek",
                s2.contains("export ANTHROPIC_BASE_URL=" + DEEPSEEK));
        String s3 = SetupManager.buildLauncherScript(FILES, DEEPSEEK + "/");
        check("脚本：末尾斜杠会被去掉",
                s3.contains("export ANTHROPIC_BASE_URL=" + DEEPSEEK)
                        && !s3.contains(DEEPSEEK + "/\n"));
    }

    // ---------- 2. 会话环境变量 ----------

    static void testBuildEnv() {
        String[] env = ClaudeRunner.buildEnv(FILES, "", FAKE_KEY);
        check("会话 env：ANTHROPIC_BASE_URL=DeepSeek",
                has(env, "ANTHROPIC_BASE_URL=" + DEEPSEEK));
        check("会话 env：ANTHROPIC_MODEL=deepseek-flash[1m]",
                has(env, "ANTHROPIC_MODEL=deepseek-flash[1m]"));
        check("会话 env：ANTHROPIC_DEFAULT_OPUS_MODEL=deepseek-flash[1m]",
                has(env, "ANTHROPIC_DEFAULT_OPUS_MODEL=deepseek-flash[1m]"));
        check("会话 env：ANTHROPIC_DEFAULT_SONNET_MODEL=deepseek-flash[1m]",
                has(env, "ANTHROPIC_DEFAULT_SONNET_MODEL=deepseek-flash[1m]"));
        check("会话 env：ANTHROPIC_DEFAULT_HAIKU_MODEL=deepseek-flash",
                has(env, "ANTHROPIC_DEFAULT_HAIKU_MODEL=deepseek-flash"));
        check("会话 env：CLAUDE_CODE_SUBAGENT_MODEL=deepseek-flash",
                has(env, "CLAUDE_CODE_SUBAGENT_MODEL=deepseek-flash"));
        check("会话 env：CLAUDE_CODE_EFFORT_LEVEL=max",
                has(env, "CLAUDE_CODE_EFFORT_LEVEL=max"));
        check("会话 env：CLAUDE_CODE_AUTO_COMPACT_WINDOW=786432",
                has(env, "CLAUDE_CODE_AUTO_COMPACT_WINDOW=786432"));
        check("会话 env：有 Key 时带 ANTHROPIC_AUTH_TOKEN（值就是 Key）",
                has(env, "ANTHROPIC_AUTH_TOKEN=" + FAKE_KEY));
        check("会话 env：不出现 ANTHROPIC_API_KEY",
                !hasPrefix(env, "ANTHROPIC_API_KEY="));
        check("会话 env：不出现 api.anthropic.com",
                !containsAny(env, "api.anthropic.com"));

        String[] noKey = ClaudeRunner.buildEnv(FILES, "", "");
        check("会话 env：没 Key 时不带 ANTHROPIC_AUTH_TOKEN",
                !hasPrefix(noKey, "ANTHROPIC_AUTH_TOKEN="));
        check("会话 env：没 Key 时仍有 DeepSeek 变量",
                has(noKey, "ANTHROPIC_BASE_URL=" + DEEPSEEK)
                        && has(noKey, "ANTHROPIC_MODEL=deepseek-flash[1m]"));

        String[] custom = ClaudeRunner.buildEnv(FILES, CUSTOM, null);
        check("会话 env：自定义地址只换 ANTHROPIC_BASE_URL",
                has(custom, "ANTHROPIC_BASE_URL=" + CUSTOM));
    }

    // ---------- 3. API Key 的 settings.json 流向 ----------

    static void testApplyAuthToken() throws Exception {
        // 3a. 默认 settings.json（无 env 块）存 Key：新建 env 块，写 AUTH_TOKEN。
        String set1 = SetupManager.applyAuthTokenToSettingsJson(DEFAULT_SETTINGS, FAKE_KEY);
        org.json.JSONObject j1 = new org.json.JSONObject(set1);
        check("settings：存 Key 后 env 块存在",
                j1.has("env") && j1.getJSONObject("env").has("ANTHROPIC_AUTH_TOKEN"));
        check("settings：存的是 ANTHROPIC_AUTH_TOKEN，值就是 Key",
                FAKE_KEY.equals(j1.getJSONObject("env").getString("ANTHROPIC_AUTH_TOKEN")));
        check("settings：没有 ANTHROPIC_API_KEY",
                !j1.getJSONObject("env").has("ANTHROPIC_API_KEY"));
        check("settings：只有一个 env 键（无重复）",
                countOccurrences(set1, "\"env\"") == 1);
        check("settings：原有 permissions 还在",
                j1.has("permissions") && j1.getJSONObject("permissions").has("deny"));
        check("settings：原有 cleanupPeriodDays 还在",
                j1.getInt("cleanupPeriodDays") == 30);

        // 3b. 换 Key：值被替换，不多出 env 块。
        String set2 = SetupManager.applyAuthTokenToSettingsJson(set1, FAKE_KEY_2);
        org.json.JSONObject j2 = new org.json.JSONObject(set2);
        check("settings：换 Key 后值更新了",
                FAKE_KEY_2.equals(j2.getJSONObject("env").getString("ANTHROPIC_AUTH_TOKEN")));
        check("settings：换 Key 后仍只有一个 env 键",
                countOccurrences(set2, "\"env\"") == 1);

        // 3c. 清 Key：token 行删掉，JSON 仍合法。
        String set3 = SetupManager.applyAuthTokenToSettingsJson(set2, "");
        org.json.JSONObject j3 = new org.json.JSONObject(set3);
        check("settings：清 Key 后 AUTH_TOKEN 没了",
                !j3.getJSONObject("env").has("ANTHROPIC_AUTH_TOKEN"));
        check("settings：清 Key 后仍是合法 JSON（能解析）",
                j3.has("permissions"));

        // 3d. v1.4 的 bug：清掉再存，不插第二个 env 块。
        String set4 = SetupManager.applyAuthTokenToSettingsJson(set3, FAKE_KEY);
        check("settings：清后再存，仍只有一个 env 键",
                countOccurrences(set4, "\"env\"") == 1);
        org.json.JSONObject j4 = new org.json.JSONObject(set4);
        check("settings：清后再存，值写对了",
                FAKE_KEY.equals(j4.getJSONObject("env").getString("ANTHROPIC_AUTH_TOKEN")));

        // 3e. 迁移：旧的 ANTHROPIC_API_KEY 被删掉，换成 AUTH_TOKEN。
        String legacy = "{\n  \"env\": {\n    \"ANTHROPIC_API_KEY\": \"" + FAKE_KEY + "\"\n  },\n"
                + "  \"cleanupPeriodDays\": 30\n}\n";
        String set5 = SetupManager.applyAuthTokenToSettingsJson(legacy, FAKE_KEY);
        org.json.JSONObject j5 = new org.json.JSONObject(set5);
        check("settings：旧 ANTHROPIC_API_KEY 被迁移掉",
                !j5.getJSONObject("env").has("ANTHROPIC_API_KEY"));
        check("settings：迁移后是 ANTHROPIC_AUTH_TOKEN",
                FAKE_KEY.equals(j5.getJSONObject("env").getString("ANTHROPIC_AUTH_TOKEN")));
        check("settings：迁移后仍只有一个 env 键",
                countOccurrences(set5, "\"env\"") == 1);

        // 3f. 已经是要的样子：返回原文，不改格式、不动文件。
        String set6 = SetupManager.applyAuthTokenToSettingsJson(set1, FAKE_KEY);
        check("settings：已正确时返回原文（不重写）",
                set6.equals(set1));

        // 3g. env 块里已有别的变量：Key 插进去，别的变量还在。
        String withOther = "{\n  \"env\": {\n    \"OTHER_VAR\": \"x\"\n  },\n"
                + "  \"cleanupPeriodDays\": 30\n}\n";
        String set7 = SetupManager.applyAuthTokenToSettingsJson(withOther, FAKE_KEY);
        org.json.JSONObject j7 = new org.json.JSONObject(set7);
        check("settings：已有别的 env 变量时，Key 插进去且别的还在",
                FAKE_KEY.equals(j7.getJSONObject("env").getString("ANTHROPIC_AUTH_TOKEN"))
                        && "x".equals(j7.getJSONObject("env").getString("OTHER_VAR")));
        check("settings：已有别的 env 变量时，仍只有一个 env 键",
                countOccurrences(set7, "\"env\"") == 1);
    }

    // ---------- 工具 ----------

    static boolean has(String[] env, String entry) {
        for (String e : env) if (e.equals(entry)) return true;
        return false;
    }

    static boolean hasPrefix(String[] env, String prefix) {
        for (String e : env) if (e.startsWith(prefix)) return true;
        return false;
    }

    static boolean containsAny(String[] env, String sub) {
        for (String e : env) if (e.contains(sub)) return true;
        return false;
    }

    static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
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
