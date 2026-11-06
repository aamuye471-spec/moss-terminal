import com.mossfield.terminal.RootBridge;
import java.io.File;

/** RootBridge 黑名单单元测试（JVM 上跑，用 Log 桩）。 */
public class RootBridgeTest {
    static int passed = 0, failed = 0;

    static void expectBlock(RootBridge b, String cmd) {
        try {
            b.checkAllowed(cmd);
            System.out.println("FAIL (should block): " + cmd);
            failed++;
        } catch (SecurityException e) {
            System.out.println("PASS block: " + cmd + " -> " + e.getMessage());
            passed++;
        }
    }

    static void expectAllow(RootBridge b, String cmd) {
        try {
            b.checkAllowed(cmd);
            System.out.println("PASS allow: " + cmd);
            passed++;
        } catch (SecurityException e) {
            System.out.println("FAIL (should allow): " + cmd + " -> " + e.getMessage());
            failed++;
        }
    }

    public static void main(String[] args) throws Exception {
        File tmp = new File("/tmp/moss-test-files");
        tmp.mkdirs();
        RootBridge b = new RootBridge(tmp);

        // 必须拦的（system 分区相关）
        expectBlock(b, "rm -rf /system");
        expectBlock(b, "rm -rf /system/etc/hosts");
        expectBlock(b, "rm -rf /vendor/lib");
        expectBlock(b, "rm -rf /product/app");
        expectBlock(b, "echo x > /etc/hosts");
        expectBlock(b, "cat > /system/build.prop <<'EOF'\nfoo\nEOF");
        expectBlock(b, "mount -o remount,rw /system");
        expectBlock(b, "mount -o remount,rw /vendor");
        expectBlock(b, "mount -o remount,rw /product");
        expectBlock(b, "dd if=/dev/zero of=/dev/block/mmcblk0");
        expectBlock(b, "rm -rf //system");
        expectBlock(b, "rm -rf /system/../system");
        expectBlock(b, "rm -rf /system/./etc");
        expectBlock(b, "cp /sdcard/x /system/app/y.apk");
        expectBlock(b, "rm -rf /apex/com.android.runtime");
        expectBlock(b, "rm -rf /data/system/packages.xml");
        expectBlock(b, "rm -rf /data/misc/wifi/wpa_supplicant.conf");
        expectBlock(b, "mount -o remount,rw /");
        // 绕过变体
        expectBlock(b, "rm -rf /system; echo done");
        expectBlock(b, "echo hi; rm -rf /vendor");

        // 必须放行的（合法 root 玩法）
        expectAllow(b, "ls /sdcard");
        expectAllow(b, "pm disable-user --user 0 com.example.app");
        expectAllow(b, "pm enable com.example.app");
        expectAllow(b, "dumpsys battery");
        expectAllow(b, "dumpsys meminfo");
        expectAllow(b, "settings put global settings_enable_monitor_phantom_procs false");
        expectAllow(b, "settings put system screen_brightness 100");
        expectAllow(b, "iptables -A OUTPUT -m owner --uid-owner 1234 -j DROP");
        expectAllow(b, "tar -czf /sdcard/backup.tar.gz /data/data/com.example");
        expectAllow(b, "cat /proc/meminfo");
        expectAllow(b, "input tap 100 200");
        expectAllow(b, "screencap -p /sdcard/shot.png");
        expectAllow(b, "cmd notification set_dnd on");
        expectAllow(b, "getprop ro.build.version.sdk");
        expectAllow(b, "echo hello");
        expectAllow(b, "ls /data/data/com.mossfield.terminal/files");
        expectBlock(b, "mount -o remount,rw /tmp"); // /tmp 在 Android 上不存在，remount 任意路径 rw 必须拦
expectAllow(b, "mount -o remount,rw -t tmpfs tmpfs /data/local/tmp"); // 显式 tmpfs 才放行
        expectAllow(b, "mount --bind /data/data/com.mossfield.terminal/files/workspace /sdcard/ClaudeCode");

        System.out.println("\n==== RESULT: " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }
}
