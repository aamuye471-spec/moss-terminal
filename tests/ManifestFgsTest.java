import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ManifestFgsTest：回归测试（v1.2 闪退根因）。
 *
 * v1.1 的 manifest 里 KeepAliveService 声明了 foregroundServiceType="dataSync"，
 * 但没有声明 FOREGROUND_SERVICE_DATA_SYNC 权限。Android 14+（targetSdk 34）上
 * startForeground() 会抛 SecurityException 直接杀进程——解压成功后闪退、每次都重复。
 *
 * 本测试解析 manifest，断言每一种 foregroundServiceType 都有对应的
 * FOREGROUND_SERVICE_* 权限声明。缺了就失败。
 *
 * 用法：java ManifestFgsTest <AndroidManifest.xml 路径>
 * 在 v1.1 的 manifest 上跑必须失败，在 v1.2 上必须通过。
 */
public class ManifestFgsTest {

    /** foregroundServiceType 值 -> 对应的 uses-permission。 */
    static final Map<String, String> TYPE_TO_PERMISSION = new LinkedHashMap<>();
    static {
        TYPE_TO_PERMISSION.put("dataSync", "android.permission.FOREGROUND_SERVICE_DATA_SYNC");
        TYPE_TO_PERMISSION.put("remoteMessaging", "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING");
        TYPE_TO_PERMISSION.put("location", "android.permission.FOREGROUND_SERVICE_LOCATION");
        TYPE_TO_PERMISSION.put("camera", "android.permission.FOREGROUND_SERVICE_CAMERA");
        TYPE_TO_PERMISSION.put("microphone", "android.permission.FOREGROUND_SERVICE_MICROPHONE");
        TYPE_TO_PERMISSION.put("mediaPlayback", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK");
        TYPE_TO_PERMISSION.put("phoneCall", "android.permission.FOREGROUND_SERVICE_PHONE_CALL");
        TYPE_TO_PERMISSION.put("connectedDevice", "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE");
        TYPE_TO_PERMISSION.put("mediaProjection", "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION");
        TYPE_TO_PERMISSION.put("health", "android.permission.FOREGROUND_SERVICE_HEALTH");
        TYPE_TO_PERMISSION.put("specialUse", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE");
        TYPE_TO_PERMISSION.put("systemExempted", "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED");
    }

    public static void main(String[] args) throws Exception {
        String manifestPath = args.length > 0 ? args[0] : "app/AndroidManifest.xml";
        String xml = new String(Files.readAllBytes(Paths.get(manifestPath)), StandardCharsets.UTF_8);

        // 收集所有声明的权限
        Set<String> permissions = new HashSet<>();
        Matcher pm = Pattern.compile("uses-permission\\s+android:name=\"([^\"]+)\"").matcher(xml);
        while (pm.find()) permissions.add(pm.group(1));

        // 收集所有 foregroundServiceType（可能用 | 分隔多个类型）
        Set<String> types = new HashSet<>();
        Matcher tm = Pattern.compile("foregroundServiceType=\"([^\"]+)\"").matcher(xml);
        while (tm.find()) {
            for (String t : tm.group(1).split("\\|")) types.add(t.trim());
        }

        System.out.println("声明的权限: " + permissions);
        System.out.println("声明的 FGS 类型: " + types);

        int failures = 0;
        if (!permissions.contains("android.permission.FOREGROUND_SERVICE")) {
            System.out.println("FAIL: 缺少 FOREGROUND_SERVICE 权限");
            failures++;
        }
        for (String type : types) {
            String need = TYPE_TO_PERMISSION.get(type);
            if (need == null) {
                System.out.println("WARN: 未知 FGS 类型 " + type + "（跳过）");
                continue;
            }
            if (!permissions.contains(need)) {
                System.out.println("FAIL: FGS 类型 " + type + " 缺少权限 " + need
                        + "（Android 14+ 上 startForeground() 会抛 SecurityException 闪退）");
                failures++;
            } else {
                System.out.println("OK: " + type + " -> " + need);
            }
        }
        if (types.isEmpty()) {
            System.out.println("WARN: manifest 里没有任何 foregroundServiceType");
        }
        if (failures > 0) {
            System.out.println("RESULT: FAIL（" + failures + " 处缺失）");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }
}
