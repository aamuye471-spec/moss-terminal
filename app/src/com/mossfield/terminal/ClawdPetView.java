package com.mossfield.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.graphics.Paint;
import android.util.Log;
import android.view.View;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * ClawdPetView：用系统自带 android.graphics.Movie 播 GIF 的螃蟹。
 *
 * 不引第三方 GIF 库（Glide 之类）：Movie 是 Android 内置的 GIF 解码器，
 * 24 张 GIF 一共才 1.9 MB，直接放 assets/clawd/ 里，够用了。
 *
 * 用法：FloatingWindowService 每 ~500ms 调一次 {@link #setPetState}，
 * 传 ClawdPet 算好的当前状态；View 自己按帧率重绘。
 * GIF 解码失败 / 文件缺失时不崩：画一个橙色圆 + 螃蟹 emoji 兜底，
 * 并记 log，悬浮窗照常能点、按钮照常能用。
 */
public class ClawdPetView extends View {

    private static final String TAG = "ClawdPetView";
    private static final String ASSET_DIR = "clawd/";

    private final Map<String, Movie> movieCache = new HashMap<>();
    private Movie movie;
    private String currentGif;
    private long movieStartMs = 0;
    private boolean movieFailed = false;
    /** v1.7：显示变换（旋转/翻面/压扁，绕中心画，不改 View 尺寸）。 */
    private float transformRotationDeg = 0f;
    private float transformScaleX = 1f;
    private float transformScaleY = 1f;
    /** v1.7：状态缩放（CrabScale 平滑插值后的值，只改画出来的大小）。 */
    private float drawScale = 1f;
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable animator = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) return;
            invalidate();
            postDelayed(this, 50);
        }
    };

    public ClawdPetView(Context context) {
        super(context);
        fallbackPaint.setColor(0xFFFF7043);
        fallbackTextPaint.setColor(0xFFFFFFFF);
        fallbackTextPaint.setTextSize(64);
        fallbackTextPaint.setTextAlign(Paint.Align.CENTER);
        setContentDescription("螃蟹桌宠，点一下展开快捷按钮，长按切换迷你模式");
    }

    /**
     * 设置当前状态。GIF 没变就不重新解码（只换 Movie 的播放起点也不用）；
     * GIF 变了才解码并从头播。
     */
    public void setPetState(ClawdPet.State state, boolean mini) {
        String gif = ClawdPet.gifFor(state, mini);
        if (gif.equals(currentGif) && movie != null) return;
        currentGif = gif;
        movie = loadMovie(gif);
        movieFailed = (movie == null);
        movieStartMs = System.currentTimeMillis();
        invalidate();
    }

    /** v1.7：设显示变换（旋转/翻面/压扁），由服务层每 tick 传进来。 */
    public void setTransform(float rotationDeg, float scaleX, float scaleY) {
        transformRotationDeg = rotationDeg;
        transformScaleX = scaleX;
        transformScaleY = scaleY;
        invalidate();
    }

    /** v1.7：设状态缩放（CrabScale 平滑插值后的值，只改画出来的大小）。 */
    public void setDrawScale(float scale) {
        drawScale = scale;
        invalidate();
    }

    public float getDrawScale() {
        return drawScale;
    }

    private Movie loadMovie(String gif) {
        Movie cached = movieCache.get(gif);
        if (cached != null) return cached;
        InputStream in = null;
        try {
            in = getContext().getAssets().open(ASSET_DIR + gif);
            Movie m = Movie.decodeStream(in);
            if (m == null) {
                Log.w(TAG, "Movie.decodeStream returned null: " + gif);
                return null;
            }
            movieCache.put(gif, m);
            return m;
        } catch (Throwable t) {
            Log.w(TAG, "cannot load gif: " + gif, t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(animator);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(animator);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // v1.7：变换绕 View 中心做——旋转/翻面/缩放只改画出来的螃蟹，
        // View 本身的尺寸和点按区域不动。
        canvas.save();
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        canvas.translate(cx, cy);
        canvas.rotate(transformRotationDeg);
        canvas.scale(transformScaleX * drawScale, transformScaleY * drawScale);
        canvas.translate(-cx, -cy);
        if (movie == null) {
            drawFallback(canvas);
            canvas.restore();
            return;
        }
        int duration = movie.duration();
        if (duration <= 0) duration = 1000;
        int relTime = (int) ((System.currentTimeMillis() - movieStartMs) % duration);
        movie.setTime(relTime);
        float scaleX = getWidth() / (float) movie.width();
        float scaleY = getHeight() / (float) movie.height();
        float scale = Math.min(scaleX, scaleY);
        float dx = (getWidth() - movie.width() * scale) / 2f;
        float dy = (getHeight() - movie.height() * scale) / 2f;
        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale);
        movie.draw(canvas, 0, 0);
        canvas.restore();
        canvas.restore();
    }

    /** 兜底：GIF 播不了也别留个空白——橙色圆 + 螃蟹，保证悬浮窗还能认、能点。 */
    private void drawFallback(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f;
        canvas.drawCircle(cx, cy, r, fallbackPaint);
        Paint.FontMetrics fm = fallbackTextPaint.getFontMetrics();
        float y = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("🦀", cx, y, fallbackTextPaint);
    }
}
