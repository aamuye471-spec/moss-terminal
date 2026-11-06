import android.view.WindowManager;
import com.mossfield.terminal.FloatingWindowService;

/**
 * FloatingOverlayTest：v1.7 锁屏悬浮窗回归测试（JVM 上跑）。
 *
 * 覆盖：
 * 1. LayoutParams flag 含 FLAG_SHOW_WHEN_LOCKED（锁屏也显示），
 *    同时含 FLAG_NOT_FOCUSABLE（不抢焦点、不挡操作）。
 * 2. 锁屏上不展开快捷面板（isPanelAllowed(false)=true，true=false）。
 *
 * 注意：跑的时候 classpath 要带 android.jar（要加载 FloatingWindowService
 * 这个 Android Service 类）；只读编译期常量 + 调纯静态方法，
 * 不调任何 Android 桩方法，不会触发 Stub 异常。
 *
 * 用法：javac -encoding UTF-8 -cp <android.jar> -d <tests/classes>
 *          app/src/com/mossfield/terminal/{ClawdPet,ClawdPetView,ClawdStateBus,
 *          CrabWander,CrabScale,SessionHolder,MainActivity,FloatingWindowService}.java
 *          tests/FloatingOverlayTest.java
 *       java -cp <tests/classes>:<android.jar> com.mossfield.terminal.FloatingOverlayTest
 * 全部通过退出 0，任一失败退出 1。
 */
public class FloatingOverlayTest {
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
        int flags = FloatingWindowService.OVERLAY_FLAGS;
        expectTrue("has FLAG_SHOW_WHEN_LOCKED",
                (flags & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0);
        expectTrue("has FLAG_NOT_FOCUSABLE",
                (flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        expectTrue("no FLAG_DISMISS_KEYGUARD (don't unlock the phone)",
                (flags & WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD) == 0);

        expect("panel allowed when unlocked", FloatingWindowService.isPanelAllowed(false), true);
        expect("panel blocked when locked", FloatingWindowService.isPanelAllowed(true), false);

        System.out.println("FLOATING_OVERLAY_TEST: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
