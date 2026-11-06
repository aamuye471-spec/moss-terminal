package com.mossfield.terminal;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * FloatingWindowService：内置悬浮窗，一键把常用内容填进终端。
 *
 * v1.5：悬浮窗的小球变成 Clawd 螃蟹桌宠（clawd-on-badge 的 GIF，
 * 见 app/assets/clawd/ATTRIBUTION.md）。
 * 螃蟹用系统自带 Movie 播 GIF，按 ClawdPet 状态机切动画：
 * 点快捷按钮发提示 → 思考；终端有输出 → 打字；输出停 3 秒 → 开心；
 * 60 秒没动静 → 睡觉；空闲一阵 → 看书；长按螃蟹 → 迷你模式；
 * 快速连点 → 冒泡 / 不耐烦 / 双跳彩蛋。
 * 状态接不上时默认显示 idle 动画，不会卡住、不会崩。
 *
 * v1.7：螃蟹会动起来——空闲时在屏幕上慢慢溜达（CrabWander，
 * 每 100ms 走一小步，碰边反射，偶尔坐下歇会儿）；手指拖动时它用弹簧
 * 追着手指走（滞后 + 封顶，不瞬移）；双指捏合调大小（64–180dp，
 * 松手记住）；锁屏上也显示（FLAG_SHOW_WHEN_LOCKED），但锁屏上不展开面板。
 *
 * 点螃蟹展开面板：原来的快捷按钮一个不少（登录 / 体检 / 备份 /
 * 查流量 / 清缓存 / ★ API Key / 中断 / 关闭悬浮窗）。
 *
 * 需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限：MainActivity 里一键申请，
 * 并讲清楚是干什么的。没有权限时本服务直接停止，不闪退。
 *
 * 安全：所有自动填入的文字都只是终端输入，最终执行的 root 命令仍要过
 * RootBridge 黑名单 + settings.json deny，悬浮窗本身不绕过任何防线。
 * 锁屏显示不申请任何额外敏感权限（只用 FLAG_SHOW_WHEN_LOCKED，
 * 不解开锁屏、不读锁屏内容）。
 */
public class FloatingWindowService extends Service {

    private static final String TAG = "FloatingWindow";

    /** MainActivity 收到后打开 API Key 设置框。 */
    public static final String ACTION_OPEN_API_KEY = "com.mossfield.terminal.OPEN_API_KEY";

    /**
     * v1.7：悬浮窗的 Window 类型 flag。
     * FLAG_SHOW_WHEN_LOCKED 让它在锁屏上也显示（TYPE_APPLICATION_OVERLAY
     * 的窗口用这个 flag，目标 SDK 34 上仍生效；Activity 用的
     * setShowWhenLocked() 不适用于悬浮窗，所以这里不用它）。
     * 不加 FLAG_DISMISS_KEYGUARD——不解开锁屏，只显示螃蟹。
     */
    public static final int OVERLAY_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;

    /** v1.7：锁屏上能不能展开快捷面板（锁着就不行，只显示螃蟹）。 */
    public static boolean isPanelAllowed(boolean keyguardLocked) {
        return !keyguardLocked;
    }

    /** v1.7：捏合调好的宠物尺寸存在 SharedPreferences 里的键。 */
    static final String KEY_PET_SIZE_DP = "pet_size_dp";

    /** 快捷按钮：{填入终端的文字, 按钮上显示的字}。填入后自动加回车提交。 */
    public static final String[][] QUICK_ACTIONS = {
            {"/login", "登录"},
            {"手机体检一下", "体检"},
            {"备份微信聊天记录到 /sdcard", "备份"},
            {"查一下哪个应用在后台偷跑流量", "查流量"},
            {"清理一下手机缓存", "清缓存"},
    };

