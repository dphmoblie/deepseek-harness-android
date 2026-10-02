package io.deepseekharness.mobile.shizuku;

import io.deepseekharness.mobile.shizuku.IDeviceShellCallback;
import android.os.ParcelFileDescriptor;
import android.os.IBinder;

interface IDeviceShellService {
    String createSession(int columns, int rows, in IDeviceShellCallback callback) = 0;
    void write(String sessionId, in byte[] data) = 1;
    void resize(String sessionId, int columns, int rows) = 2;
    void closeSession(String sessionId) = 3;
    void closeAll() = 4;
    String startVirtualScreen(String component, int width, int height, int dpi, IBinder owner) = 5;
    String virtualScreenState() = 6;
    String virtualScreenAction(String parameters) = 7;
    ParcelFileDescriptor virtualScreenSnapshot(String sessionId) = 8;
    // Shizuku reserves this transaction for removing a UserService.
    void destroy() = 16777114;
}
