package io.deepseekharness.mobile.virtualscreen;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 用设备当前尺寸验证副屏选择页；外部测试脚本负责设置与恢复窄屏、宽屏尺寸。 */
@RunWith(AndroidJUnit4.class)
public final class VirtualScreenPageTest {
    @Test public void targetPickerFitsCurrentDisplay() {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), VirtualScreenActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ViewGroup root = activity.findViewById(android.R.id.content);
                Rect visible = new Rect();
                assertTrue("页面必须可见", root.getGlobalVisibleRect(visible));
                assertTrue("页面宽度无效", visible.width() > 0);
                assertTrue("页面高度无效", visible.height() > 0);
                verifyChildren(root, visible, activity.getResources().getDisplayMetrics().density);
            });
        }
    }

    private static void verifyChildren(View view, Rect viewport, float density) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof ListView) {
            assertTrue("应用列表需要保留可操作高度", view.getHeight() >= 80 * density);
        }
        if (view instanceof Button) {
            int[] xy = new int[2];
            view.getLocationOnScreen(xy);
            assertTrue("操作按钮不能横向溢出", xy[0] >= viewport.left && xy[0] + view.getWidth() <= viewport.right);
            assertTrue("操作按钮不能被底部裁切", xy[1] + view.getHeight() <= viewport.bottom);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) verifyChildren(group.getChildAt(i), viewport, density);
        }
    }
}
