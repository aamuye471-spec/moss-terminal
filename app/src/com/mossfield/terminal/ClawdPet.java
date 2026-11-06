package com.mossfield.terminal;

/**
 * ClawdPet：螃蟹桌宠的状态机（纯 Java，不依赖 Android，可在 JVM 上单测）。
 *
 * 移植自 clawd-on-badge（https://github.com/shdy0602-glitch/clawd-on-badge，
 * 提交 70af12e，见 app/assets/clawd/ATTRIBUTION.md）：
 * 只拿了 GIF 形象 + docs/guides/state-mapping.md 的事件→状态→GIF 映射，
 * 没有搬 Electron / Node 依赖。
 *
 * 状态 = 逻辑状态（logical）+ 短暂覆盖（transient）两层：
 * - 逻辑状态：idle / thinking / working / sleeping 等，由事件驱动；
 * - 短暂覆盖：happy / error / notification / 连点彩蛋等，几秒后自动回到逻辑状态。
 * 显示时永远取 {@link #getDisplayState(long)}，它已经把两层合并好了；
 * 拿不到事件时（比如状态接不上）默认就是 IDLE，不会卡住。
 *
 * 时间全部由调用方传入 nowMs（毫秒），不直接读系统时钟，方便测试。
 *
 * 注意：本 App 里 Claude Code 的真实 hook 事件是看不到的（Claude 跑在终端子进程里，
 * 没有把 hook 事件发给 App 的通道）。所以事件分两档：
 * - 真实事件：悬浮窗自己发出的（点快捷按钮 = 用户提交提示 → thinking；
 *   终端有输出 = 工具在跑 → typing；终端输出停了 3 秒 = 一轮结束 → happy；
 *   60 秒无操作 → sleeping），这些是实测可验证的；
 * - 预留 API：onToolUse(sessions) / onSubagentStart(count) / onToolFailure() /
 *   onPermissionRequest() / onPreCompact() / onWorktreeCreate() 等，
 *   严格按上游 state-mapping 表映射，留给以后接 Claude hook 时用，
 *   目前由状态机单测覆盖，不靠它们驱动日常显示。
 */
public class ClawdPet {

    /** 60 秒无操作 → 睡觉（上游表：60s mouse idle → sleeping）。 */
    public static final long SLEEP_AFTER_MS = 60_000;
    /** 空闲多久后随机切到看书（上游表：Idle random → idle-reading）。 */
    public static final long IDLE_READING_AFTER_MS = 25_000;
    /** 看书多久后切回普通 idle（来回轮换，别一直看书）。 */
    public static final long IDLE_READING_MS = 20_000;
    /** v1.7：刚进 idle 时先老实待着多久（纯 IDLE），再开始轮换动作。
     * 10 秒内回 idle 的地方（happy/通知结束等）看到的还是 plain IDLE，
     * 不会刚回 idle 就乱切动作。 */
    public static final long IDLE_GRACE_MS = 10_000;
    /** v1.7：空闲动作轮换表（每个动作持续 2–4 秒再换，不闪切）。
     * 顺序固定（可测）：看书 → 张望 → 挥手 → 伸懒腰 → 打盹 → 发呆。 */
    public static final State[] IDLE_ACTS = {
            State.IDLE_READING, State.LOOK_AROUND, State.WAVE,
            State.STRETCH, State.DOZE, State.IDLE,
    };
    /** 每个空闲动作的持续时间（毫秒），和 IDLE_ACTS 一一对应。 */
    public static final long[] IDLE_ACT_DURATIONS_MS = {3000, 2500, 3500, 2000, 3000, 3000};
    /** v1.7：翻跟头三段时长（毫秒）：翻 1.2s → 晕 1.8s → 爬起来 1s，共 4s。 */
    public static final long SOMERSAULT_FLIP_MS = 1200;
    public static final long SOMERSAULT_DIZZY_MS = 1800;
    public static final long SOMERSAULT_RECOVER_MS = 1000;
    public static final long SOMERSAULT_TOTAL_MS =
            SOMERSAULT_FLIP_MS + SOMERSAULT_DIZZY_MS + SOMERSAULT_RECOVER_MS;
    /** v1.7：空闲满几轮动作轮换后，自动来一次翻跟头（概率低，别太频繁）。 */
    public static final int AUTO_SOMERSAULT_CYCLES = 8;
    /** 工具输出停了多久算一轮结束 → happy。 */
    public static final long QUIET_BEFORE_HAPPY_MS = 3_000;

