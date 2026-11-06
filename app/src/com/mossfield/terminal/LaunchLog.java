package com.mossfield.terminal;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * LaunchLog：启动日志，写进应用私有目录 files/logs/launch.log。
 *
 * 每次启动的关键步骤（释放、自检、root 检测、保活服务、启动前校验、
 * 会话创建、失败原因）都记一行带时间戳。用户在菜单 → 查看启动日志
 * 里直接能看，不用连电脑抓 logcat。
 */
public class LaunchLog {

    private static final String TAG = "LaunchLog";
    /** 对话框里最多显示的行数（太长截尾，留最新的）。 */
    private static final int MAX_LINES_SHOWN = 200;

    private final File logFile;

    public LaunchLog(File filesDir) {
        File logDir = new File(filesDir, "logs");
        if (!logDir.exists()) logDir.mkdirs();
        this.logFile = new File(logDir, "launch.log");
    }

    public synchronized void log(String msg) {
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date());
        String line = ts + " " + msg + "\n";
        try (FileOutputStream out = new FileOutputStream(logFile, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "write failed", e);
        }
        Log.i(TAG, msg);
    }

    /** 读日志（只留最后 MAX_LINES_SHOWN 行）。文件不存在或为空返回 ""。 */
    public String readAll() {
        if (!logFile.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(logFile), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
            while ((line = r.readLine()) != null) {
                tail.add(line);
                if (tail.size() > MAX_LINES_SHOWN) tail.removeFirst();
                count++;
            }
            for (String l : tail) sb.append(l).append('\n');
            if (count > MAX_LINES_SHOWN) {
                sb.insert(0, "…（仅显示最后 " + MAX_LINES_SHOWN + " 行，共 " + count + " 行）\n");
            }
        } catch (Exception e) {
            return "";
        }
        return sb.toString();
    }

    /** 清空日志。 */
    public synchronized void clear() {
        try {
            if (logFile.exists()) logFile.delete();
        } catch (Exception ignored) {
        }
    }
}
