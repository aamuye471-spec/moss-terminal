import java.io.*;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * AtomicExtractTest：验证 v1.1 新增的原子释放流程。
 *
 * 模拟 SetupManager.extractAsset() 的新逻辑：
 * 写临时文件（.tmp）→ 校验大小 → 原子改名 → SHA-256 校验。
 * 任何一步失败都要删掉临时文件、不留坏的二进制。
 *
 * 用法：java AtomicExtractTest <apk路径> <输出目录>
 */
public class AtomicExtractTest {
    static final long EXPECTED_CLAUDE_SIZE = 321810696L;
    static final String EXPECTED_SHA256 =
            "3c9743b09f5c4b3b1d7dc0f303f8b8b7fc45f82df0c08debb52e5976e33ec423";

    public static void main(String[] args) throws Exception {
        String apkPath = args[0];
        File outDir = new File(args[1]);
        outDir.mkdirs();
        File dest = new File(outDir, "claude");
        File tmp = new File(outDir, "claude.tmp");
        if (tmp.exists()) tmp.delete();
        if (dest.exists()) dest.delete();

        // 1. 流式写临时文件（256KB 缓冲，和 SetupManager 一致）
        long copied = 0;
        try (ZipFile zf = new ZipFile(apkPath)) {
            ZipEntry e = zf.getEntry("assets/claude");
            System.out.println("asset method=" + (e.getMethod() == ZipEntry.STORED ? "STORED" : "DEFLATED"));
            try (InputStream in = zf.getInputStream(e);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1024 * 256];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    copied += n;
                }
                out.flush();
            }
        }
        System.out.println("step1 tmp written: " + copied + " bytes");

        // 2. 大小校验
        if (tmp.length() != EXPECTED_CLAUDE_SIZE) {
            tmp.delete();
            throw new AssertionError("size mismatch: " + tmp.length());
        }
        System.out.println("step2 size check OK: " + tmp.length());

        // 3. 原子改名
        if (!tmp.renameTo(dest)) {
            throw new AssertionError("rename failed");
        }
        System.out.println("step3 rename OK, tmp gone=" + !tmp.exists()
                + " dest exists=" + dest.exists());

        // 4. SHA-256 校验
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(dest)) {
            byte[] buf = new byte[1024 * 256];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        String sha = sb.toString();
        if (!EXPECTED_SHA256.equals(sha)) {
            dest.delete();
            throw new AssertionError("sha mismatch: " + sha);
        }
        System.out.println("step4 sha256 OK: " + sha);
        System.out.println("ATOMIC_EXTRACT_OK");
    }
}