    /** 短暂状态持续时间（毫秒）。 */
    public static final long HAPPY_MS = 4_000;
    public static final long ERROR_MS = 5_000;
    public static final long NOTIFICATION_MS = 6_000;
    public static final long SWEEPING_MS = 5_000;
    public static final long CARRYING_MS = 5_000;
    public static final long REACTION_MS = 3_000;
    public static final long MINI_ENTER_MS = 2_500;
    public static final long MINI_PEEK_MS = 2_000;

    /** 状态 → GIF 文件名（assets/clawd/ 下）。 */
    public enum State {
        IDLE("clawd-idle.gif"),
        IDLE_READING("clawd-idle-reading.gif"),
        THINKING("clawd-thinking.gif"),
        WORKING_TYPING("clawd-typing.gif"),
        WORKING_GROOVE("clawd-headphones-groove.gif"),
        WORKING_BUILDING("clawd-building.gif"),
        JUGGLING("clawd-juggling.gif"),
        ERROR("clawd-error.gif"),
        DEBUGGER("clawd-debugger.gif"),
        HAPPY("clawd-happy.gif"),
        NOTIFICATION("clawd-notification.gif"),
        SWEEPING("clawd-sweeping.gif"),
        CARRYING("clawd-carrying.gif"),
        SLEEPING("clawd-sleeping.gif"),
        CONDUCTING("clawd-conducting.gif"),
        BUBBLE("clawd-bubble.gif"),
        REACT_ANNOYED("clawd-react-annoyed.gif"),
        REACT_DOUBLE_JUMP("clawd-react-double-jump.gif"),
        MINI_IDLE("clawd-mini-idle.gif"),
        MINI_PEEK("clawd-mini-peek.gif"),
        MINI_HAPPY("clawd-mini-happy.gif"),
        MINI_ALERT("clawd-mini-alert.gif"),
        MINI_CRABWALK("clawd-mini-crabwalk.gif"),
        MINI_ENTER("clawd-mini-enter.gif"),
        /** v1.7：空闲动作（复用已有 GIF，不新加素材）。 */
        LOOK_AROUND("clawd-idle.gif"),
        DOZE("clawd-idle.gif"),
        WAVE("clawd-conducting.gif"),
        STRETCH("clawd-happy.gif"),
        /** v1.7：转圈（复用 idle GIF，靠旋转变换表现）。 */
        SPIN("clawd-idle.gif"),
        /** v1.7：翻跟头（复用 double-jump GIF，靠旋转变换表现）。 */
        SOMERSAULT("clawd-react-double-jump.gif");

        public final String gif;

        State(String gif) {
            this.gif = gif;
        }
    }

    /** v1.7：翻跟头所处的阶段（供服务层暂停溜达、忽略点按）。 */
    public enum SomersaultPhase {
        NONE, FLIP, DIZZY, RECOVER
    }

    /**
     * v1.7：显示变换（View 层按这个画，不新加素材）。
     * rotationDeg 旋转（度，绕中心），scaleX/scaleY 方向缩放（翻面/压扁），
     * scale 整体缩放（CrabScale 再做平滑插值，这里给的是目标值）。
     */
    public static class Transform {
        public final float rotationDeg;
        public final float scaleX;
        public final float scaleY;
        public final float scale;

        public Transform(float rotationDeg, float scaleX, float scaleY, float scale) {
            this.rotationDeg = rotationDeg;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.scale = scale;
        }

        public static Transform identity() {
            return new Transform(0f, 1f, 1f, 1f);
        }
    }

