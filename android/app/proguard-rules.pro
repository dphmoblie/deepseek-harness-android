-keep class io.deepseekharness.mobile.shizuku.DeviceShellUserService { *; }
-keep class io.deepseekharness.mobile.shizuku.** extends android.os.Binder { *; }

# 保护 JNI 绑定类与所有带有 native 修饰符的方法，防止 R8 激进优化重命名破坏符号解析
-keep class io.deepseekharness.mobile.runtime.NativePty { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
