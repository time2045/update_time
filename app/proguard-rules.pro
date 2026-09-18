# NTP 校时 App 的 R8 混淆/压缩规则（极简，只保留反射相关的入口）
# Compose / Material3 / DataStore 等官方库自带 consumer 规则，无需在此重复声明。

# SyncViewModel 通过 ViewModelProvider 反射构造（Application 参数构造器），必须保留。
-keep public class com.example.ntpsync.SyncViewModel {
    public <init>(android.app.Application);
}

# 设备管理接收器（Manifest 引用，AGP 会自动保留，此处显式声明双保险）。
-keep public class com.example.ntpsync.NtpDeviceAdminReceiver {
    *;
}
