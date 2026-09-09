package io.deepseekharness.mobile.shizuku;

import io.deepseekharness.mobile.shizuku.IDeviceShellCallback;

interface IDeviceShellService {
    String createSession(int columns, int rows, in IDeviceShellCallback callback) = 0;
    void write(String sessionId, in byte[] data) = 1;
    void resize(String sessionId, int columns, int rows) = 2;
    void closeSession(String sessionId) = 3;
    void closeAll() = 4;
    // Shizuku reserves this transaction for removing a UserService.
    void destroy() = 16777114;
}
