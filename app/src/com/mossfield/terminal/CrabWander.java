package com.mossfield.terminal;

/**
 * CrabWander：螃蟹的位置控制器（纯 Java，不依赖 Android，可在 JVM 上单测）。
 *
 * 两件事：
 * 1. 空闲溜达：每 100ms 走一小步（约 30px/秒，慢），方向随机，
 *    碰到屏幕边缘就反射换方向；偶尔坐下歇 2.5 秒；睡觉/翻跟头时不动。
 *    只改 WindowManager.LayoutParams 的 x/y，不碰终端，挡不住操作。
 * 2. 跟手拖动：手指移动时螃蟹用弹簧追着手指走，不硬贴在手指下。
 *    每步位移 = 到目标距离 × 0.22，再封顶 48px/步——手指甩得快时
 *    它就跟不上，顿一下再加速追上，绝不瞬移。手指停下后它再滑到位。
 *    松手后停在落点，过 2 秒恢复溜达。
 *
 * 方向：facingScaleX（朝左 -1 / 朝右 +1，View 层翻面）和
 * tiltDeg（朝运动方向倾斜，速度越快倾得越多，封顶 ±15°）。
 * 换方向时 turning=true 持续 300ms（服务层用它表现“转身”）。
 *
 * 时间全部由调用方传入 nowMs，不直接读系统时钟，方便测试。
 * 随机方向用可注种子的 Random（测试传固定种子，行为可复现）。
 */
public class CrabWander {

    /** 移动步进间隔（毫秒）：100ms 一步，Handler 延时驱动，省电。 */
    public static final long STEP_MS = 100;
    /** 溜达每步位移（px）：3px/100ms ≈ 30px/秒，慢。 */
    public static final float WANDER_STEP_PX = 3f;
    /** 跟手时每步最大位移（px）：封顶，手指甩再快也不瞬移。 */
    public static final float MAX_FOLLOW_STEP_PX = 48f;
    /** 跟手弹簧系数：每步走剩余距离的 22%。 */
    public static final float FOLLOW_K = 0.22f;
    /** 跟到距离小于 2px 算到了，不再抖。 */
    public static final float FOLLOW_ARRIVED_PX = 2f;
    /** 松手后过多久恢复溜达。 */
    public static final long RESUME_AFTER_DROP_MS = 2000;
    /** 偶尔坐下歇多久。 */
    public static final long SIT_MS = 2500;
    /** 坐下间隔：每 9–15 秒歇一次（随机）。 */
    public static final long SIT_EVERY_MIN_MS = 9000;
    public static final long SIT_EVERY_MAX_MS = 15000;
    /** 转身信号持续多久。 */
    public static final long TURN_MS = 300;
    /** 最大倾斜角（度）。 */
    public static final float MAX_TILT_DEG = 15f;

    public enum Mode {
        WANDERING, FOLLOWING
    }

    private final java.util.Random random;
    private Mode mode = Mode.WANDERING;
    private float x;
    private float y;
    private int screenW;
    private int screenH;
    private int petSizePx;
    /** 溜达方向（度，0=向右，90=向下）。 */
    private float angleDeg;
    private long lastStepMs = -1;
    private long sitUntilMs = 0;
    private long nextSitMs = 0;
    private long resumeAtMs = 0;
    /** 外部暂停（翻跟头时服务层置 true，恢复后置 false）。 */
    private boolean paused = false;
    /** 跟手目标（手指位置换算成的螃蟹左上角坐标）。 */
    private float targetX;
    private float targetY;
    /** 上一步是否真的移动了（服务层用它决定要不要调 updateViewLayout）。 */
    private boolean moving = false;
    /** 朝向：朝右 1 / 朝左 -1。 */
    private float facingScaleX = 1f;
    /** 倾斜角（度，正=向右倾）。 */
    private float tiltDeg = 0f;
    /** 转身中（换方向后 300ms 内）。 */
    private long turnUntilMs = 0;

    public CrabWander(float startX, float startY, int screenW, int screenH, int petSizePx) {
        this(startX, startY, screenW, screenH, petSizePx, System.nanoTime());
    }

    public CrabWander(float startX, float startY, int screenW, int screenH,
                      int petSizePx, long seed) {
        this.x = startX;
        this.y = startY;
        this.screenW = screenW;
        this.screenH = screenH;
        this.petSizePx = petSizePx;
        this.random = new java.util.Random(seed);
        this.angleDeg = random.nextFloat() * 360f;
        this.targetX = startX;
        this.targetY = startY;
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public Mode getMode() { return mode; }
    public boolean isMoving() { return moving; }
    public float getFacingScaleX() { return facingScaleX; }
    public float getTiltDeg() { return tiltDeg; }
    public boolean isTurning(long nowMs) { return nowMs < turnUntilMs; }
    public boolean isPaused() { return paused; }

    public void setScreenBounds(int w, int h, int petSizePx) {
        this.screenW = w;
        this.screenH = h;
        this.petSizePx = petSizePx;
        clampPosition();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) moving = false;
    }

    /** 手指按住螃蟹：进入跟手模式，目标就是手指位置。 */
    public void startFollow(float fingerX, float fingerY) {
        mode = Mode.FOLLOWING;
        updateFollowTarget(fingerX, fingerY);
    }

