package io.deepseekharness.mobile.virtualscreen;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Surface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.deepseekharness.mobile.runtime.RuntimeFailure;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 使用真实 ImageReader 验证静止帧截图；无需 Shizuku 或启动目标应用。 */
@RunWith(AndroidJUnit4.class)
public final class VirtualScreenSnapshotTest {
    @Test public void staticFrameIsCapturedImmediatelyAndCachedUntilImageChanges() throws Exception {
        ShellVirtualScreen shell = new ShellVirtualScreen();
        ImageReader reader = ImageReader.newInstance(320, 320, PixelFormat.RGBA_8888, 3);
        set(shell, "reader", reader);
        set(shell, "width", 320);
        set(shell, "height", 320);
        try {
            publish(shell, reader, Color.BLACK);
            assertNull("预览采集不能提前分配 CPU 位图", get(shell, "frame"));
            byte[] first = capture(shell, reader);
            assertPixels(first, Color.BLACK);
            assertSame("静止页应复用已经编码的 PNG", first, capture(shell, reader));
            publish(shell, reader, Color.RED);
            assertNull("新画面必须作废旧截图", get(shell, "encodedFrame"));
            assertEquals("采样只能发生在请求截图时", 0L, get(shell, "frameSampledAt"));
            byte[] second = capture(shell, reader);
            assertNotSame(first, second);
            assertPixels(second, Color.RED);
        } finally {
            shell.close();
        }
    }

    @Test public void oldReaderCannotCaptureTheNextSession() throws Exception {
        ShellVirtualScreen shell = new ShellVirtualScreen();
        try (ImageReader old = ImageReader.newInstance(320, 320, PixelFormat.RGBA_8888, 3);
             ImageReader next = ImageReader.newInstance(320, 320, PixelFormat.RGBA_8888, 3)) {
            set(shell, "reader", next);
            try {
                capture(shell, old);
                fail("旧会话不得读取新会话画面");
            } catch (InvocationTargetException error) {
                assertTrue(error.getCause() instanceof RuntimeFailure);
                assertEquals("VIRTUAL_SCREEN_STOPPED", ((RuntimeFailure) error.getCause()).getCode());
            } finally {
                set(shell, "reader", null);
                shell.close();
            }
        }
    }

    private static void publish(ShellVirtualScreen shell, ImageReader reader, int color) throws Exception {
        Object previous = get(shell, "latestImage");
        Surface surface = reader.getSurface();
        Canvas canvas = surface.lockCanvas(null);
        try {
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint();
            paint.setColor(color);
            canvas.drawRect(0, 0, 160, 320, paint);
        } finally {
            surface.unlockCanvasAndPost(canvas);
        }
        Method collect = ShellVirtualScreen.class.getDeclaredMethod("collectFrame", ImageReader.class);
        collect.setAccessible(true);
        long deadline = SystemClock.elapsedRealtime() + 2000L;
        do {
            collect.invoke(shell, reader);
            if (get(shell, "latestImage") != previous) return;
            SystemClock.sleep(10L);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("ImageReader 未收到测试画面");
    }

    private static byte[] capture(ShellVirtualScreen shell, ImageReader reader) throws Exception {
        Method method = ShellVirtualScreen.class.getDeclaredMethod("awaitFrame", ImageReader.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(shell, reader);
    }

    private static void assertPixels(byte[] png, int color) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertNotNull(bitmap);
        try {
            assertEquals(color, bitmap.getPixel(80, 160));
            assertEquals(Color.WHITE, bitmap.getPixel(240, 160));
        } finally {
            bitmap.recycle();
        }
    }

    private static Object get(ShellVirtualScreen shell, String name) throws Exception {
        Field field = ShellVirtualScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(shell);
    }

    private static void set(ShellVirtualScreen shell, String name, Object value) throws Exception {
        Field field = ShellVirtualScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(shell, value);
    }
}
