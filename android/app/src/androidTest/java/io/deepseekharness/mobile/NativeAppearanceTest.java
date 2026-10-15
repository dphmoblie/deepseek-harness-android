package io.deepseekharness.mobile;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 无需 Root、Shizuku 或真实悬浮窗权限，验证服务原位刷新不会重建 WebView 或输入。 */
@RunWith(AndroidJUnit4.class)
public final class NativeAppearanceTest {
    @Test public void floatingConversationRefreshesLanguageAndThemeWithoutReplacingInput() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences language = app.getSharedPreferences("app_language", Context.MODE_PRIVATE);
        SharedPreferences theme = app.getSharedPreferences("app_theme", Context.MODE_PRIVATE);
        String previousLanguage = language.getString("language", null);
        String previousTheme = theme.getString("mode", null);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                WebView web = null;
                try {
                    // 系统英语、应用中文：原生服务也必须尊重应用语言，而不是系统 locale。
                    Configuration config = new Configuration(app.getResources().getConfiguration());
                    config.setLocale(Locale.ENGLISH);
                    Context base = app.createConfigurationContext(config);
                    language.edit().putString("language", "zh-CN").commit();
                    theme.edit().putString("mode", "light").commit();
                    OverlayBallService service = new OverlayBallService();
                    Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                    attach.setAccessible(true);
                    attach.invoke(service, base);
                    Context themed = new ContextThemeWrapper(base, R.style.AppTheme);
                    LinearLayout panel = new LinearLayout(themed);
                    panel.setBackground(new GradientDrawable());
                    TextView title = new TextView(themed);
                    title.setTag(R.string.overlay_conversation_title);
                    title.setClickable(true);
                    EditText input = new EditText(themed);
                    input.setText("unsent input");
                    web = new WebView(themed);
                    panel.addView(title);
                    panel.addView(input);
                    panel.addView(web);
                    FrameLayout handle = new FrameLayout(themed);
                    TextView grip = new TextView(themed);
                    grip.setBackground(new GradientDrawable());
                    handle.addView(grip);
                    setField(service, "conversationView", panel);
                    setField(service, "conversationWebView", web);
                    setField(service, "conversationResizeHandle", handle);
                    Method refresh = OverlayBallService.class.getDeclaredMethod("refreshVisibleAppearance");
                    refresh.setAccessible(true);
                    refresh.invoke(service);
                    assertEquals("Harness AI 对话", title.getText().toString());
                    int lightInk = title.getCurrentTextColor();
                    language.edit().putString("language", "en").commit();
                    theme.edit().putString("mode", "dark").commit();
                    refresh.invoke(service);
                    assertEquals("Harness AI chat", title.getText().toString());
                    assertEquals(title.getText(), title.getContentDescription());
                    assertNotEquals(lightInk, title.getCurrentTextColor());
                    assertEquals(title.getCurrentTextColor(), grip.getCurrentTextColor());
                    assertEquals("Drag to resize the chat window", handle.getContentDescription());
                    assertEquals("unsent input", input.getText().toString());
                    assertSame(web, panel.getChildAt(2));
                    assertSame(web, getField(service, "conversationWebView"));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                } finally {
                    if (web != null) web.destroy();
                }
            });
        } finally {
            restore(language, "language", previousLanguage);
            restore(theme, "mode", previousTheme);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void restore(SharedPreferences preferences, String name, String value) {
        SharedPreferences.Editor editor = preferences.edit();
        if (value == null) editor.remove(name); else editor.putString(name, value);
        editor.commit();
    }
}
