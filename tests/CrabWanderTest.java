import com.mossfield.terminal.CrabWander;

/**
 * CrabWanderTest：v1.7 移动回归测试（JVM 上跑）。
 *
 * 覆盖：
 * 1. 空闲溜达：位置随时间变化、不出屏幕边界、速度慢（每步 <= 3px）。
 * 2. 跟手：位置平滑追向手指，每步 <= 48px（不瞬移），最终到达手指处。
 * 3. 快速甩动：目标很远时第一步也被封顶（不瞬移），之后加速追上。
 * 4. 松手：停在落点 2 秒不动，之后恢复溜达。
 * 5. 朝向：往右走朝右、往左走朝左（facingScaleX 翻面）。
 * 6. 暂停：翻跟头时暂停（setPaused），恢复后继续动。
 *
 * 时间全部用假时钟（传 nowMs），随机方向用固定种子。
 * 全部通过退出 0，任一失败退出 1。
 */
public class CrabWanderTest {
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
        testWanderMovesAndStaysInBounds();
        testFollowSmoothAndArrives();
        testFastFlingNoTeleport();
        testDropStaysThenResumes();
        testFacingFlips();
        testPaused();
        System.out.println("CRAB_WANDER_TEST: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ---------- 1. 空闲溜达 ----------

    static void testWanderMovesAndStaysInBounds() {
        CrabWander w = new CrabWander(500, 500, 1080, 2400, 120, 42L);
        float startX = w.getX(), startY = w.getY();
        float maxStep = 0f;
        boolean moved = false;
        for (long t = 0; t <= 30000; t += 100) {
            float px = w.getX(), py = w.getY();
            w.step(t);
            float d = dist(px, py, w.getX(), w.getY());
            if (d > 0) {
                moved = true;
                maxStep = Math.max(maxStep, d);
            }
            expectTrue("in bounds x at " + t, w.getX() >= 0 && w.getX() <= 1080 - 120);
            expectTrue("in bounds y at " + t, w.getY() >= 0 && w.getY() <= 2400 - 120);
        }
        expectTrue("wander moved", moved);
        expectTrue("wander moved away from start",
                dist(startX, startY, w.getX(), w.getY()) > 20f);
        expectTrue("wander slow (max step <= 3px): " + maxStep, maxStep <= 3.01f);
    }

    // ---------- 2. 跟手平滑 + 到达 ----------

    static void testFollowSmoothAndArrives() {
        CrabWander w = new CrabWander(100, 100, 1080, 2400, 120, 7L);
        w.startFollow(500, 500);
        float maxStep = 0f;
        long t = 0;
        for (; t <= 5000; t += 100) {
            float px = w.getX(), py = w.getY();
            w.step(t);
            maxStep = Math.max(maxStep, dist(px, py, w.getX(), w.getY()));
            // t=0 的第一步只是初始化（不动），从 t>0 起才算到达。
            if (t > 0 && !w.isMoving()) break;
        }
        expectTrue("follow max step <= 48px: " + maxStep, maxStep <= 48.01f);
        // 目标是手指中心：(500-60, 500-60) = (440, 440)，2px 内算到了
        expectTrue("follow arrived near finger: (" + w.getX() + "," + w.getY() + ")",
                dist(w.getX(), w.getY(), 440f, 440f) <= 2.01f);
        expectTrue("follow arrived within 5s", t <= 5000);
    }

    // ---------- 3. 快速甩动不瞬移 ----------

    static void testFastFlingNoTeleport() {
        CrabWander w = new CrabWander(100, 100, 1080, 2400, 120, 7L);
        // 手指一下甩到很远（1900px 外）
        w.startFollow(1000, 2000);
        float px = w.getX(), py = w.getY();
        w.step(0);
        w.step(100);
        float firstStep = dist(px, py, w.getX(), w.getY());
        expectTrue("first step capped at 48px: " + firstStep, firstStep <= 48.01f);
        expectTrue("first step moved", firstStep > 0f);
        // 多步之后追上了（距离在缩小，没瞬移过去）
        float before = dist(w.getX(), w.getY(), 940f, 1940f);
        for (long t = 200; t <= 2000; t += 100) w.step(t);
        float after = dist(w.getX(), w.getY(), 940f, 1940f);
        expectTrue("catching up (dist " + before + " -> " + after + ")", after < before);
    }

    // ---------- 4. 松手停在落点，2 秒后恢复溜达 ----------

    static void testDropStaysThenResumes() {
        CrabWander w = new CrabWander(100, 100, 1080, 2400, 120, 7L);
        w.startFollow(600, 600);
        long t = 0;
        for (; t <= 5000; t += 100) {
            w.step(t);
            if (t > 0 && !w.isMoving()) break;
        }
        float dropX = w.getX(), dropY = w.getY();
        w.endFollow(t);
        // 松手后 2 秒内不动
        boolean stayed = true;
        for (long u = t + 100; u <= t + 1900; u += 100) {
            w.step(u);
            if (w.getX() != dropX || w.getY() != dropY) stayed = false;
        }
        expectTrue("stays at drop point for 2s", stayed);
        // 2 秒后恢复溜达
        boolean movedAgain = false;
        for (long u = t + 2000; u <= t + 5000; u += 100) {
            float px = w.getX(), py = w.getY();
            w.step(u);
            if (w.getX() != px || w.getY() != py) movedAgain = true;
        }
        expectTrue("resumes wandering after drop", movedAgain);
    }

    // ---------- 5. 朝向 ----------

    static void testFacingFlips() {
        CrabWander w = new CrabWander(500, 500, 1080, 2400, 120, 7L);
        w.startFollow(900, 500); // 往右
        for (long t = 0; t <= 1000; t += 100) w.step(t);
        expect("facing right", w.getFacingScaleX(), 1f);
        w.startFollow(100, 500); // 往左
        for (long t = 1100; t <= 3000; t += 100) w.step(t);
        expect("facing left", w.getFacingScaleX(), -1f);
    }

    // ---------- 6. 暂停 ----------

    static void testPaused() {
        CrabWander w = new CrabWander(500, 500, 1080, 2400, 120, 7L);
        w.setPaused(true);
        float px = w.getX(), py = w.getY();
        for (long t = 0; t <= 2000; t += 100) {
            expectTrue("paused: no move", !w.step(t));
        }
        expect("paused: position unchanged", w.getX(), px);
        expect("paused: y unchanged", w.getY(), py);
        w.setPaused(false);
        boolean moved = false;
        for (long t = 2100; t <= 5000; t += 100) {
            float bx = w.getX(), by = w.getY();
            w.step(t);
            if (w.getX() != bx || w.getY() != by) moved = true;
        }
        expectTrue("resumed after unpause", moved);
    }
}
