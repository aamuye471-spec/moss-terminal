package com.mossfield.terminal;

/**
 * CrabScale：螃蟹的尺寸控制（纯 Java，不依赖 Android，可在 JVM 上单测）。
 *
 * 两件事：
 * 1. 状态缩放：按显示状态给目标缩放——开心/兴奋时长大一点（1.12），
 *    睡觉时缩一点（0.85），其余 1.0。update() 用 smoothstep 在 300ms 内
 *    平滑插值过去，不跳变。缩放只改 View 里画出来的螃蟹大小
 *    （ClawdPetView 的 canvas），不改 View 本身的布局尺寸，
 *    所以点按区域和拖动都不受影响。
 * 2. 捏合缩放：双指捏合调宠物基础尺寸，64dp–180dp 之间，松手后记住，
 *    下次打开还是这个大小（经 SizeStore 持久化，服务层用
 *    SharedPreferences 实现）。长按切迷你/正常保留：迷你就是缩到最小 64dp。
 */
public class CrabScale {

    /** 宠物尺寸范围（dp）：最小 64（迷你），最大 180，默认 120。 */
    public static final int PET_SIZE_MIN_DP = 64;
    public static final int PET_SIZE_MAX_DP = 180;
    public static final int PET_SIZE_DEFAULT_DP = 120;
    /** 状态缩放过渡时长（毫秒）。 */
    public static final long TRANSITION_MS = 300;

    /** 尺寸持久化接口（服务层用 SharedPreferences 实现，单测用内存实现）。 */
    public interface SizeStore {
        int load(int defValue);
        void save(int sizeDp);
    }

    /** 内存版 SizeStore（单测用）。 */
    public static class InMemorySizeStore implements SizeStore {
        private int value = -1;

        @Override
        public int load(int defValue) {
            return value < 0 ? defValue : value;
        }

        @Override
        public void save(int sizeDp) {
            value = sizeDp;
        }
    }

    /** 按显示状态给目标缩放：开心/兴奋 1.12，睡觉 0.85，其余 1.0。 */
    public static float targetScaleFor(ClawdPet.State display, boolean mini) {
        if (display == null) return 1f;
        switch (display) {
            case HAPPY:
            case STRETCH:
            case REACT_DOUBLE_JUMP:
            case BUBBLE:
                return 1.12f;
            case SLEEPING:
                return 0.85f;
            default:
                return 1f;
        }
    }

    /** 捏合尺寸夹到 64–180dp。 */
    public static int clampPetSizeDp(int sizeDp) {
        return Math.max(PET_SIZE_MIN_DP, Math.min(PET_SIZE_MAX_DP, sizeDp));
    }

    /**
     * 捏合一步：当前尺寸 × 手势缩放因子，再夹到范围内。
     * factor > 1 放大，< 1 缩小；factor <= 0 或 NaN 时原样返回。
     */
    public static int applyPinch(int currentDp, float factor) {
        if (factor <= 0f || Float.isNaN(factor) || Float.isInfinite(factor)) return currentDp;
        return clampPetSizeDp(Math.round(currentDp * factor));
    }

    private float current = 1f;
    private float from = 1f;
    private float target = 1f;
    private long transitionStartMs = -1;

    public float getCurrent() {
        return current;
    }

    public float getTarget() {
        return target;
    }

    /** 设新的目标缩放（目标变了才重开 300ms 过渡）。 */
    public void setTarget(float target, long nowMs) {
        if (target == this.target) return;
        this.from = current;
        this.target = target;
        this.transitionStartMs = nowMs;
    }

    /**
     * 推进一步，返回当前缩放（smoothstep 插值，300ms 到位）。
     * 到位后返回的就是目标值，不再抖。
     */
    public float update(long nowMs) {
        if (transitionStartMs < 0 || current == target) return current;
        float t = (nowMs - transitionStartMs) / (float) TRANSITION_MS;
        if (t >= 1f) {
            current = target;
            transitionStartMs = -1;
            return current;
        }
        if (t <= 0f) return current;
        float s = t * t * (3f - 2f * t); // smoothstep
        current = from + (target - from) * s;
        return current;
    }
}
