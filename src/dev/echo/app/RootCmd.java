package dev.echo.app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** root 命令封装（KernelSU 授权后可用）。失败返回 null，调用方自行降级。 */
public final class RootCmd {

    private static final String TAG = "echo-root";
    private static Boolean hasRoot;

    private RootCmd() {
    }

    public static synchronized boolean available() {
        if (hasRoot == null) {
            String out = exec("id", 5);
            hasRoot = out != null && out.contains("uid=0");
            Log.i(TAG, "root 可用: " + hasRoot);
        }
        return hasRoot;
    }

    /** 执行 su -c 命令，返回 stdout（含 stderr 尾部），失败/超时返回 null */
    public static String exec(String command, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder("su", "-c", command).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                    if (System.currentTimeMillis() > deadline) break;
                }
            }
            boolean finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            return out.toString();
        } catch (Throwable error) {
            Log.w(TAG, "exec failed: " + error);
            return null;
        }
    }

    /** root 拷贝文件到回声私有目录（可被 SQLiteDatabase 打开的位置） */
    public static boolean copyToAppData(String srcAbs, String dstAbs) {
        String out = exec("cat '" + srcAbs + "' > '" + dstAbs + "'", 10);
        boolean ok = out != null;
        Log.i(TAG, "copy " + srcAbs + " → " + (ok ? "ok" : "fail"));
        return ok;
    }
}