    /** 手指移动：更新跟手目标（螃蟹中心对准手指）。 */
    public void updateFollowTarget(float fingerX, float fingerY) {
        targetX = fingerX - petSizePx / 2f;
        targetY = fingerY - petSizePx / 2f;
        clampTarget();
    }

    /** 松手：停在落点，过 2 秒恢复溜达。 */
    public void endFollow(long nowMs) {
        mode = Mode.WANDERING;
        resumeAtMs = nowMs + RESUME_AFTER_DROP_MS;
        moving = false;
        tiltDeg = 0f;
    }

    /**
     * 推进一步（服务层每 100ms 调一次）。
     * 返回 true 表示位置变了（要调 updateViewLayout），false 表示没动。
     */
    public boolean step(long nowMs) {
        moving = false;
        if (paused) return false;
        if (lastStepMs < 0) lastStepMs = nowMs;
        if (nowMs - lastStepMs < STEP_MS) return false;
        lastStepMs = nowMs;
        if (mode == Mode.FOLLOWING) return stepFollow();
        return stepWander(nowMs);
    }

    /** 跟手：弹簧追目标，封顶，不瞬移。 */
    private boolean stepFollow() {
        float dx = targetX - x;
        float dy = targetY - y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist <= FOLLOW_ARRIVED_PX) {
            tiltDeg = 0f;
            return false;
        }
        float step = Math.min(dist * FOLLOW_K, MAX_FOLLOW_STEP_PX);
        float nx = x + dx / dist * step;
        float ny = y + dy / dist * step;
        // 朝向 + 倾斜：跟着手指方向走，越快倾得越多。
        if (Math.abs(dx) > 1f) facingScaleX = dx > 0 ? 1f : -1f;
        tiltDeg = Math.max(-MAX_TILT_DEG,
                Math.min(MAX_TILT_DEG, dx * 0.15f));
        x = nx;
        y = ny;
        clampPosition();
        moving = true;
        return true;
    }

    /** 溜达：沿当前方向走一小步，碰边反射，偶尔坐下歇会儿。 */
    private boolean stepWander(long nowMs) {
        if (nowMs < resumeAtMs) return false; // 刚松手，刹住歇会儿
        if (nowMs < sitUntilMs) return false; // 坐着歇呢
        if (nextSitMs == 0) {
            nextSitMs = nowMs + SIT_EVERY_MIN_MS
                    + (long) (random.nextFloat() * (SIT_EVERY_MAX_MS - SIT_EVERY_MIN_MS));
        }
        if (nowMs >= nextSitMs) {
            sitUntilMs = nowMs + SIT_MS;
            nextSitMs = nowMs + SIT_EVERY_MIN_MS
                    + (long) (random.nextFloat() * (SIT_EVERY_MAX_MS - SIT_EVERY_MIN_MS));
            moving = false;
            tiltDeg = 0f;
            return false;
        }
        double rad = Math.toRadians(angleDeg);
        float nx = x + (float) (Math.cos(rad) * WANDER_STEP_PX);
        float ny = y + (float) (Math.sin(rad) * WANDER_STEP_PX);
        boolean hitEdge = false;
        if (screenW > 0) {
            if (nx < 0) { nx = 0; hitEdge = true; }
            if (nx > screenW - petSizePx) { nx = screenW - petSizePx; hitEdge = true; }
        }
        if (screenH > 0) {
            if (ny < 0) { ny = 0; hitEdge = true; }
            if (ny > screenH - petSizePx) { ny = screenH - petSizePx; hitEdge = true; }
        }
        // 朝向 + 倾斜：跟着溜达方向走。
        float stepDx = nx - x;
        if (Math.abs(stepDx) > 0.01f) {
            float newFacing = stepDx > 0 ? 1f : -1f;
            if (newFacing != facingScaleX) turnUntilMs = nowMs + TURN_MS;
            facingScaleX = newFacing;
        }
        tiltDeg = Math.max(-MAX_TILT_DEG,
                Math.min(MAX_TILT_DEG, stepDx * 1.5f));
        x = nx;
        y = ny;
        moving = true;
        if (hitEdge) {
            // 碰边：反射换方向（转身信号由朝向翻转触发）。
            angleDeg = reflectAngle(angleDeg, nx, ny);
        }
        return true;
    }

    /** 碰边反射：按碰到哪条边翻转方向分量，再加一点随机扰动，别走直线。 */
    private float reflectAngle(float angle, float nx, float ny) {
        double rad = Math.toRadians(angle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        boolean hitVertical = (nx <= 0 || (screenW > 0 && nx >= screenW - petSizePx));
        boolean hitHorizontal = (ny <= 0 || (screenH > 0 && ny >= screenH - petSizePx));
        if (hitVertical) cos = -cos;
        if (hitHorizontal) sin = -sin;
        float next = (float) Math.toDegrees(Math.atan2(sin, cos));
        next += (random.nextFloat() - 0.5f) * 40f; // ±20° 扰动
        if (next < 0) next += 360f;
        if (next >= 360f) next -= 360f;
        return next;
    }

    private void clampPosition() {
        if (screenW > 0) x = Math.max(0, Math.min(x, screenW - petSizePx));
        if (screenH > 0) y = Math.max(0, Math.min(y, screenH - petSizePx));
    }

    private void clampTarget() {
        if (screenW > 0) targetX = Math.max(0, Math.min(targetX, screenW - petSizePx));
        if (screenH > 0) targetY = Math.max(0, Math.min(targetY, screenH - petSizePx));
    }
}
