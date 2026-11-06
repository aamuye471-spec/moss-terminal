import com.mossfield.terminal.ClawdPet;

/**
 * ClawdPetActsTest：v1.7 动作丰富度回归测试（JVM 上跑）。
 *
 * 覆盖：
 * 1. 空闲轮换：10 秒宽限期后按表轮换（看书/张望/挥手/伸懒腰/打盹/发呆），
 *    每个动作 2–4 秒，不闪切（两次切换间隔 >= 2 秒），不卡在同一个动作，
 *    60 秒内至少出现 4 种不同动作，看书仍在轮换里。
 * 2. 单点反应：每 3 次单点换一个反应（跳 → 转圈 → 挥爪），循环。
 * 3. 工作状态区分：思考/打字/出错/成功的变换各不相同。
 * 4. 翻跟头：翻 → 晕 → 爬起来完整跑完（共 4 秒），期间点按被忽略，
 *    恢复后状态机正常；空闲满 8 轮自动来一次；连点第 3 次双跳换成翻跟头。
 *
 * 时间全部用假时钟（传 nowMs），不依赖系统时间。
 * 全部通过退出 0，任一失败退出 1。
 */
public class ClawdPetActsTest {
    static int passed = 0, failed = 0;

    static void expect(String name, Object got, Object want) {
        if (want.equals(got)) {
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
        testIdleRotation();
        testSingleTapReactions();
        testWorkStateTransforms();
        testSomersaultFullRun();
        testSomersaultAutoAfterCycles();
        testSomersaultFromRapidTaps();
        System.out.println("CLAWDPET_ACTS_TEST: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ---------- 1. 空闲轮换 ----------

    static void testIdleRotation() {
        ClawdPet p = new ClawdPet();
        // 宽限期：前 10 秒就是 plain IDLE
        expect("0s idle", p.getDisplayState(0), ClawdPet.State.IDLE);
        expect("9s idle (grace)", p.getDisplayState(9000), ClawdPet.State.IDLE);
        expect("10s reading first", p.getDisplayState(10000), ClawdPet.State.IDLE_READING);
        // 60 秒采样：每 500ms 取一次
        java.util.List<ClawdPet.State> seen = new java.util.ArrayList<>();
        java.util.List<Long> changeTimes = new java.util.ArrayList<>();
        ClawdPet.State last = null;
        long lastChange = 0;
        for (long t = 0; t <= 60000; t += 500) {
            ClawdPet.State s = p.getDisplayState(t);
            if (last == null || s != last) {
                if (last != null) changeTimes.add(t - lastChange);
                seen.add(s);
                last = s;
                lastChange = t;
            }
        }
        java.util.Set<ClawdPet.State> distinct = new java.util.HashSet<>(seen);
        expectTrue("rotation shows >= 4 distinct acts in 60s: " + distinct, distinct.size() >= 4);
        expectTrue("reading still appears in rotation", distinct.contains(ClawdPet.State.IDLE_READING));
        expectTrue("look-around appears", distinct.contains(ClawdPet.State.LOOK_AROUND));
        expectTrue("wave appears", distinct.contains(ClawdPet.State.WAVE));
        expectTrue("doze appears", distinct.contains(ClawdPet.State.DOZE));
        // 不闪切：两次切换间隔都 >= 2 秒
        boolean noFlicker = true;
        for (long gap : changeTimes) {
            if (gap < 2000) noFlicker = false;
        }
        expectTrue("no flicker (all gaps >= 2s): " + changeTimes, noFlicker);
        // 不重复卡在同一个：连续两次采样不一样的次数够多
        expectTrue("rotation keeps changing: " + seen.size() + " segments", seen.size() >= 8);
    }

    // ---------- 2. 单点反应 ----------

    static void testSingleTapReactions() {
        ClawdPet p = new ClawdPet();
        // 第 1、2 次单点：没反应
        expectTrue("tap 1 no reaction", !p.onSingleTapReaction(1000));
        expectTrue("tap 2 no reaction", !p.onSingleTapReaction(5000));
        // 第 3 次：跳一下
        expectTrue("tap 3 reacts", p.onSingleTapReaction(9000));
        expect("tap 3 -> double jump", p.getDisplayState(9000), ClawdPet.State.REACT_DOUBLE_JUMP);
        // 第 4、5 次：没反应
        expectTrue("tap 4 no reaction", !p.onSingleTapReaction(13000));
        expectTrue("tap 5 no reaction", !p.onSingleTapReaction(17000));
        // 第 6 次：转个圈
        expectTrue("tap 6 reacts", p.onSingleTapReaction(21000));
        expect("tap 6 -> spin", p.getDisplayState(21000), ClawdPet.State.SPIN);
        // 第 7、8 次：没反应；第 9 次：挥爪
        expectTrue("tap 7 no reaction", !p.onSingleTapReaction(25000));
        expectTrue("tap 8 no reaction", !p.onSingleTapReaction(29000));
        expectTrue("tap 9 reacts", p.onSingleTapReaction(33000));
        expect("tap 9 -> wave", p.getDisplayState(33000), ClawdPet.State.WAVE);
        // 第 12 次：循环回跳一下
        p.onSingleTapReaction(37000);
        p.onSingleTapReaction(41000);
        expectTrue("tap 12 reacts", p.onSingleTapReaction(45000));
        expect("tap 12 -> double jump again", p.getDisplayState(45000),
                ClawdPet.State.REACT_DOUBLE_JUMP);
    }

    // ---------- 3. 工作状态区分 ----------

    static void testWorkStateTransforms() {
        ClawdPet p = new ClawdPet();
        // 思考：轻微起伏（t=1300 时 scaleY = 1.02，sin(π/2)=1）
        p.onUserPromptSubmit(1000);
        ClawdPet.Transform thinking = p.getDisplayTransform(1300);
        expect("thinking state", p.getDisplayState(1300), ClawdPet.State.THINKING);
        expectTrue("thinking rotation ~ 0", Math.abs(thinking.rotationDeg) < 0.01f);
        expectTrue("thinking bobs (scaleY > 1): " + thinking.scaleY, thinking.scaleY > 1f);
        // 打字：无变换
        p.onToolUse(1, 2000);
        ClawdPet.Transform typing = p.getDisplayTransform(2000);
        expect("typing state", p.getDisplayState(2000), ClawdPet.State.WORKING_TYPING);
        expectTrue("typing identity", typing.rotationDeg == 0f && typing.scale == 1f
                && typing.scaleX == 1f && typing.scaleY == 1f);
        // 出错：抖动（t=3100 时 rotation = 5*sin(pi/2) = 5）
        p.onToolFailure(3000);
        expect("error state", p.getDisplayState(3000), ClawdPet.State.ERROR);
        ClawdPet.Transform error = p.getDisplayTransform(3100);
        expectTrue("error shakes (|rotation| > 1): " + error.rotationDeg,
                Math.abs(error.rotationDeg) > 1f);
        // 成功：长大（scale 1.12）
        p.onStop(4000);
        expect("happy state", p.getDisplayState(4000), ClawdPet.State.HAPPY);
        ClawdPet.Transform happy = p.getDisplayTransform(4000);
        expect("happy scale 1.12", happy.scale, 1.12f);
        // 四个变换各不相同
        expectTrue("thinking != typing transform",
                thinking.scaleY != typing.scaleY || thinking.rotationDeg != typing.rotationDeg
                        || thinking.scale != typing.scale);
        expectTrue("error != happy transform",
                error.rotationDeg != happy.rotationDeg || error.scale != happy.scale);
    }

    // ---------- 4. 翻跟头完整跑完 ----------

    static void testSomersaultFullRun() {
        ClawdPet p = new ClawdPet();
        p.startSomersault(1000);
        // 翻：600ms 时转了 180°
        expect("flip phase", p.getSomersaultPhase(1600), ClawdPet.SomersaultPhase.FLIP);
        ClawdPet.Transform flip = p.getDisplayTransform(1600);
        expectTrue("flip rotation ~180: " + flip.rotationDeg,
                Math.abs(flip.rotationDeg - 180f) < 1f);
        expect("flip shows somersault", p.getDisplayState(1600), ClawdPet.State.SOMERSAULT);
        // 翻的时候点按也没反应（在空中呢）
        expectTrue("tap ignored during flip", !p.onPoke(1700));
        // 晕：翻完（1200ms）→ 躺平 90° + 压扁
        expect("dizzy phase", p.getSomersaultPhase(2200), ClawdPet.SomersaultPhase.DIZZY);
        ClawdPet.Transform dizzy = p.getDisplayTransform(2500);
        expect("dizzy rotation 90", dizzy.rotationDeg, 90f);
        expect("dizzy scaleY 0.7", dizzy.scaleY, 0.7f);
        expectTrue("tap ignored during dizzy", !p.onPoke(2500));
        expectTrue("single tap ignored during dizzy", !p.onSingleTapReaction(2600));
        // 爬起来：90° → 0°
        expect("recover phase", p.getSomersaultPhase(4500), ClawdPet.SomersaultPhase.RECOVER);
        ClawdPet.Transform recover = p.getDisplayTransform(4500);
        expectTrue("recover rotation between 0 and 90: " + recover.rotationDeg,
                recover.rotationDeg > 0f && recover.rotationDeg < 90f);
        // 4 秒整跑完，恢复正常
        expect("total 4s", ClawdPet.SOMERSAULT_TOTAL_MS, 4000L);
        expect("somersault over", p.getSomersaultPhase(5000), ClawdPet.SomersaultPhase.NONE);
        expect("back to idle", p.getDisplayState(5000), ClawdPet.State.IDLE);
        // 恢复后点按正常
        expectTrue("tap works after recovery", !p.onPoke(6000));
        expect("still idle after tap", p.getDisplayState(6000), ClawdPet.State.IDLE);
    }

    // ---------- 5. 空闲满 8 轮自动翻跟头 ----------

    static void testSomersaultAutoAfterCycles() {
        ClawdPet p = new ClawdPet();
        // 不调 noteActivity：lastActivityMs=0，不会睡觉，纯溜达到 200 秒
        int somersaultStarts = 0;
        long firstStart = -1;
        ClawdPet.SomersaultPhase prev = ClawdPet.SomersaultPhase.NONE;
        for (long t = 0; t <= 200000; t += 500) {
            p.getDisplayState(t);
            ClawdPet.SomersaultPhase cur = p.getSomersaultPhase(t);
            if (cur == ClawdPet.SomersaultPhase.FLIP && prev != ClawdPet.SomersaultPhase.FLIP) {
                somersaultStarts++;
                if (firstStart < 0) firstStart = t;
            }
            prev = cur;
        }
        expect("exactly 1 auto somersault in 200s", somersaultStarts, 1);
        expectTrue("first auto somersault after 140s: " + firstStart,
                firstStart >= 140000 && firstStart <= 160000);
    }

    // ---------- 6. 连点第 3 次双跳换成翻跟头 ----------

    static void testSomersaultFromRapidTaps() {
        ClawdPet p = new ClawdPet();
        // 第 1 轮 4 连点 → 双跳
        p.onPoke(1000); p.onPoke(1100); p.onPoke(1200);
        expectTrue("round 1 double jump", p.onPoke(1300));
        expect("round 1 -> double jump", p.getDisplayState(1300), ClawdPet.State.REACT_DOUBLE_JUMP);
        // 第 2 轮 4 连点（隔 2 秒，计数重置）→ 双跳
        p.onPoke(3000); p.onPoke(3100); p.onPoke(3200);
        expectTrue("round 2 double jump", p.onPoke(3300));
        expect("round 2 -> double jump", p.getDisplayState(3300), ClawdPet.State.REACT_DOUBLE_JUMP);
        // 第 3 轮 4 连点 → 翻跟头
        p.onPoke(5000); p.onPoke(5100); p.onPoke(5200);
        expectTrue("round 3 somersault", p.onPoke(5300));
        expect("round 3 -> somersault", p.getDisplayState(5300), ClawdPet.State.SOMERSAULT);
    }
}
