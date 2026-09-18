# 局域网 NTP 校时

一个极简的安卓校时工具 App，唯一功能：**从局域网 NTP 服务器获取准确时间，
并尝试同步 Android 设备的系统时间**。

- **多 NTP 服务器**：可添加多个地址（默认 `ntp.aliyun.com`，支持 `IP` / `IP:端口` / 域名），
  列表可上移 / 下移排序，同步时按顺序逐个尝试，第一个成功的即用于本次校时
- **“同步时间”按钮**（同步中显示进度圈并变为“正在同步...”，防止重复点击）
- **当前设备时间**（每秒刷新）、**NTP 服务器时间**、**时间偏差**、**网络延迟**、**同步状态**
- 每次打开 App 自动读取上次保存的地址并同步一次
- 严格区分“NTP 获取成功”和“系统时间修改成功”，**绝不伪造“同步成功”**

无登录、无广告、无后台服务器、无数据库、无统计埋点，权限只保留 `INTERNET`。

## 功能

| 功能 | 说明 |
| --- | --- |
| NTP 查询 | 标准 NTP over UDP 123，自研轻量客户端（`NtpClient` + `NtpPacket`），按 RFC 5905 计算 offset / delay；多服务器按序优选 |
| 精度处理 | T1 用 `System.currentTimeMillis`，T4 用 `elapsedRealtime` 差值推算，避免收包期间时钟跳变污染 RTT |
| 系统时间同步 | `DeviceOwnerTimeSynchronizer` 经 `DevicePolicyManager.setTime()` 修改（需 Device Owner，API 26+） |
| 诚实上报 | 无权限时明确显示“当前应用没有修改系统时间的权限”，并照常展示 NTP 时间 / 本机时间 / 偏差 |
| 地址存档 | DataStore 保存服务器地址列表（含顺序），下次启动自动使用；老版本单地址自动迁移 |
| 自动同步 | 打开 App 即按顺序同步一次；超时 3000ms；所有网络操作在 `Dispatchers.IO`，异常全部兜底 |

## NTP 同步工作流程

```text
启动 App
  ↓
读取 DataStore 保存的 NTP 地址列表（默认 ntp.aliyun.com，缺省端口 UDP 123）
  ↓
按顺序逐个 UDP 发送 NTP 请求，第一个成功的服务器用于本次校时
（T1/T2/T3/T4，失败的自动试下一个）
  ↓
offset = ((T2-T1) + (T3-T4)) / 2，delay = (T4-T1) - (T3-T2)
  ↓
目标时间 = 本机此刻 + offset
  ↓
检查 isDeviceOwnerApp() → 是则 setTime() → 重读时钟二次验证
  ↓
界面显示：NTP 时间 / 本机时间 / 偏差 / 延迟 / 真实同步结果
```

## 本地编译（可选）

需要 **JDK 17 + Android SDK**。用 Android Studio 打开项目直接运行即可
（Android Studio 会自动生成 gradle wrapper）；或使用已安装的 Gradle 8.x：

```bash
gradle assembleDebug
```

```bash
gradle assembleRelease
```

单元测试（NTP 报文解析 / offset-delay 公式 / 地址解析）：

```bash
gradle :app:testDebugUnitTest
```

## APK 位置

```text
app/build/outputs/apk/debug/    # 调试包
app/build/outputs/apk/release/  # 发布包（当前用 debug 签名，保证可直接安装）
```

## 获取 APK（GitHub Actions 编译）

1. 把本仓库推到 GitHub；
2. 打开仓库的 **Actions** 标签页 → 选择 **Build APK** 工作流 → 点 **Run workflow**
   （push / PR 也会自动触发）；
3. 构建完成后，进入该次运行的 **Summary** 页面，在 **Artifacts** 区域下载
   `ntp-sync-apk`（发布包）或 `ntp-sync-debug-apk`（调试包）；
4. 非 PR 构建还会自动发布 GitHub Release，手机点链接直接下载安装。

> release 包当前使用 debug 签名（便于直接安装），正式发布请自行配置 keystore。
> 工作流文件位置：`.github/workflows/build.yml`。

## Device Owner 说明

**普通第三方 Android 应用通常不能直接修改系统时间。**
`SET_TIME` 是系统级权限，不授予普通 APK；通过 Shell / 辅助功能 / 悬浮窗等手段
绕过都属于破解系统安全机制，本项目一概不做。

**Device Owner 模式可以获得企业设备管理场景下所需的系统级能力。**
本 App 已预置 `NtpDeviceAdminReceiver` + `res/xml/device_admin.xml` + Manifest 声明，
并在代码中用 `DevicePolicyManager.isDeviceOwnerApp()` + `setTime()` 实现改时，
无权限时如实返回 `PermissionDenied`，绝不报假成功。

测试环境配置 Device Owner（需出厂重置后、未登录任何账号的全新设备）：

```bash
# 1. 安装 App 后，在配网完成前执行（设备必须是没有配置过账号的新设备/刚重置）：
adb shell dpm set-device-owner com.example.ntpsync/.NtpDeviceAdminReceiver

# 2. 成功后打开 App 点“同步时间”，即可真正修改系统时间
```

> ADB 仅用于设备初始化/测试阶段；APK 日常运行不依赖 ADB。

普通 Android 设备（非 Device Owner）上的表现：NTP 时间照常获取并显示偏差，
状态明确提示“当前应用没有修改系统时间的权限”，不会显示“同步成功”。

## 版本

- compileSdk / targetSdk：35
- minSdk：26（`setTime()` 与 `java.time` 均需 API 26+）
- Android Gradle Plugin：8.7.3
- Kotlin：2.1.0
- Gradle（CI）：8.11.1
- UI：Jetpack Compose + Material 3，协程处理异步
