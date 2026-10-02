package io.deepseekharness.mobile.virtualscreen;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 设备测试专用目标；独立启动时只依赖系统类，不读取任何用户数据。 */
public final class VirtualScreenProbeActivity extends Activity {
    private int count;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        count = 0;
        save();
        Button button = new Button(this);
        button.setText("副屏测试 · 点击计数 0");
        button.setTextSize(24);
        button.setOnClickListener(view -> {
            count++;
            button.setText("副屏测试 · 点击计数 " + count);
            save();
        });
        setContentView(button);
    }

    private void save() {
        try (FileOutputStream output = openFileOutput("virtual-probe-count", MODE_PRIVATE)) {
            output.write(Integer.toString(count).getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("无法保存测试计数", failure);
        }
    }
}