    private State logical = State.IDLE;
    private long logicalSinceMs = 0;
    private State transientState = null;
    private long transientUntilMs = 0;
    /** v1.7：当前短暂状态的总时长（算变换进度用）。 */
    private long transientDurationMs = 0;
    private long lastActivityMs = 0;
    private long lastOutputMs = -1;
    private int consecutiveFailures = 0;
    private boolean mini = false;
    /** 连点计数：1.5 秒内的点按次数（彩蛋用）。 */
    private int pokeCount = 0;
    private long lastPokeMs = -1;
    /** v1.7：空闲动作轮换：当前是第几个动作、从什么时候开始的。 */
    private int idleActIndex = 0;
    private long idleActSinceMs = 0;
    /** v1.7：已完整跑完几轮空闲动作（满 8 轮自动翻一次跟头）。 */
    private int idleCycles = 0;
    /** v1.7：单点反应计数（每 3 次单点换一个反应：跳 → 转圈 → 挥爪）。 */
    private int singleTapCount = 0;
    /** v1.7：连点彩蛋里第几次双跳（每 3 次双跳换成一次翻跟头）。 */
    private int doubleJumpCount = 0;
    /** v1.7：翻跟头开始时间（毫秒）；-1 表示没在翻。 */
    private long somersaultStartMs = -1;

    public ClawdPet() {
    }

    /** 逻辑状态（不含短暂覆盖）。 */
    public State getLogicalState() {
        return logical;
    }

    /** v1.7：是不是空闲系的显示状态（发呆/看书/张望/挥手/伸懒腰/打盹）。 */
    public static boolean isIdleFamily(State s) {
        return s == State.IDLE || s == State.IDLE_READING || s == State.LOOK_AROUND
                || s == State.DOZE || s == State.WAVE || s == State.STRETCH;
    }

    /** 当前该显示的状态（短暂覆盖优先，过期自动失效）。 */
    public State getDisplayState(long nowMs) {
        tick(nowMs);
        if (isSomersaulting(nowMs)) return State.SOMERSAULT;
        if (transientState != null) return transientState;
        if (logical == State.IDLE) {
            // 刚进 idle 的 10 秒内老实待着（plain IDLE），之后才轮换动作。
            if (nowMs - logicalSinceMs < IDLE_GRACE_MS) return State.IDLE;
            return currentIdleAct();
        }
        return logical;
    }

    /** v1.7：当前空闲动作（宽限期内就是 plain IDLE，之后按表轮换）。 */
    public State currentIdleAct() {
        return IDLE_ACTS[idleActIndex % IDLE_ACTS.length];
    }

    /** v1.7：翻跟头所处的阶段。 */
    public SomersaultPhase getSomersaultPhase(long nowMs) {
        if (somersaultStartMs < 0) return SomersaultPhase.NONE;
        long elapsed = nowMs - somersaultStartMs;
        if (elapsed < 0 || elapsed >= SOMERSAULT_TOTAL_MS) return SomersaultPhase.NONE;
        if (elapsed < SOMERSAULT_FLIP_MS) return SomersaultPhase.FLIP;
        if (elapsed < SOMERSAULT_FLIP_MS + SOMERSAULT_DIZZY_MS) return SomersaultPhase.DIZZY;
        return SomersaultPhase.RECOVER;
    }

    /** v1.7：是不是正在翻跟头（翻/晕/爬起来任一阶段）。 */
    public boolean isSomersaulting(long nowMs) {
        return getSomersaultPhase(nowMs) != SomersaultPhase.NONE;
    }

    /** v1.7：是不是正晕着（躺平阶段，点按没反应）。 */
    public boolean isDizzy(long nowMs) {
        return getSomersaultPhase(nowMs) == SomersaultPhase.DIZZY;
    }

