import com.mossfield.terminal.ClawdPet;
import com.mossfield.terminal.CrabScale;

/**
 * CrabScaleTest：v1.7 缩放回归测试（JVM 上跑）。
 *
 * 覆盖：
 * 1. 状态目标缩放：开心/兴奋 1.12，睡觉 0.85，其余 1.0。
 * 2. 平滑过渡：300ms 内 smoothstep 插值到位，不跳变（中途值在起止之间）。
 * 3. 捏合尺寸：64–180dp 夹紧，无效因子原样返回。
 * 4. 持久化：SizeStore 存取往返（内存实现，服务层用 SharedPreferences 实现）。
 *
 * 全部通过退出 0，任一失败退出 1。
 */
public class CrabScaleTest {
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
        testTargetScales();
        testSmoothTransition();
        testPinchClamp();
        testSizeStoreRoundTrip();
        System.out.println("CRAB_SCALE_TEST: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    static void testTargetScales() {
        expect("happy 1.12", CrabScale.targetScaleFor(ClawdPet.State.HAPPY, false), 1.12f);
        expect("stretch 1.12", CrabScale.targetScaleFor(ClawdPet.State.STRETCH, false), 1.12f);
        expect("double jump 1.12", CrabScale.targetScaleFor(ClawdPet.State.REACT_DOUBLE_JUMP, false), 1.12f);
        expect("bubble 1.12", CrabScale.targetScaleFor(ClawdPet.State.BUBBLE, false), 1.12f);
        expect("sleeping 0.85", CrabScale.targetScaleFor(ClawdPet.State.SLEEPING, false), 0.85f);
        expect("idle 1.0", CrabScale.targetScaleFor(ClawdPet.State.IDLE, false), 1f);
        expect("thinking 1.0", CrabScale.targetScaleFor(ClawdPet.State.THINKING, false), 1f);
        expect("typing 1.0", CrabScale.targetScaleFor(ClawdPet.State.WORKING_TYPING, false), 1f);
        expect("null 1.0", CrabScale.targetScaleFor(null, false), 1f);
    }

    static void testSmoothTransition() {
        CrabScale s = new CrabScale();
        expect("starts at 1.0", s.getCurrent(), 1f);
        s.setTarget(1.12f, 1000);
        // 刚设目标时还是 1.0（不跳变）
        expect("no jump at start", s.update(1000), 1f);
        // 中途：smoothstep t=0.5 → 1.06
        float mid = s.update(1150);
        expectTrue("midway between 1.0 and 1.12: " + mid, mid > 1f && mid < 1.12f);
        expectTrue("midway ~1.06: " + mid, Math.abs(mid - 1.06f) < 0.001f);
        // 单调递增
        float prev = mid;
        boolean monotonic = true;
        for (long t = 1200; t <= 1300; t += 50) {
            float v = s.update(t);
            if (v < prev - 0.0001f) monotonic = false;
            prev = v;
        }
        expectTrue("monotonic increase", monotonic);
        // 300ms 到位，之后不动
        expect("arrives at 300ms", s.update(1300), 1.12f);
        expect("stays at target", s.update(2000), 1.12f);
        // 缩回去也一样平滑
        s.setTarget(0.85f, 3000);
        float down = s.update(3150);
        expectTrue("shrinks midway: " + down, down < 1.12f && down > 0.85f);
        expect("arrives 0.85", s.update(3300), 0.85f);
        // 目标不变时不重开过渡
        s.setTarget(0.85f, 4000);
        expect("same target stays", s.update(4000), 0.85f);
    }

    static void testPinchClamp() {
        expect("pinch up 1.5x -> 180", CrabScale.applyPinch(120, 1.5f), 180);
        expect("pinch up 2x clamps 180", CrabScale.applyPinch(120, 2.0f), 180);
        expect("pinch down 0.5x -> 64", CrabScale.applyPinch(120, 0.5f), 64);
        expect("pinch down 0.1x clamps 64", CrabScale.applyPinch(120, 0.1f), 64);
        expect("pinch 1.0x unchanged", CrabScale.applyPinch(100, 1.0f), 100);
        expect("pinch 0 factor unchanged", CrabScale.applyPinch(120, 0f), 120);
        expect("pinch negative unchanged", CrabScale.applyPinch(120, -1f), 120);
        expect("pinch NaN unchanged", CrabScale.applyPinch(120, Float.NaN), 120);
        expect("clamp 200 -> 180", CrabScale.clampPetSizeDp(200), 180);
        expect("clamp 10 -> 64", CrabScale.clampPetSizeDp(10), 64);
        expect("clamp 120 -> 120", CrabScale.clampPetSizeDp(120), 120);
        expect("min 64", CrabScale.PET_SIZE_MIN_DP, 64);
        expect("max 180", CrabScale.PET_SIZE_MAX_DP, 180);
        expect("default 120", CrabScale.PET_SIZE_DEFAULT_DP, 120);
    }

    static void testSizeStoreRoundTrip() {
        CrabScale.SizeStore store = new CrabScale.InMemorySizeStore();
        expect("load default when empty", store.load(120), 120);
        store.save(150);
        expect("load saved 150", store.load(120), 150);
        expect("load ignores default once saved", store.load(64), 150);
        store.save(64);
        expect("load saved 64", store.load(120), 64);
    }
}
