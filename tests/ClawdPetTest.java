import com.mossfield.terminal.ClawdPet;

/**
 * ClawdPet 状态机单元测试（JVM 上跑，ClawdPet 是纯 Java，不依赖 Android）。
 * 覆盖上游 docs/guides/state-mapping.md 的每一行映射，外加
 * tick 推进（睡觉 / 看书 / 输出停→开心）、迷你模式换算、连点彩蛋。
 */
public class ClawdPetTest {
    static int passed = 0, failed = 0;

    static void expect(String name, Object got, Object want) {
        if (want.equals(got)) {
            System.out.println("PASS: " + name + " -> " + got);
            passed++;
        } else {
            System.out.println("FAIL: " + name + " got=" + got + " want=" + want);
            failed++;
        }
    }

    static void expectTrue(String name, boolean cond) {
        expect(name, cond, true);
    }

    public static void main(String[] args) {
        // 默认：idle
        ClawdPet p = new ClawdPet();
        expect("default state", p.getDisplayState(0), ClawdPet.State.IDLE);
        expect("default gif", p.getDisplayGif(0), "clawd-idle.gif");
        expect("gifFor null -> idle", ClawdPet.gifFor(null, false), "clawd-idle.gif");

        // 用户提交提示 → thinking
        p.onUserPromptSubmit(1000);
        expect("prompt -> thinking", p.getDisplayState(1000), ClawdPet.State.THINKING);

        // 工具运行分档：1 → typing，2 → groove，3+ → building
        p.onToolUse(1, 2000);
        expect("tool 1 session -> typing", p.getDisplayState(2000), ClawdPet.State.WORKING_TYPING);
        p.onToolUse(2, 3000);
        expect("tool 2 sessions -> groove", p.getDisplayState(3000), ClawdPet.State.WORKING_GROOVE);
        p.onToolUse(3, 4000);
        expect("tool 3 sessions -> building", p.getDisplayState(4000), ClawdPet.State.WORKING_BUILDING);
        p.onToolUse(5, 5000);
        expect("tool 5 sessions -> building", p.getDisplayState(5000), ClawdPet.State.WORKING_BUILDING);
        p.onToolUse(0, 6000);
        expect("tool 0 sessions -> typing", p.getDisplayState(6000), ClawdPet.State.WORKING_TYPING);

        // 子代理分档：1 → groove，2+ → juggling；结束 → typing
        p.onSubagentStart(1, 7000);
        expect("subagent 1 -> groove", p.getDisplayState(7000), ClawdPet.State.WORKING_GROOVE);
        p.onSubagentStart(2, 8000);
        expect("subagent 2 -> juggling", p.getDisplayState(8000), ClawdPet.State.JUGGLING);
        p.onSubagentStart(4, 9000);
        expect("subagent 4 -> juggling", p.getDisplayState(9000), ClawdPet.State.JUGGLING);
        p.onSubagentStop(10000);
        expect("subagent stop -> typing", p.getDisplayState(10000), ClawdPet.State.WORKING_TYPING);

        // 工具失败：第 1 次 error，第 2 次 debugger；结束 → happy
        p.onToolFailure(11000);
        expect("failure 1 -> error", p.getDisplayState(11000), ClawdPet.State.ERROR);
        expect("failure count 1", p.getConsecutiveFailures(), 1);
        p.onToolFailure(12000);
        expect("failure 2 -> debugger", p.getDisplayState(12000), ClawdPet.State.DEBUGGER);
        expect("failure count reset", p.getConsecutiveFailures(), 0);
        p.onStop(13000);
        expect("stop -> happy", p.getDisplayState(13000), ClawdPet.State.HAPPY);
        expect("happy expires -> idle", p.getDisplayState(13000 + ClawdPet.HAPPY_MS + 1),
                ClawdPet.State.IDLE);

        // 权限请求 → notification，处理后回 idle；6 秒后也自动回 idle
        p.onPermissionRequest(20000);
        expect("permission -> notification", p.getDisplayState(20000), ClawdPet.State.NOTIFICATION);
        p.onPermissionResolved(21000);
        expect("permission resolved -> idle", p.getDisplayState(21000), ClawdPet.State.IDLE);
        p.onPermissionRequest(22000);
        expect("notification expires -> idle-family",
                ClawdPet.isIdleFamily(p.getDisplayState(22000 + ClawdPet.NOTIFICATION_MS + 1)),
                true);

        // 压缩前 → sweeping；建 worktree → carrying；会话结束 → idle
        p.onPreCompact(30000);
        expect("preCompact -> sweeping", p.getDisplayState(30000), ClawdPet.State.SWEEPING);
        p.onWorktreeCreate(31000);
        expect("worktree -> carrying", p.getDisplayState(31000), ClawdPet.State.CARRYING);
        p.onSessionEnd(32000);
        expect("session end -> idle", p.getDisplayState(32000), ClawdPet.State.IDLE);

        // 中断 → idle
        p.onToolUse(1, 33000);
        p.onInterrupt(34000);
        expect("interrupt -> idle", p.getDisplayState(34000), ClawdPet.State.IDLE);

        // 终端输出驱动：idle + 输出 → typing；输出停 3 秒 → happy → idle
        ClawdPet q = new ClawdPet();
        q.onTerminalOutput(1000);
        expect("output idle -> typing", q.getDisplayState(1000), ClawdPet.State.WORKING_TYPING);
        q.onTerminalOutput(2000);
        expect("output still typing", q.getDisplayState(2000), ClawdPet.State.WORKING_TYPING);
        expect("quiet 2.9s still typing", q.getDisplayState(4900), ClawdPet.State.WORKING_TYPING);
        expect("quiet 3s -> happy", q.getDisplayState(5000), ClawdPet.State.HAPPY);
        expect("happy then idle", q.getDisplayState(5000 + ClawdPet.HAPPY_MS + 1),
                ClawdPet.State.IDLE);

        // 60 秒无操作 → 睡觉；有活动就醒（59 秒时已在看书，60 秒准时睡）
        ClawdPet s = new ClawdPet();
        s.onUserPromptSubmit(1000);
        s.onSessionStart(1000);
        s.noteActivity(1000);
        expect("59s idle-family, not yet sleeping",
                ClawdPet.isIdleFamily(s.getDisplayState(1000 + 59000)), true);
        expect("60s -> sleeping", s.getDisplayState(1000 + 60000), ClawdPet.State.SLEEPING);
        s.noteActivity(1000 + 61000);
        expect("activity wakes", s.getDisplayState(1000 + 61000), ClawdPet.State.IDLE);
        // 睡觉时终端输出也算活动，醒了直接打字
        expect("sleep again after 60s", s.getDisplayState(1000 + 61000 + 60000),
                ClawdPet.State.SLEEPING);
        s.onTerminalOutput(1000 + 61000 + 61000);
        expect("output wakes to typing", s.getDisplayState(1000 + 61000 + 61000),
                ClawdPet.State.WORKING_TYPING);

        // v1.7 空闲轮换：10 秒宽限期后按表轮换（看书/张望/挥手/伸懒腰/打盹/发呆），
        // 每个动作 2–4 秒。v1.5 的“25 秒 → 看书、20 秒后回 idle”已并入轮换表
        // （看书仍是轮换的第一个动作，只是出现得更早、还会轮换别的动作）。
        // 详细轮换断言见 ClawdPetActsTest；这里只验宽限期和轮换起点。
        ClawdPet r = new ClawdPet();
        r.noteActivity(1000);
        expect("9s still idle (grace)", r.getDisplayState(9000), ClawdPet.State.IDLE);
        expect("10s reading first", r.getDisplayState(10000), ClawdPet.State.IDLE_READING);
        expect("25s rotating", ClawdPet.isIdleFamily(r.getDisplayState(1000 + 25000)), true);
        expect("45s rotating", ClawdPet.isIdleFamily(r.getDisplayState(1000 + 45000)), true);

        // 迷你模式换算
        expect("mini happy", ClawdPet.gifFor(ClawdPet.State.HAPPY, true), "clawd-mini-happy.gif");
        expect("mini notification", ClawdPet.gifFor(ClawdPet.State.NOTIFICATION, true),
                "clawd-mini-alert.gif");
        expect("mini typing -> mini-idle", ClawdPet.gifFor(ClawdPet.State.WORKING_TYPING, true),
                "clawd-mini-idle.gif");
        expect("mini sleeping -> mini-idle", ClawdPet.gifFor(ClawdPet.State.SLEEPING, true),
                "clawd-mini-idle.gif");
        expect("normal happy", ClawdPet.gifFor(ClawdPet.State.HAPPY, false), "clawd-happy.gif");
        ClawdPet m = new ClawdPet();
        m.onEnterMini(1000);
        expect("enter mini -> mini-enter", m.getDisplayState(1000), ClawdPet.State.MINI_ENTER);
        expect("enter mini gif", m.getDisplayGif(1000), "clawd-mini-enter.gif");
        expect("mini-enter expires -> mini-idle gif",
                m.getDisplayGif(1000 + ClawdPet.MINI_ENTER_MS + 1), "clawd-mini-idle.gif");
        m.onMiniPeek(5000);
        expect("mini peek", m.getDisplayState(5000), ClawdPet.State.MINI_PEEK);
        m.onMiniDrag(6000);
        expect("mini drag -> crabwalk", m.getDisplayState(6000), ClawdPet.State.MINI_CRABWALK);
        m.onExitMini(7000);
        expect("exit mini -> idle", m.getDisplayState(7000), ClawdPet.State.IDLE);
        expectTrue("exit mini flag", !m.isMini());
        // 非迷你模式下 peek / drag 不生效
        ClawdPet m2 = new ClawdPet();
        m2.onMiniPeek(1000);
        expect("peek ignored when not mini", m2.getDisplayState(1000), ClawdPet.State.IDLE);

        // 连点彩蛋：第 1 下正常，第 2 下冒泡，第 3 下不耐烦，第 4 下双跳
        ClawdPet k = new ClawdPet();
        expectTrue("poke 1 no reaction", !k.onPoke(1000));
        expect("poke 1 still idle", k.getDisplayState(1000), ClawdPet.State.IDLE);
        expectTrue("poke 2 bubble", k.onPoke(1100));
        expect("poke 2 -> bubble", k.getDisplayState(1100), ClawdPet.State.BUBBLE);
        expectTrue("poke 3 annoyed", k.onPoke(1200));
        expect("poke 3 -> annoyed", k.getDisplayState(1200), ClawdPet.State.REACT_ANNOYED);
        expectTrue("poke 4 double jump", k.onPoke(1300));
        expect("poke 4 -> double jump", k.getDisplayState(1300), ClawdPet.State.REACT_DOUBLE_JUMP);
        expectTrue("poke 5 resets to normal", !k.onPoke(1400));

        // 30 个状态（v1.7 加了 6 个：LOOK_AROUND / DOZE / WAVE / STRETCH / SPIN /
        // SOMERSAULT，全部复用已有 GIF），GIF 文件还是 24 个，不重复、不新加素材
        ClawdPet.State[] states = ClawdPet.State.values();
        expect("30 states", states.length, 30);
        java.util.Set<String> gifs = new java.util.HashSet<>();
        for (ClawdPet.State st : states) {
            gifs.add(st.gif);
            expectTrue("gif ends .gif: " + st.gif, st.gif.endsWith(".gif"));
        }
        expect("24 unique gifs", gifs.size(), 24);

        System.out.println("\n==== RESULT: " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }
}