    /** v1.7：螃蟹尺寸走 CrabScale（默认 120dp，迷你 64dp，捏合 64–180dp）。 */
    /** 状态机每 500ms 推进一次（睡觉 / 看书 / 开心回 idle 都靠它）。 */
    private static final long TICK_MS = 500;
    /** v1.7：溜达每 100ms 推进一步（Handler 延时步进，不高频刷新）。 */
    private static final long MOVE_TICK_MS = 100;

    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    private WindowManager wm;
    private LinearLayout rootView;
    private ClawdPetView petView;
    private LinearLayout panel;
    private WindowManager.LayoutParams params;
    private boolean expanded = false;
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    /** v1.7：位置控制器（溜达 + 弹簧跟手）与尺寸控制器（状态缩放 + 捏合）。 */
    private CrabWander wander;
    private final CrabScale crabScale = new CrabScale();
    /** v1.7：用户捏合调好的尺寸（dp），存在 SharedPreferences 里，下次打开还用它。 */
    private int userSizeDp = CrabScale.PET_SIZE_DEFAULT_DP;
    private SharedPreferences prefs;
    /** v1.7：正在双指捏合（捏合时不走拖动、不触发点击）。 */
    private boolean pinching = false;
    private ScaleGestureDetector scaleDetector;
    private final Handler moveHandler = new Handler(Looper.getMainLooper());
    private final Runnable moveRunnable = new Runnable() {
        @Override
        public void run() {
            stepWander();
            moveHandler.postDelayed(this, MOVE_TICK_MS);
        }
    };
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            applyPetVisual(System.currentTimeMillis());
            tickHandler.postDelayed(this, TICK_MS);
        }
    };

    /** v1.7：每 tick 按状态机算好该显示什么、怎么画（状态 + 变换 + 缩放）。 */
    private void applyPetVisual(long now) {
        if (petView == null) return;
        ClawdPet pet = ClawdStateBus.getPet();
        ClawdPet.State display = pet.getDisplayState(now);
        // 溜达/跟手时配上对应动作：只有空闲系状态才换成 crabwalk，
        // 思考/打字/出错/睡觉这些工作状态不动（工作状态区分更明显）。
        if (wander != null && wander.isMoving() && ClawdPet.isIdleFamily(display)) {
            display = ClawdPet.State.MINI_CRABWALK;
        }
        petView.setPetState(display, pet.isMini());
        // 变换：状态机给的旋转/压扁/缩放目标 + 溜达的朝向/倾斜。
        ClawdPet.Transform t = pet.getDisplayTransform(now);
        float facing = (wander == null) ? 1f : wander.getFacingScaleX();
        float tilt = (wander == null) ? 0f : wander.getTiltDeg();
        petView.setTransform(t.rotationDeg + tilt, t.scaleX * facing, t.scaleY);
        // 状态缩放：目标值由状态定，300ms 平滑插值，不跳变。
        crabScale.setTarget(CrabScale.targetScaleFor(display, pet.isMini()), now);
        petView.setDrawScale(crabScale.update(now));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission, stopping");
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) {
            stopSelf();
            return;
        }
        prefs = getSharedPreferences("moss_terminal", MODE_PRIVATE);
        userSizeDp = CrabScale.clampPetSizeDp(
                prefs.getInt(KEY_PET_SIZE_DP, CrabScale.PET_SIZE_DEFAULT_DP));
        scaleDetector = new ScaleGestureDetector(this, scaleListener);
        buildView();
        try {
            wm.addView(rootView, params);
            running = true;
            tickHandler.post(tickRunnable);
            moveHandler.post(moveRunnable);
        } catch (Throwable t) {
            Log.e(TAG, "addView failed", t);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        tickHandler.removeCallbacks(tickRunnable);
        moveHandler.removeCallbacks(moveRunnable);
        if (wm != null && rootView != null) {
            try {
                wm.removeView(rootView);
            } catch (Exception ignored) {
            }
        }
        rootView = null;
        petView = null;
        super.onDestroy();
    }

    /** v1.7：溜达推进一步（每 100ms）。面板展开、睡觉、翻跟头时不动。 */
    private void stepWander() {
        if (wander == null || rootView == null) return;
        long now = System.currentTimeMillis();
        ClawdPet pet = ClawdStateBus.getPet();
        if (expanded) return; // 面板开着，别在按钮底下乱跑
        if (pet.isSomersaulting(now)) return; // 翻跟头时暂停溜达，恢复后再继续
        if (pet.getDisplayState(now) == ClawdPet.State.SLEEPING) return; // 睡着了不动
        wander.setPaused(false);
        if (wander.step(now)) {
            params.x = (int) wander.getX();
            params.y = (int) wander.getY();
            try {
                wm.updateViewLayout(rootView, params);
            } catch (Exception ignored) {
            }
            applyPetVisual(now);
        }
    }

    /** v1.7：当前是不是锁屏（KeyguardManager，不申请额外权限）。 */
    private boolean isKeyguardLocked() {
        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** v1.7：双指捏合监听：捏合调大小，松手记住尺寸。 */
    private final ScaleGestureDetector.SimpleOnScaleGestureListener scaleListener =
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    pinching = true;
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    userSizeDp = CrabScale.applyPinch(userSizeDp, detector.getScaleFactor());
                    applyPetSize();
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector detector) {
                    pinching = false;
                    if (prefs != null) {
                        prefs.edit().putInt(KEY_PET_SIZE_DP, userSizeDp).apply();
                    }
                }
            };

    /** v1.7：按当前尺寸（迷你 64dp / 正常=用户捏合的尺寸）重设螃蟹 View 大小。 */
    private void applyPetSize() {
        if (petView == null) return;
        int sizeDp = ClawdStateBus.getPet().isMini()
                ? CrabScale.PET_SIZE_MIN_DP : userSizeDp;
        petView.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        if (wander != null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            wander.setScreenBounds(dm.widthPixels, dm.heightPixels, dp(sizeDp));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private void buildView() {
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                OVERLAY_FLAGS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 300;

        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);

        // 螃蟹本体：点一下展开面板；长按切换迷你模式；快速连点有彩蛋。
        petView = new ClawdPetView(this);
        petView.setLayoutParams(new LinearLayout.LayoutParams(dp(userSizeDp), dp(userSizeDp)));
        petView.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            // v1.7 锁屏：只显示螃蟹，不展开面板；点按唤醒到应用内
            // （系统会先要求解锁，安全起见不在锁屏上直接操作）。
            if (!isPanelAllowed(isKeyguardLocked())) {
                try {
                    Intent i = new Intent(FloatingWindowService.this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) {
                }
                return;
            }
            ClawdPet pet = ClawdStateBus.getPet();
            pet.noteActivity(now);
            if (pet.isMini()) pet.onMiniPeek(now);
            boolean reacted = pet.onPoke(now);
            // v1.7：单点除了展开面板，偶尔还触发跳一下/转个圈/挥爪。
            if (!reacted) pet.onSingleTapReaction(now);
            toggleExpand();
        });
        petView.setOnLongClickListener(v -> {
            ClawdPet pet = ClawdStateBus.getPet();
            long now = System.currentTimeMillis();
            if (pet.isMini()) {
                pet.onExitMini(now);
                Toast.makeText(this, "螃蟹恢复正常大小", Toast.LENGTH_SHORT).show();
            } else {
                pet.onEnterMini(now);
                Toast.makeText(this, "迷你螃蟹：点一下探头，再长按恢复", Toast.LENGTH_SHORT).show();
            }
            // v1.7：迷你就是缩到最小（64dp），恢复就回到用户捏合的尺寸。
            applyPetSize();
            petView.setPetState(pet.getDisplayState(now), pet.isMini());
            return true;
        });
        rootView.addView(petView);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        for (String[] action : QUICK_ACTIONS) {
            Button b = new Button(this);
            b.setText(action[1]);
            b.setTextSize(14);
            final String text = action[0];
            b.setOnClickListener(v -> fillText(text));
            panel.addView(b);
        }
        // API Key：打开设置框（不把 key 打进终端）。放最前面、绿色高亮，
        // v1.4 要求：悬浮窗里也要有一键填 API Key，和主界面一样显眼。
        Button apiKey = new Button(this);
        apiKey.setText("★ API Key");
        apiKey.setTextSize(16);
        apiKey.setTextColor(0xFFFFFFFF);
        apiKey.setBackgroundColor(0xFF2E7D32);
        apiKey.setOnClickListener(v -> openApiKeyDialog());
        panel.addView(apiKey);
        // 中断：发 Ctrl+C
        Button interrupt = new Button(this);
        interrupt.setText("中断");
        interrupt.setTextSize(14);
        interrupt.setOnClickListener(v -> sendCtrlC());
        panel.addView(interrupt);
        // 关闭悬浮窗
        Button close = new Button(this);
        close.setText("关闭悬浮窗");
        close.setTextSize(14);
        close.setOnClickListener(v -> stopSelf());
        panel.addView(close);

        rootView.addView(panel);

        // v1.7 触摸：双指捏合调大小；单指拖动时螃蟹用弹簧追着手指走
        // （不硬贴在手指下，手指停下后它再滑到位；甩得快就跟不上、顿一下再追）。
        // 注意：悬浮窗只在碰到螃蟹本身时才收到触摸，不加全屏透明层，
        // 不会挡住其他应用的操作。
        rootView.setOnTouchListener(new View.OnTouchListener() {
            private float startTouchX, startTouchY;
            private boolean dragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                // 捏合优先：双指事件全交给 ScaleGestureDetector。
                scaleDetector.onTouchEvent(e);
                if (pinching || e.getPointerCount() >= 2) return true;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startTouchX = e.getRawX();
                        startTouchY = e.getRawY();
                        dragging = false;
                        if (wander != null) wander.startFollow(e.getRawX(), e.getRawY());
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - startTouchX);
                        int dy = (int) (e.getRawY() - startTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) dragging = true;
                        if (dragging && wander != null) {
                            // 只更新跟手目标，位置由 100ms 的弹簧步进慢慢追，
                            // 手指不停它就一直追，手指停下它再滑到位。
                            wander.updateFollowTarget(e.getRawX(), e.getRawY());
                            ClawdPet pet = ClawdStateBus.getPet();
                            if (pet.isMini()) pet.onMiniDrag(System.currentTimeMillis());
                            else pet.noteActivity(System.currentTimeMillis());
                        }
                        return dragging;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 松手：停在落点，过 2 秒恢复溜达。
                        if (wander != null && dragging) {
                            wander.endFollow(System.currentTimeMillis());
                        }
                        return dragging;
                    default:
                        return false;
                }
            }
        });

        // v1.7：初始化溜达器（屏幕尺寸 + 当前螃蟹大小，供越界反射用）。
        DisplayMetrics dm = getResources().getDisplayMetrics();
        wander = new CrabWander(params.x, params.y,
                dm.widthPixels, dm.heightPixels, dp(userSizeDp));
    }

    private void toggleExpand() {
        expanded = !expanded;
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    /** 把文字填进终端并回车提交。终端没启动时给提示，不崩。 */
    private void fillText(String text) {
        // 发出去的就是一条用户提示：螃蟹切到思考。
        ClawdStateBus.getPet().onUserPromptSubmit(System.currentTimeMillis());
        if (!SessionHolder.writeText(text + "\n")) {
            Toast.makeText(this, "终端还没启动，先打开 Moss Terminal", Toast.LENGTH_SHORT).show();
            return;
        }
        collapse();
    }

    private void sendCtrlC() {
        ClawdStateBus.getPet().onInterrupt(System.currentTimeMillis());
        if (!SessionHolder.writeBytes(new byte[]{0x03})) {
            Toast.makeText(this, "终端还没启动，先打开 Moss Terminal", Toast.LENGTH_SHORT).show();
            return;
        }
        collapse();
    }

    private void openApiKeyDialog() {
        if (!SessionHolder.isRunning()) {
            Toast.makeText(this, "终端还没启动，先打开 Moss Terminal", Toast.LENGTH_SHORT).show();
            return;
        }
        sendBroadcast(new Intent(ACTION_OPEN_API_KEY));
        collapse();
    }

    private void collapse() {
        expanded = false;
        panel.setVisibility(View.GONE);
    }
}