    /**
     * v1.7：当前该显示的变换（旋转/翻面/压扁/缩放目标）。
     * 旋转类是时间的连续函数，本身就是平滑的；scale 是目标值，
     * 由 CrabScale 再做 300ms 平滑插值，不跳变。
     */
    public Transform getDisplayTransform(long nowMs) {
        State display = getDisplayState(nowMs);
        long elapsed = stateElapsedMs(display, nowMs);
        switch (display) {
            case LOOK_AROUND:
                // 左右张望：缓慢左右摆头（2.4 秒一个来回）。
                return new Transform(
                        (float) (12 * Math.sin(2 * Math.PI * elapsed / 2400.0)), 1f, 1f, 1f);
            case DOZE:
                // 打盹点头：慢速点头 + 微微缩一点。
                return new Transform(
                        (float) (7 * Math.sin(2 * Math.PI * elapsed / 1800.0)), 1f, 0.97f, 1f);
            case SPIN:
                // 转圈：0.9 秒转满一圈。
                return new Transform(
                        360f * Math.min(1f, elapsed / 900f), 1f, 1f, 1f);
            case REACT_DOUBLE_JUMP:
                // 跳一下：1.2 秒内缩放脉冲（变大再回来）。
                return new Transform(0f, 1f, 1f,
                        1f + (float) (0.2 * Math.sin(Math.PI * Math.min(1.0, elapsed / 1200.0))));
            case HAPPY:
            case STRETCH:
                // 开心/伸懒腰：长大一点（目标 1.12，CrabScale 平滑过渡）。
                return new Transform(0f, 1f, 1f, 1.12f);
            case ERROR:
                // 出错：快速小幅抖动（0.4 秒一个来回）。
                return new Transform(
                        (float) (5 * Math.sin(2 * Math.PI * elapsed / 400.0)), 1f, 1f, 1f);
            case THINKING:
                // 思考：轻微起伏。
                return new Transform(0f, 1f,
                        1f + (float) (0.02 * Math.sin(2 * Math.PI * elapsed / 1200.0)), 1f);
            case SLEEPING:
                // 睡觉：缩一点。
                return new Transform(0f, 1f, 1f, 0.85f);
            case SOMERSAULT: {
                // 翻跟头：翻（0→360°）→ 晕（躺平 90° + 压扁）→ 爬起来（90°→0°）。
                SomersaultPhase phase = getSomersaultPhase(nowMs);
                if (phase == SomersaultPhase.FLIP) {
                    return new Transform(360f * (elapsed / (float) SOMERSAULT_FLIP_MS), 1f, 1f, 1f);
                }
                if (phase == SomersaultPhase.DIZZY) {
                    return new Transform(90f, 1f, 0.7f, 1f);
                }
                if (phase == SomersaultPhase.RECOVER) {
                    long recoverElapsed = elapsed - SOMERSAULT_FLIP_MS - SOMERSAULT_DIZZY_MS;
                    float t = Math.min(1f, recoverElapsed / (float) SOMERSAULT_RECOVER_MS);
                    return new Transform(90f * (1f - t), 1f, 0.7f + 0.3f * t, 1f);
                }
                return Transform.identity();
            }
            default:
                return Transform.identity();
        }
    }

    /** 当前显示状态已经持续了多久（毫秒），供变换进度用。 */
    private long stateElapsedMs(State display, long nowMs) {
        if (display == State.SOMERSAULT && somersaultStartMs >= 0) {
            return Math.max(0, nowMs - somersaultStartMs);
        }
        if (transientState != null && display == transientState) {
            return Math.max(0, transientDurationMs - (transientUntilMs - nowMs));
        }
        if (logical == State.IDLE && isIdleFamily(display)) {
            // 宽限期内的 IDLE 从进 idle 起算，轮换中的动作从动作开始起算。
            if (display == State.IDLE && nowMs - logicalSinceMs < IDLE_GRACE_MS) {
                return Math.max(0, nowMs - logicalSinceMs);
            }
            return Math.max(0, nowMs - idleActSinceMs);
        }
        return Math.max(0, nowMs - logicalSinceMs);
    }

    /** 当前该显示的 GIF 文件名（已按迷你模式换算）。 */
    public String getDisplayGif(long nowMs) {
        return gifFor(getDisplayState(nowMs), mini);
    }

    /**
     * 状态 → GIF（含迷你模式换算）。
     * 迷你模式只有 6 张 GIF：默认 mini-idle，完成 mini-happy，提醒 mini-alert，
     * 其余逻辑状态在迷你模式下都显示 mini-idle（上游 mini 表只有这 4 种触发）。
     */
    public static String gifFor(State state, boolean miniMode) {
        if (state == null) return State.IDLE.gif;
        if (state.name().startsWith("MINI_")) return state.gif;
        if (!miniMode) return state.gif;
        switch (state) {
            case HAPPY:
            case WAVE:
            case STRETCH:
                return State.MINI_HAPPY.gif;
            case NOTIFICATION:
                return State.MINI_ALERT.gif;
            default:
                return State.MINI_IDLE.gif;
        }
    }

