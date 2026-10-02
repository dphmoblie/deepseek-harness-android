import android.os.Binder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import io.deepseekharness.mobile.virtualscreen.ShellVirtualScreen;
import java.io.FileOutputStream;
import org.json.JSONObject;

/** 通过 adb shell / app_process 在 shell UID 验证副屏核心；不用 root，不替代 Shizuku 绑定测试。 */
public final class VirtualScreenDeviceProbe {
    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        int exit = 0;
        ShellVirtualScreen screen = new ShellVirtualScreen();
        try {
            String primaryBefore = primaryActivity();
            for (boolean landscape : new boolean[]{false, true}) {
                int width = landscape ? 1280 : 726;
                int height = landscape ? 580 : 1600;
                long before = android.os.SystemClock.elapsedRealtime();
                JSONObject state = new JSONObject(screen.start(
                    "io.deepseekharness.mobile.test/io.deepseekharness.mobile.virtualscreen.VirtualScreenProbeActivity",
                    width, height, landscape ? 256 : 320, new Binder()));
                if (state.getInt("displayId") <= 0) throw new AssertionError("副屏编号无效");
                String session = state.getString("sessionId");
                long started = android.os.SystemClock.elapsedRealtime();
                Thread.sleep(1000);
                String destination = "/data/local/tmp/dsh-virtual-probe-" + (landscape ? "landscape" : "portrait") + ".png";
                int bytes;
                try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(screen.snapshot(session));
                     FileOutputStream output = new FileOutputStream(destination)) {
                    byte[] data = input.readAllBytes();
                    if (data.length < 8 || data[0] != (byte)0x89 || data[1] != 0x50) throw new AssertionError("截图不是 PNG");
                    bytes = data.length;
                    output.write(data);
                }
                long observed = android.os.SystemClock.elapsedRealtime();
                screen.action(new JSONObject().put("sessionId", session).put("action", "tap").put("x", width / 2).put("y", height / 2).toString());
                Thread.sleep(300);
                Process count = new ProcessBuilder("/system/bin/run-as", "io.deepseekharness.mobile.test", "cat", "files/virtual-probe-count").start();
                String value = new String(count.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (count.waitFor() != 0 || !value.equals("1")) throw new AssertionError("副屏点击没有命中测试应用");
                long verified = android.os.SystemClock.elapsedRealtime();
                if (!primaryBefore.equals(primaryActivity())) throw new AssertionError("主屏任务发生变化：" + primaryBefore + " → " + primaryActivity());
                // 静止应用不再提交新缓冲区，也应该能读取最近一帧。
                Thread.sleep(3500);
                try (ParcelFileDescriptor.AutoCloseInputStream still = new ParcelFileDescriptor.AutoCloseInputStream(screen.snapshot(session))) {
                    if (still.readAllBytes().length < 8) throw new AssertionError("静止副屏截图失效");
                }
                System.out.println(new JSONObject().put("orientation", landscape ? "横屏" : "竖屏")
                    .put("displayId", state.getInt("displayId")).put("pngBytes", bytes)
                    .put("startMs", started - before).put("tapAndVerifyMs", verified - observed).put("count", value)
                    .put("primaryUnchanged", true).put("staticFrameReadable", true).put("independentFocusRequested", state.getBoolean("independentFocusRequested")));
                screen.close();
                if (screen.state().getBoolean("active")) throw new AssertionError("副屏没有释放");
                boolean rejected = false;
                try { screen.action(new JSONObject().put("sessionId", session).put("action", "back").toString()); }
                catch (IllegalStateException expected) { rejected = true; }
                if (!rejected) throw new AssertionError("过期会话仍可操作");
            }
        } catch (Throwable error) {
            exit = 1;
            error.printStackTrace();
        } finally {
            screen.close();
        }
        System.exit(exit);
    }

    private static String primaryActivity() throws Exception {
        Process process = new ProcessBuilder("/system/bin/dumpsys", "activity", "activities").start();
        String dump = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError("不能检查主屏任务");
        boolean primary = false;
        for (String line : dump.split("\n")) {
            if (line.trim().startsWith("Display #")) primary = line.trim().startsWith("Display #0 ");
            if (primary && line.matches(".*\\b(?:mResumedActivity|topResumedActivity)[:=].*")) return line.substring(line.indexOf("ActivityRecord{")).trim();
        }
        throw new AssertionError("未找到主屏活动");
    }
}
