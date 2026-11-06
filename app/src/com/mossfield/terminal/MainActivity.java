package com.mossfield.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;

/**
 * MainActivity：终端界面。
 *
 * 首次启动：释放 Claude Code → 检测 root → 拉起会话。
 * 终端用 Termux 的 TerminalView/TerminalSession（GPL-3.0，源码随 APK 一并交付）。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final String PREFS = "moss_terminal";
    private static final String KEY_ROOT_MODE = "root_mode";
    // API Key / 中转地址的键用 SetupManager 的（同一份，避免两处定义跑偏）。
    private static final String KEY_LOGIN_HINT_SHOWN = "login_hint_shown";

    private TerminalView terminalView;
    private TerminalSession session;
    private SetupManager setup;
    private RootBridge rootBridge;
    private ClaudeRunner runner;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 首次释放的进度框（只在需要释放时出现，失败不闪退，给重试）。 */
    private ProgressDialog setupDialog;

    /** 会话创建时间（毫秒），用于判断“刚启动就退出”。 */
    private volatile long sessionStartMs = 0;
    /** 当前会话的“刚启动就退出”错误框是否已弹过（避免重复弹）。 */
    private volatile boolean sessionExitDialogShown = false;
    /** CTRL 键开关（底部 key bar 的 CTRL 按钮控制）。 */
    private volatile boolean ctrlActive = false;
    /** 用户点了悬浮窗的“去开启”后正在系统设置里，回来时要接着开服务。 */
    private boolean overlayPending = false;
    /** 悬浮窗发来的“打开 API Key 设置框”广播。 */
    private final BroadcastReceiver apiKeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (FloatingWindowService.ACTION_OPEN_API_KEY.equals(intent.getAction())) {
                showApiKeyDialog();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setup = new SetupManager(this);
        rootBridge = new RootBridge(getFilesDir());
        runner = new ClaudeRunner(setup, rootBridge);

        terminalView = findViewById(R.id.terminal_view);
        terminalView.setTerminalViewClient(viewClient);
        terminalView.setTextSize(36);
        setupKeyBar();
        setupTopBar(); // v1.4：顶部常驻按钮（菜单 / API Key / 悬浮窗 / 网络诊断）

        // 悬浮窗的 API Key 广播（只收本应用的，不导出）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(apiKeyReceiver,
                    new IntentFilter(FloatingWindowService.ACTION_OPEN_API_KEY),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(apiKeyReceiver,
                    new IntentFilter(FloatingWindowService.ACTION_OPEN_API_KEY));
        }

        // 首次设置放后台线程做（释放 320 MB 需要几秒）。
        new Thread(this::doSetupAndStart).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFloatingTopButton();
        // 从悬浮窗权限设置页回来：如果已授权就接着开悬浮窗。
        if (overlayPending) {
            overlayPending = false;
            if (Settings.canDrawOverlays(this) && !FloatingWindowService.isRunning()) {
                startService(new Intent(this, FloatingWindowService.class));
                Toast.makeText(this, R.string.floating_on, Toast.LENGTH_SHORT).show();
                updateFloatingTopButton();
            }
        }
    }

    /**
     * 首次设置 + 启动会话（后台线程）。
     * v1.1：释放时显示带进度的对话框；任何失败（包括 OOM 等 Error）
     * 都进错误框给一键重试，绝不闪退。
     * v1.2：释放之后的启动环节（自检 / root 检测 / 保活服务 / 启动前校验）
     * 也包进 try-catch(Throwable)，任何失败都进日志 + 错误框，不杀进程。
     * 闪退根因是保活服务：KeepAliveService 声明了 foregroundServiceType="dataSync"，
     * 但 v1.1 的 manifest 漏了 FOREGROUND_SERVICE_DATA_SYNC 权限，
     * Android 14+ 上 startForeground() 直接抛 SecurityException 杀进程。
     * v1.2 已在 manifest 里补上权限，这里再加 try-catch 做兜底。
     * v1.3：su 参数修好了（见 ClaudeRunner.buildArgv）；另外会话刚启动
     * 就非零退出时，onSessionFinished 会弹明白的错误框 + 一键重试，
     * 不再只剩终端里一行报错。
     */
    private void doSetupAndStart() {
        LaunchLog launchLog = new LaunchLog(getFilesDir());
        launchLog.log("启动开始");
        try {
            if (!setup.isSetupDone()) {
                launchLog.log("首次设置：开始释放");
                showSetupDialog();
                setup.runSetup(new SetupManager.ProgressListener() {
                    @Override
                    public void onStage(final String stage) {
                        runOnUiThread(() -> {
                            if (setupDialog != null) setupDialog.setMessage(stage);
                        });
                    }

                    @Override
                    public void onProgress(final long done, final long total) {
                        final int pct = total > 0
                                ? (int) Math.min(100, done * 100 / total) : 0;
                        runOnUiThread(() -> {
                            if (setupDialog != null) setupDialog.setProgress(pct);
                        });
                    }
                });
                launchLog.log("首次设置：释放完成");
            } else {
                launchLog.log("已设置过，跳过释放");
            }
        } catch (Throwable t) {
            // 注意是 Throwable：OOM 等 Error 也要接住，显示错误框而不是闪退。
            Log.e(TAG, "setup failed", t);
            launchLog.log("释放失败：" + t);
            final String msg = friendlySetupError(t);
            runOnUiThread(() -> showSetupError(msg));
            return;
        }
        dismissSetupDialog();

        // v1.7：先跑一次性迁移（清掉 v1.4 时代的中转地址，默认变 DeepSeek），
        // 再强制保证启动脚本干净——每次启动都按当前生效地址重新生成，
        // 覆盖任何旧脚本（v1.5 升级残留、手改过的脚本都在这里被清掉）。
        // v1.6 的教训：重写失败只记日志、会话照常启动，旧脚本就这么活下来了；
        // v1.7 改成硬失败：脚本保证不了干净，就不启动会话，弹明白的错 + 重试。
        // v1.4：已装过的机器（v1.3 升上来）启动脚本里还没有中转地址，
        // 每次启动都按保存的地址重写一次脚本，保证改地址/恢复默认都生效。
        try {
            setup.migrateToDeepSeekDefaults();
            if (!setup.ensureLauncherScript()) {
                launchLog.log("启动脚本校验失败：无法生成干净的启动脚本");
                final String msg = "启动脚本写不干净（可能是存储出问题了）。"
                        + "为防止连到旧的 Anthropic 地址，这次不启动会话。"
                        + "点重试可再试一次。";
                runOnUiThread(() -> showLaunchError(msg));
                return;
            }
            launchLog.log("启动脚本已校验干净");
        } catch (Throwable t) {
            Log.w(TAG, "ensure launcher script failed", t);
            launchLog.log("启动脚本校验失败：" + t);
            final String msg = "启动脚本校验失败：" + friendlySetupError(t)
                    + "\n\n为防止连到旧的 Anthropic 地址，这次不启动会话。点重试可再试一次。";
            runOnUiThread(() -> showLaunchError(msg));
            return;
        }

        // ---- 启动环节：全部包进 try-catch，失败进日志 + 错误框，绝不闪退 ----
        try {
            // 启动自检（system 是否 ro）
            String check = setup.selfCheck();
            Log.i(TAG, "selfCheck: " + check);
            launchLog.log("自检：" + check.replace("\n", " "));

            // root 检测
            boolean hasRoot = rootBridge.hasRoot();
            launchLog.log("root 检测：" + (hasRoot ? "有 root" : "无 root"));
            boolean rootMode = prefs.getBoolean(KEY_ROOT_MODE, true) && hasRoot;

            // 写 API key 到 settings.json 的 env 块（如果用户设过）
            applyApiKey();

            // 前台服务保活（失败不致命：记日志继续，终端照常启动）
            try {
                startService(new Intent(this, KeepAliveService.class));
                launchLog.log("保活服务已启动");
            } catch (Throwable t) {
                Log.e(TAG, "keepalive start failed", t);
                launchLog.log("保活服务启动失败（不影响终端）：" + t);
            }

            // 启动前校验：二进制、加载器、脚本、终端 JNI 库、su
            String preflight = runner.preflight(rootMode);
            if (preflight != null) {
                launchLog.log("启动前校验失败：" + preflight);
                final String msg = preflight;
                runOnUiThread(() -> showLaunchError(msg));
                return;
            }
            launchLog.log("启动前校验通过，root 模式=" + rootMode);

            final boolean finalRootMode = rootMode;
            final boolean finalHasRoot = hasRoot;
            runOnUiThread(() -> {
                if (!finalHasRoot && prefs.getBoolean(KEY_ROOT_MODE, true)) {
                    // root 没生效：给明确提示（继续普通模式 / 退出），而不是崩。
                    launchLog.log("root 未生效，弹出提示框");
                    showRootMissingDialog();
                } else {
                    startSession(finalRootMode);
                    showLoginHintOnce();
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "launch failed", t);
            launchLog.log("启动环节失败：" + t);
            final String msg = "启动失败：" + friendlySetupError(t) + "\n\n点重试可再试一次。";
            runOnUiThread(() -> showLaunchError(msg));
        }
    }

    /** 把技术异常翻译成人话（用户不用懂，知道怎么办就行）。 */
    private static String friendlySetupError(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) msg = t.toString();
        if (t instanceof OutOfMemoryError) {
            return "内存不足，释放中断了。请关掉其他应用再点重试。";
        }
        // IOException 等已经在 SetupManager 里写成人话了，直接用。
        return msg;
    }

    private void showSetupDialog() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            dismissSetupDialog();
            setupDialog = new ProgressDialog(this);
            setupDialog.setTitle(R.string.setup_title);
            setupDialog.setMessage(getString(R.string.setup_extracting));
            setupDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            setupDialog.setMax(100);
            setupDialog.setProgress(0);
            setupDialog.setCancelable(false);
            setupDialog.setCanceledOnTouchOutside(false);
            setupDialog.show();
        });
    }

    private void dismissSetupDialog() {
        runOnUiThread(() -> {
            if (setupDialog != null) {
                try {
                    setupDialog.dismiss();
                } catch (Exception ignored) {
                }
                setupDialog = null;
            }
        });
    }

    /** 释放失败的错误框：说清原因，一键重试；Activity 已销毁时不弹，避免二次崩溃。 */
    private void showSetupError(String msg) {
        dismissSetupDialog();
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_failed_title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.retry, (d, w) -> new Thread(this::doSetupAndStart).start())
                .setNegativeButton(R.string.exit_app, (d, w) -> finish())
                .show();
    }

    /** 启动失败的错误框：说清原因，一键重试；Activity 已销毁时不弹，避免二次崩溃。 */
    private void showLaunchError(String msg) {
        dismissSetupDialog();
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.launch_failed_title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.retry, (d, w) -> new Thread(this::doSetupAndStart).start())
                .setNegativeButton(R.string.exit_app, (d, w) -> finish())
                .show();
    }

    /** root 没生效的明确提示：继续普通模式 / 退出，而不是崩。 */
    private void showRootMissingDialog() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.root_missing_title)
                .setMessage(R.string.root_missing_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.root_missing_continue, (d, w) -> {
                    startSession(false);
                    showLoginHintOnce();
                })
                .setNegativeButton(R.string.exit_app, (d, w) -> finish())
                .show();
    }

    /** 查看启动日志：菜单里直接能看，不用连电脑抓 logcat。 */
    private void showLaunchLog() {
        String content = new LaunchLog(getFilesDir()).readAll();
        if (content == null || content.isEmpty()) content = getString(R.string.launch_log_empty);
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(13);
        tv.setPadding(24, 16, 24, 16);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(R.string.launch_log_title)
                .setView(sv)
                .setPositiveButton("好", null)
                .setNegativeButton(R.string.launch_log_clear,
                        (d, w) -> new LaunchLog(getFilesDir()).clear())
                .show();
    }

    /** 悬浮窗开关：没权限先讲清楚是干什么的，再一键去系统设置里开。 */
    private void toggleFloatingWindow() {
        if (FloatingWindowService.isRunning()) {
            stopService(new Intent(this, FloatingWindowService.class));
            Toast.makeText(this, R.string.floating_off, Toast.LENGTH_SHORT).show();
            updateFloatingTopButton();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.floating_title)
                    .setMessage(R.string.floating_explain)
                    .setPositiveButton(R.string.floating_open_settings, (d, w) -> {
                        overlayPending = true;
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        startService(new Intent(this, FloatingWindowService.class));
        Toast.makeText(this, R.string.floating_on, Toast.LENGTH_LONG).show();
        updateFloatingTopButton();
    }

    // ---------- v1.4：顶部常驻按钮 ----------

    /** 顶部一排大按钮：菜单 / API Key / 悬浮窗 / 网络诊断，一直可见，不用找菜单。 */
    private void setupTopBar() {
        findViewById(R.id.btn_menu_top).setOnClickListener(v -> openOptionsMenu());
        findViewById(R.id.btn_api_key_top).setOnClickListener(v -> showApiKeyDialog());
        findViewById(R.id.btn_floating_top).setOnClickListener(v -> toggleFloatingWindow());
        findViewById(R.id.btn_net_top).setOnClickListener(v -> showNetworkDiagnostics());
        updateFloatingTopButton();
    }

    /** 顶部悬浮窗按钮显示当前状态（开/关），一点就切换。 */
    private void updateFloatingTopButton() {
        View b = findViewById(R.id.btn_floating_top);
        if (b instanceof android.widget.Button) {
            ((android.widget.Button) b).setText(FloatingWindowService.isRunning()
                    ? "悬浮窗：开" : "悬浮窗：关");
        }
    }

    /** v1.4 网络诊断：一键测 DNS + 443 连通，结果说人话（后台线程跑，不卡界面）。 */
    private void showNetworkDiagnostics() {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(R.string.net_diag_title);
        pd.setMessage(getString(R.string.net_diag_running));
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            String report = NetworkDiagnostics.run(setup.getSavedBaseUrl());
            runOnUiThread(() -> {
                try {
                    pd.dismiss();
                } catch (Exception ignored) {
                }
                if (isFinishing() || isDestroyed()) return;
                TextView tv = new TextView(this);
                tv.setText(report);
                tv.setTextSize(14);
                tv.setPadding(24, 16, 24, 16);
                ScrollView sv = new ScrollView(this);
                sv.addView(tv);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.net_diag_title)
                        .setView(sv)
                        .setPositiveButton("好", null)
                        .setNeutralButton("再测一次", (d, w) -> showNetworkDiagnostics())
                        .show();
            });
        }).start();
    }

    /** v1.4 中转地址设置：填一个讲 Anthropic API 格式的中转地址就保存，重启会话生效。 */
    private void showBaseUrlDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 16, 48, 8);
        TextView caveat = new TextView(this);
        caveat.setText(R.string.base_url_caveat);
        caveat.setTextSize(13);
        layout.addView(caveat);
        EditText input = new EditText(this);
        input.setHint(R.string.base_url_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        // 预填生效地址（默认就是 DeepSeek），让用户看到现在实际在用什么。
        input.setText(setup.getSavedBaseUrl());
        layout.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_base_url)
                .setView(layout)
                .setPositiveButton("保存", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) {
                        saveBaseUrl("");
                        return;
                    }
                    if (!SetupManager.isValidBaseUrl(url)) {
                        Toast.makeText(this, "地址格式不对：要以 http:// 或 https:// 开头，"
                                + "不能含空格和 $ ` \" ' \\ ; | & < > 这些字符。",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    saveBaseUrl(url);
                })
                .setNeutralButton("恢复默认", (d, w) -> saveBaseUrl(""))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 保存/清空中转地址：写 SharedPreferences + 重写启动脚本，提示重启会话生效。 */
    private void saveBaseUrl(String url) {
        // v1.7：api.anthropic.com 已在代码里禁用——用户就算手填这个地址也不保存，
        // 直接按恢复默认处理，不让它有机会再进 SharedPreferences。
        if (!url.isEmpty() && SetupManager.isAnthropicApiUrl(url)) {
            prefs.edit().putString(SetupManager.KEY_BASE_URL, "").apply();
            try {
                setup.rewriteLauncherScript();
            } catch (Exception e) {
                Log.w(TAG, "rewrite launcher script failed", e);
            }
            Toast.makeText(this, "api.anthropic.com 已禁用，App 不会再连 Anthropic；"
                    + "已恢复默认 DeepSeek 地址，重启会话后生效", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString(SetupManager.KEY_BASE_URL, url).apply();
        try {
            setup.rewriteLauncherScript();
        } catch (Exception e) {
            Log.w(TAG, "rewrite launcher script failed", e);
        }
        if (url.isEmpty()) {
            Toast.makeText(this, "已恢复默认 DeepSeek 地址，重启会话后生效", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "中转地址已保存，重启会话后生效（菜单 → 重启会话）",
                    Toast.LENGTH_LONG).show();
        }
    }
    private void showLoginHintOnce() {
        if (prefs.getBoolean(KEY_LOGIN_HINT_SHOWN, false)) return;
        prefs.edit().putBoolean(KEY_LOGIN_HINT_SHOWN, true).apply();
        Toast.makeText(this, R.string.login_hint, Toast.LENGTH_LONG).show();
    }

    private void startSession(boolean rootMode) {
        // v1.7：建会话前再验一次启动脚本（覆盖菜单“重启会话”、
        // root 缺失后继续等所有入口）——脚本不干净就不建会话。
        try {
            if (!setup.ensureLauncherScript()) {
                showLaunchError("启动脚本写不干净（可能是存储出问题了）。"
                        + "为防止连到旧的 Anthropic 地址，这次不启动会话。"
                        + "点重试可再试一次。");
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensure launcher script failed", t);
            showLaunchError("启动脚本校验失败：" + friendlySetupError(t)
                    + "\n\n为防止连到旧的 Anthropic 地址，这次不启动会话。点重试可再试一次。");
            return;
        }
        if (session != null) session.finishIfRunning();
        sessionStartMs = System.currentTimeMillis();
        sessionExitDialogShown = false;
        try {
            session = runner.createSession(sessionClient, rootMode);
            SessionHolder.set(session);
            ClawdStateBus.noteSessionStarted(); // v1.5：会话启动，螃蟹回 idle。
            new LaunchLog(getFilesDir()).log("会话已创建，root 模式=" + rootMode);
            terminalView.attachSession(session);
            terminalView.requestFocus();
            showKeyboard();
        } catch (Throwable t) {
            // 注意是 Throwable：JNI 加载失败（UnsatisfiedLinkError）这类 Error 也要接住。
            Log.e(TAG, "start session failed", t);
            new LaunchLog(getFilesDir()).log("创建会话失败：" + t);
            SessionHolder.clear();
            showLaunchError("创建终端会话失败：" + friendlySetupError(t));
        }
    }

    /**
     * 把用户设的 API key 写进 settings.json 的 env 块（文件 0600）。
     * v1.6：改名——DeepSeek 文档用的是 ANTHROPIC_AUTH_TOKEN，不是
     * ANTHROPIC_API_KEY（v1.5 之前写错了名字）。JSON 操作走
     * SetupManager.applyAuthTokenToSettingsJson（org.json，不会再插出
     * 重复的 env 块；旧的 ANTHROPIC_API_KEY 会被迁移掉）。
     * Key 只存本机 settings.json env 块；会话环境变量里还会再带一份
     * （ClaudeRunner.buildEnv，只走内存，不写文件），两边同一个值。
     */
    private void applyApiKey() {
        String key = prefs.getString(SetupManager.KEY_API_KEY, null);
        try {
            File settings = new File(setup.getHomeDir(), ".claude/settings.json");
            String content = readFile(settings);
            String updated = SetupManager.applyAuthTokenToSettingsJson(content, key);
            if (!updated.equals(content)) {
                writeFile(settings, updated);
                new ProcessBuilder("/system/bin/sh", "-c",
                        "chmod 600 " + settings.getAbsolutePath()).start();
            }
        } catch (Exception e) {
            Log.w(TAG, "applyApiKey failed", e);
        }
    }

    private String readFile(File f) throws Exception {
        byte[] data = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            in.read(data);
        }
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void writeFile(File f, String content) throws Exception {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    // ---------- 底部 key bar ----------

    private void setupKeyBar() {
        findViewById(R.id.key_esc).setOnClickListener(v -> sendText("\u001b"));
        findViewById(R.id.key_tab).setOnClickListener(v -> sendText("\t"));
        findViewById(R.id.key_up).setOnClickListener(v -> sendText("\u001b[A"));
        findViewById(R.id.key_down).setOnClickListener(v -> sendText("\u001b[B"));
        findViewById(R.id.key_left).setOnClickListener(v -> sendText("\u001b[D"));
        findViewById(R.id.key_right).setOnClickListener(v -> sendText("\u001b[C"));
        findViewById(R.id.key_paste).setOnClickListener(v -> pasteFromClipboard());
        findViewById(R.id.key_ctrl).setOnClickListener(v -> {
            ctrlActive = !ctrlActive;
            v.setBackgroundColor(ctrlActive ? 0xFF4CAF50 : 0xFF3A3A3A);
            Toast.makeText(this, ctrlActive ? "CTRL 已按下" : "CTRL 已松开",
                    Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.key_menu).setOnClickListener(v -> openOptionsMenu());
    }

    private void sendText(String text) {
        if (session == null || !session.isRunning()) return;
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        session.write(bytes, 0, bytes.length);
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).getText();
        if (text != null) sendText(text.toString());
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    // ---------- 菜单 ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.menu_restart);
        menu.add(0, 2, 0, R.string.menu_root_toggle);
        menu.add(0, 3, 0, R.string.menu_api_key);
        menu.add(0, 8, 0, R.string.menu_network);
        menu.add(0, 9, 0, R.string.menu_base_url);
        menu.add(0, 6, 0, R.string.menu_floating);
        menu.add(0, 7, 0, R.string.menu_launch_log);
        menu.add(0, 4, 0, R.string.menu_about);
        menu.add(0, 5, 0, R.string.menu_exit);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1: // 重启会话
                boolean rootMode = prefs.getBoolean(KEY_ROOT_MODE, true) && rootBridge.hasRoot();
                startSession(rootMode);
                return true;
            case 2: // Root 模式开关
                boolean current = prefs.getBoolean(KEY_ROOT_MODE, true);
                prefs.edit().putBoolean(KEY_ROOT_MODE, !current).apply();
                Toast.makeText(this, "Root 模式：" + (!current ? "开" : "关")
                        + "（重启会话后生效）", Toast.LENGTH_LONG).show();
                return true;
            case 3: // API Key
                showApiKeyDialog();
                return true;
            case 4: // 关于
                new AlertDialog.Builder(this)
                        .setTitle(R.string.app_name)
                        .setMessage(R.string.about_text)
                        .setPositiveButton("好", null)
                        .show();
                return true;
            case 6: // 悬浮窗（一键自动填入）
                toggleFloatingWindow();
                return true;
            case 8: // 网络诊断
                showNetworkDiagnostics();
                return true;
            case 9: // 中转地址
                showBaseUrlDialog();
                return true;
            case 7: // 查看启动日志
                showLaunchLog();
                return true;
            case 5: // 退出
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * v1.4 一键填 API Key：输入框 + “粘贴”按钮（从剪贴板一键填入）+“清除”按钮，
     * 点保存就存本机并写进 settings.json。已保存的 key 不回显原文（只显示“已保存”）。
     */
    private void showApiKeyDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 16, 48, 8);
        TextView status = new TextView(this);
        String existing = prefs.getString(SetupManager.KEY_API_KEY, "");
        status.setText(existing.isEmpty()
                ? "还没保存过 API Key。粘贴一次就保存好，下次不用再填。"
                : "已保存了一个 API Key（不显示原文）。粘贴新的覆盖它，或点清除删掉。");
        status.setTextSize(13);
        layout.addView(status);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.api_key_hint);
        layout.addView(input);
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.Button pasteBtn = new android.widget.Button(this);
        pasteBtn.setText("粘贴");
        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null && text.length() > 0) {
                        input.setText(text.toString().trim());
                        input.setSelection(input.getText().length());
                        return;
                    }
                }
            }
            Toast.makeText(this, "剪贴板里没有文字，先复制 API Key 再来粘贴",
                    Toast.LENGTH_SHORT).show();
        });
        row.addView(pasteBtn);
        android.widget.Button clearBtn = new android.widget.Button(this);
        clearBtn.setText("清除已保存");
        clearBtn.setOnClickListener(v -> {
            input.setText("");
            prefs.edit().putString(SetupManager.KEY_API_KEY, "").apply();
            applyApiKey();
            Toast.makeText(this, "已清除保存的 API Key", Toast.LENGTH_SHORT).show();
        });
        row.addView(clearBtn);
        layout.addView(row);
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_api_key)
                .setView(layout)
                .setPositiveButton("保存", (d, w) -> {
                    String key = input.getText().toString().trim();
                    prefs.edit().putString(SetupManager.KEY_API_KEY, key).apply();
                    applyApiKey();
                    Toast.makeText(this, key.isEmpty()
                            ? "已清除（没有填 key）" : "已保存（只存本机）",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 返回键 = ESC（终端里常用）
            sendText("\u001b");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (session != null) session.finishIfRunning();
        SessionHolder.clear();
        try {
            unregisterReceiver(apiKeyReceiver);
        } catch (Exception ignored) {
        }
        stopService(new Intent(this, KeepAliveService.class));
        super.onDestroy();
    }

    // ---------- TerminalSessionClient ----------

    private final TerminalSessionClient sessionClient = new TerminalSessionClient() {
        @Override
        public void onTextChanged(TerminalSession session) {
            terminalView.onScreenUpdated();
            // v1.5：终端有输出 = Claude 在干活，螃蟹切到打字。
            ClawdStateBus.noteTerminalOutput();
        }

        @Override
        public void onTitleChanged(TerminalSession session) {
            String title = session.getTitle();
            if (title != null && !title.isEmpty()) setTitle(title);
        }

        @Override
        public void onSessionFinished(TerminalSession session) {
            // 只有当前会话才处理（重启后旧会话的回调不再理会）。
            if (session != MainActivity.this.session) return;
            ClawdStateBus.noteSessionFinished(); // v1.5：会话结束，螃蟹回 idle。
            final int exitCode = session.getExitStatus();
            final long livedMs = System.currentTimeMillis() - sessionStartMs;
            mainHandler.post(() -> {
                // v1.3：会话刚启动（10 秒内）就非零退出，大概率是启动命令本身有问题
                // （比如 v1.2 的 su 参数错：su 直接报 Usage 退出码 2）。
                // 别只剩终端里一行报错 + 一个 Toast：给明白的错误框 + 一键重试。
                // 正常退出（退出码 0）或跑了一阵子才退出的，走原来的 Toast。
                if (exitCode != 0 && livedMs < 10000 && !sessionExitDialogShown) {
                    sessionExitDialogShown = true;
                    new LaunchLog(getFilesDir()).log(
                            "会话启动后 " + livedMs + "ms 就退出了，退出码=" + exitCode);
                    showLaunchError("终端会话刚启动就退出了（退出码 " + exitCode + "）。"
                            + "可能是启动命令有问题。点重试可再试一次；"
                            + "也可以到菜单 → 查看启动日志看详细记录。");
                } else {
                    Toast.makeText(MainActivity.this,
                            R.string.session_finished, Toast.LENGTH_LONG).show();
                }
            });
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
            pasteFromClipboard();
        }

        @Override
        public void onBell(TerminalSession session) {
            // 忽略响铃
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
            // 忽略
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
            // 忽略
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE;
        }

        @Override
        public void logError(String tag, String message) { Log.e(tag, message); }

        @Override
        public void logWarn(String tag, String message) { Log.w(tag, message); }

        @Override
        public void logInfo(String tag, String message) { Log.i(tag, message); }

        @Override
        public void logDebug(String tag, String message) { Log.d(tag, message); }

        @Override
        public void logVerbose(String tag, String message) { Log.v(tag, message); }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.e(tag, "stack", e);
        }
    };

    // ---------- TerminalViewClient ----------

    private final TerminalViewClient viewClient = new TerminalViewClient() {
        @Override
        public float onScale(float scale) {
            return 1.0f;
        }

        @Override
        public void onSingleTapUp(android.view.MotionEvent e) {
            terminalView.requestFocus();
            showKeyboard();
        }

        @Override
        public boolean shouldBackButtonBeMappedToEscape() {
            return true;
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return false;
        }

        @Override
        public boolean shouldUseCtrlSpaceWorkaround() {
            return false;
        }

        @Override
        public boolean isTerminalViewSelected() {
            return true;
        }

        @Override
        public void copyModeChanged(boolean copyMode) {
            // 忽略
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
            return false;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent e) {
            return false;
        }

        @Override
        public boolean onLongPress(android.view.MotionEvent e) {
            return false;
        }

        @Override
        public boolean readControlKey() {
            boolean c = ctrlActive;
            ctrlActive = false; // 用一次就松开
            mainHandler.post(() -> {
                View b = findViewById(R.id.key_ctrl);
                if (b != null) b.setBackgroundColor(0xFF3A3A3A);
            });
            return c;
        }

        @Override
        public boolean readAltKey() {
            return false;
        }

        @Override
        public boolean readShiftKey() {
            return false;
        }

        @Override
        public boolean readFnKey() {
            return false;
        }

        @Override
        public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
            return false;
        }

        @Override
        public void onEmulatorSet() {
            // 忽略
        }

        @Override
        public void logError(String tag, String message) { Log.e(tag, message); }

        @Override
        public void logWarn(String tag, String message) { Log.w(tag, message); }

        @Override
        public void logInfo(String tag, String message) { Log.i(tag, message); }

        @Override
        public void logDebug(String tag, String message) { Log.d(tag, message); }

        @Override
        public void logVerbose(String tag, String message) { Log.v(tag, message); }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.e(tag, "stack", e);
        }
    };
}