    public boolean isMini() {
        return mini;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    // ---------- 事件（严格按上游 state-mapping 表） ----------

    /** 用户提交提示 → thinking。 */
    public void onUserPromptSubmit(long nowMs) {
        noteActivity(nowMs);
        setLogical(State.THINKING, nowMs);
    }

    /**
     * 工具运行：1 会话 → typing；2 会话 → headphones-groove；3+ 会话 → building。
     * 上游表 PreToolUse / PostToolUse 分档。
     */
    public void onToolUse(int sessions, long nowMs) {
        noteActivity(nowMs);
        if (sessions >= 3) setLogical(State.WORKING_BUILDING, nowMs);
        else if (sessions == 2) setLogical(State.WORKING_GROOVE, nowMs);
        else setLogical(State.WORKING_TYPING, nowMs);
    }

    /**
     * 子代理启动：1 个 → headphones-groove；2+ 个 → juggling（三球杂耍）。
     * 上游表 SubagentStart 分档。
     */
    public void onSubagentStart(int count, long nowMs) {
        noteActivity(nowMs);
        if (count >= 2) setLogical(State.JUGGLING, nowMs);
        else setLogical(State.WORKING_GROOVE, nowMs);
    }

    /** 子代理结束 → 回到 working（上游 Kimi 表 SubagentStop → working）。 */
    public void onSubagentStop(long nowMs) {
        noteActivity(nowMs);
        setLogical(State.WORKING_TYPING, nowMs);
    }

    /**
     * 工具失败 → error；连续第 2 次失败 → debugger（加分项：上游有这张 GIF，
     * 主映射只写了 error，连续失败切 debugger 更看得出在查问题）。
     */
    public void onToolFailure(long nowMs) {
        noteActivity(nowMs);
        consecutiveFailures++;
        if (consecutiveFailures >= 2) {
            setTransient(State.DEBUGGER, ERROR_MS, nowMs);
            consecutiveFailures = 0;
        } else {
            setTransient(State.ERROR, ERROR_MS, nowMs);
        }
    }

    /** 结束 / 压缩后 → happy（上游表 Stop / PostCompact → attention/happy）。 */
    public void onStop(long nowMs) {
        noteActivity(nowMs);
        consecutiveFailures = 0;
        setTransient(State.HAPPY, HAPPY_MS, nowMs);
        setLogical(State.IDLE, nowMs);
    }

    /** 权限请求 → notification（上游表 PermissionRequest）。 */
    public void onPermissionRequest(long nowMs) {
        noteActivity(nowMs);
        setTransient(State.NOTIFICATION, NOTIFICATION_MS, nowMs);
    }

    /** 权限请求已处理 → 回 idle。 */
    public void onPermissionResolved(long nowMs) {
        transientState = null;
        transientUntilMs = 0;
        setLogical(State.IDLE, nowMs);
    }

    /** 压缩前 → sweeping（上游表 PreCompact）。 */
    public void onPreCompact(long nowMs) {
        noteActivity(nowMs);
        setTransient(State.SWEEPING, SWEEPING_MS, nowMs);
    }

    /** 建 worktree → carrying（上游表 WorktreeCreate）。 */
    public void onWorktreeCreate(long nowMs) {
        noteActivity(nowMs);
        setTransient(State.CARRYING, CARRYING_MS, nowMs);
    }

    /** 会话结束 → 回 idle（上游表 SessionEnd：没有活会话就 idle）。 */
    public void onSessionEnd(long nowMs) {
        consecutiveFailures = 0;
        transientState = null;
        transientUntilMs = 0;
        setLogical(State.IDLE, nowMs);
    }

    /** 会话开始 → idle（上游表 SessionStart → idle）。 */
    public void onSessionStart(long nowMs) {
        onSessionEnd(nowMs);
    }

    /** 中断（Ctrl+C）→ 取消当前短暂状态，回 idle。 */
    public void onInterrupt(long nowMs) {
        noteActivity(nowMs);
        consecutiveFailures = 0;
        transientState = null;
        transientUntilMs = 0;
        setLogical(State.IDLE, nowMs);
    }

    /**
     * 终端有输出（本 App 唯一能实测到的 Claude 活动信号）。
     * 空闲/睡觉/思考时收到输出 → 正在打字；已在 working/juggling 等状态时不动；
     * error / notification 等短暂提醒不被输出打断。
     */
    public void onTerminalOutput(long nowMs) {
        noteActivity(nowMs);
        lastOutputMs = nowMs;
        if (transientState != null) return;
        switch (logical) {
            case IDLE:
            case IDLE_READING:
            case SLEEPING:
            case THINKING:
                setLogical(State.WORKING_TYPING, nowMs);
                break;
            default:
                break;
        }
    }

    /** 任何用户操作（点螃蟹、拖动、填快捷命令）都算活动：睡着了就醒。 */
    public void noteActivity(long nowMs) {
        lastActivityMs = nowMs;
        if (logical == State.SLEEPING) setLogical(State.IDLE, nowMs);
    }

    // ---------- 迷你模式（上游 Mini Mode 表） ----------

    /** 进入迷你模式：播 mini-enter，随后默认 mini-idle。 */
    public void onEnterMini(long nowMs) {
        mini = true;
        setTransient(State.MINI_ENTER, MINI_ENTER_MS, nowMs);
    }

    /** 退出迷你模式：回普通 idle。 */
    public void onExitMini(long nowMs) {
        mini = false;
        transientState = null;
        transientUntilMs = 0;
        setLogical(State.IDLE, nowMs);
    }

    /** 迷你模式点一下：探头（mini-peek，上游 Mini 表 Hover → Peek）。 */
    public void onMiniPeek(long nowMs) {
        if (!mini) return;
        noteActivity(nowMs);
        setTransient(State.MINI_PEEK, MINI_PEEK_MS, nowMs);
    }

    /** 迷你模式拖动：横着走（mini-crabwalk），每次移动刷新 1.5 秒。 */
    public void onMiniDrag(long nowMs) {
        if (!mini) return;
        noteActivity(nowMs);
        setTransient(State.MINI_CRABWALK, 1500, nowMs);
    }

    /**
     * 连点彩蛋（上游 Click Reactions：双击、快速连点、反复戳有隐藏反应）。
     * 1.5 秒内第 1 下是正常点按（返回 false，不抢显示）；
     * 第 2 下冒泡、第 3 下不耐烦、第 4 下双跳，之后重置。
     * v1.7：翻跟头期间点按没反应（它在晕）；每 3 次双跳换成一次翻跟头。
     * 调用方（悬浮窗）只在返回 true 时才算彩蛋触发。
     */
    public boolean onPoke(long nowMs) {
        if (isSomersaulting(nowMs)) return false; // 晕着呢，别戳它
        noteActivity(nowMs);
        if (lastPokeMs < 0 || nowMs - lastPokeMs > 1500) pokeCount = 0;
        lastPokeMs = nowMs;
        pokeCount++;
        State reaction;
        switch (pokeCount) {
            case 2:
                reaction = State.BUBBLE;
                break;
            case 3:
                reaction = State.REACT_ANNOYED;
                break;
            case 4:
                pokeCount = 0;
                doubleJumpCount++;
                if (doubleJumpCount % 3 == 0) {
                    startSomersault(nowMs);
                    return true;
                }
                reaction = State.REACT_DOUBLE_JUMP;
                break;
            default:
                return false;
        }
        setTransient(reaction, REACTION_MS, nowMs);
        return true;
    }

    /**
     * v1.7：单点反应（不是连点时才走这里）。
     * 每 3 次单点换一个反应：跳一下 → 转个圈 → 挥爪，循环。
     * 返回 true 表示触发了反应（调用方不用再展开面板之外的事）。
     * 翻跟头期间同样没反应。
     */
    public boolean onSingleTapReaction(long nowMs) {
        if (isSomersaulting(nowMs)) return false;
        noteActivity(nowMs);
        singleTapCount++;
        if (singleTapCount % 3 != 0) return false;
        State reaction;
        switch ((singleTapCount / 3 - 1) % 3) {
            case 0:
                reaction = State.REACT_DOUBLE_JUMP;
                break;
            case 1:
                reaction = State.SPIN;
                break;
            default:
                reaction = State.WAVE;
                break;
        }
        setTransient(reaction, REACTION_MS, nowMs);
        return true;
    }

    /** v1.7：开始翻跟头（翻 → 晕 → 爬起来，共 4 秒）。 */
    public void startSomersault(long nowMs) {
        somersaultStartMs = nowMs;
    }

    /** 上游已退役的指挥动作（conducting）：保留接口，不接入主映射。 */
    public void onConducting(long nowMs) {
        noteActivity(nowMs);
        setLogical(State.CONDUCTING, nowMs);
    }

    // ---------- 时间推进（由调用方每 ~500ms 调一次） ----------

    /**
     * 按当前时间推进状态：
     * - 短暂状态到期 → 回到逻辑状态；
     * - working 时输出停了 3 秒 → 一轮结束，happy 4 秒；
     * - 60 秒无任何活动 → 睡觉（权限提醒等着用户时不睡），睡觉优先于看书；
     * - v1.7：空闲动作轮换——进 idle 10 秒后开始按表轮换，每个动作 2–4 秒；
     *   每跑满 8 轮动作，自动翻一次跟头（概率低，别太频繁）；
     * - v1.7：翻跟头跑完（4 秒）自动结束，回到原来的逻辑状态。
     * 注意：v1.5 的“25 秒 → 看书、20 秒后回 idle”已并入轮换表
     * （看书仍是轮换的第一个动作，只是出现得更早、还会轮换别的动作）。
     */
    public void tick(long nowMs) {
        if (somersaultStartMs >= 0 && nowMs - somersaultStartMs >= SOMERSAULT_TOTAL_MS) {
            somersaultStartMs = -1;
        }
        if (transientState != null && nowMs >= transientUntilMs) {
            transientState = null;
            transientUntilMs = 0;
            transientDurationMs = 0;
        }
        if (transientState != null) return;
        if (isSomersaulting(nowMs)) return;
        if (logical == State.WORKING_TYPING && lastOutputMs >= 0
                && nowMs - lastOutputMs >= QUIET_BEFORE_HAPPY_MS) {
            setTransient(State.HAPPY, HAPPY_MS, nowMs);
            setLogical(State.IDLE, nowMs);
            return;
        }
        // 睡觉优先于看书：60 秒没动静就睡，别被看书轮换拖住。
        if (logical != State.SLEEPING && lastActivityMs > 0
                && nowMs - lastActivityMs >= SLEEP_AFTER_MS) {
            setLogical(State.SLEEPING, nowMs);
            return;
        }
        // 空闲动作轮换：宽限期过后，按表一个一个换，每个动作 2–4 秒。
        if (logical == State.IDLE && nowMs - logicalSinceMs >= IDLE_GRACE_MS) {
            // 轮换从宽限期结束那一刻起算（构造时 idleActSinceMs=0，要先对齐，
            // 否则第一个动作会被判定为已超时、瞬间跳过）。
            if (idleActSinceMs < logicalSinceMs + IDLE_GRACE_MS) {
                idleActSinceMs = logicalSinceMs + IDLE_GRACE_MS;
            }
            long actDuration = IDLE_ACT_DURATIONS_MS[idleActIndex % IDLE_ACT_DURATIONS_MS.length];
            if (nowMs - idleActSinceMs >= actDuration) {
                idleActIndex++;
                idleActSinceMs = nowMs;
                if (idleActIndex % IDLE_ACTS.length == 0) {
                    idleCycles++;
                    if (idleCycles >= AUTO_SOMERSAULT_CYCLES) {
                        idleCycles = 0;
                        startSomersault(nowMs);
                    }
                }
            }
        }
    }

    private void setLogical(State s, long nowMs) {
        if (logical != s) {
            logicalSinceMs = nowMs;
        }
        if (s == State.IDLE) {
            // 进 idle（或明确重置回 idle，比如会话结束/中断/权限处理完）时：
            // 动作轮换从头开始（先是 10 秒宽限期，再轮换），轮数也清零。
            // 这样会话结束那一刻看到的总是 plain IDLE，不会正在挥手/看书。
            logicalSinceMs = nowMs;
            idleActIndex = 0;
            idleActSinceMs = nowMs + IDLE_GRACE_MS;
            idleCycles = 0;
        }
        logical = s;
    }

    private void setTransient(State s, long durationMs, long nowMs) {
        transientState = s;
        transientDurationMs = durationMs;
        transientUntilMs = nowMs + durationMs;
    }
}
