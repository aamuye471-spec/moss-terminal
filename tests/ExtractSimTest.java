import java.io.*;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ExtractSimTest：模拟 SetupManager.extractAsset() 的解压流程。
 *
 * 做法：用 java.util.zip.ZipFile 打开 APK，取出 assets/claude 条目，
 * 以 256KB 缓冲流式写出（和 SetupManager.extractAsset 完全一致），
 * 记录耗时、堆内存峰值、SHA-256，并与预期值比对。
 *
 * 意义：Android 的 AssetManager.open() 对压缩资源做的事，本质上就是
 * ZipFile + zlib inflate（同一份 zlib）。这个测试量的是“解压 321MB
 * DEFLATE 压缩资源”本身的 CPU/内存代价。
 *
 * 用法：java ExtractSimTest <apk路径> <输出目录>
 */
public class ExtractSimTest {
    static final String EXPECTED_SHA256 =
            "3c9743b09f5c4b3b1d7dc0f303f8b8b7fc45f82df0c08debb52e5976e33ec423";

    public static void main(String[] args) throws Exception {
        String apkPath = args[0];
        File outDir = new File(args[1]);
        outDir.mkdirs();

        try (ZipFile zf = new ZipFile(apkPath)) {
            ZipEntry claude = zf.getEntry("assets/claude");
            ZipEntry loader = zf.getEntry("assets/ld-musl-aarch64.so.1");
            System.out.println("assets/claude: method="
                    + (claude.getMethod() == ZipEntry.DEFLATED ? "DEFLATED" : "STORED")
                    + " compressed=" + claude.getCompressedSize()
                    + " uncompressed=" + claude.getSize());
            System.out.println("assets/ld-musl-aarch64.so.1: method="
                    + (loader.getMethod() == ZipEntry.DEFLATED ? "DEFLATED" : "STORED")
                    + " compressed=" + loader.getCompressedSize()
                    + " uncompressed=" + loader.getSize());

            // --- 模拟 extractAsset("claude") ---
            File dest = new File(outDir, "claude");
            long t0 = System.currentTimeMillis();
            long peakUsed = 0;
            long copied = 0;
            byte[] buf = new byte[1024 * 256];
            try (InputStream in = zf.getInputStream(claude);
                 OutputStream out = new FileOutputStream(dest)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    copied += n;
                    long used = Runtime.getRuntime().totalMemory()
                            - Runtime.getRuntime().freeMemory();
                    if (used > peakUsed) peakUsed = used;
                }
            }
            long ms = System.currentTimeMillis() - t0;
            System.out.println("extract claude: copied=" + copied
                    + " bytes in " + ms + " ms"
                    + " peakHeapUsed=" + (peakUsed / 1024 / 1024) + " MB"
                    + " outSize=" + dest.length());

            // --- SHA-256 校验（模拟 SetupManager.runSetup 末尾的校验）---
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(dest)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            String sha = sb.toString();
            System.out.println("sha256=" + sha);
            System.out.println("sha256 match expected: " + EXPECTED_SHA256.equals(sha));

            // --- 模拟 extractAsset("ld-musl-aarch64.so.1") ---
            File destLd = new File(outDir, "ld-musl-aarch64.so.1");
            long t1 = System.currentTimeMillis();
            try (InputStream in = zf.getInputStream(loader);
                 OutputStream out = new FileOutputStream(destLd)) {
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            System.out.println("extract loader: " + destLd.length() + " bytes in "
                    + (System.currentTimeMillis() - t1) + " ms");
            System.out.println("EXTRACT_SIM_OK");
        }
    }
}
