package io.deepseekharness.mobile.shizuku;

import io.deepseekharness.mobile.shizuku.IDeviceShellCallback;

interface IDeviceShellService {
    String createSession(int columns, int rows, in IDeviceShellCallback callback);
    void write(String sessionId, in byte[] data);
    void resize(String sessionId, int columns, int rows);
    void closeSession(String sessionId);
    void closeAll();
    void destroy();
}
