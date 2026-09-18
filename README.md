# 局域网 NTP 校时

一个极简的安卓校时工具 App：**从 NTP 服务器获取准确时间，
帮你把 Android 设备的系统时间对准**。

## 普通用户 3 步（不用电脑，不用任何授权）

实话：普通手机上任何 App 都改不了系统时间，所以流程是：

1. 打开 App，点「获取准确时间」，记住屏幕上的准确时间；
2. 点「去系统设置改时间」，关掉「自动确定日期和时间」；
3. 照着准确时间手动填进去（可先点「复制准确时间」）。

有网默认用阿里云（`ntp.aliyun.com`），在局域网且配好 192.168.1.100
的去「高级设置」里添加即可。

- **多 NTP 服务器**：可添加多个地址（默认 `ntp.aliyun.com`，支持 `IP` / `IP:端口` / 域名），
  列表可上移 / 下移排序，同步时按顺序逐个尝试，第一个成功的即用于本次校时
- **系统 NTP 一键切换（推荐）**：用电脑执行一次 ADB 授权，之后在 App 里点
  服务器右侧「设为系统」，即可在局域网（`192.168.1.100`）和公网
  （`ntp.aliyun.com`）之间随便切，1 分钟内生效，不用再碰电脑
- **“同步时间”按钮**（同步中显示进度圈并变为“正在同步...”，防止重复点击）
- **当前设备时间**（每秒刷新）、**NTP 服务器时间**、**时间偏差**、**网络延迟**、**同步状态**
- 每次打开 App 自动读取上次保存的地址并同步一次
- 严格区分“NTP 获取成功”和“系统时间修改成功”，**绝不伪造“同步成功”**

无登录、无广告、无后台服务器、无数据库、无统计埋点，权限只保留 `INTERNET`。

## 功能

| 功能 | 说明 |
| --- | --- |
| NTP 查询 | 标准 NTP over UDP 123，自研轻量客户端（`NtpClient` + `NtpPacket`），按 RFC 5905 计算 offset / delay；多服务器按序优选 |
| 系统 NTP 切换 | 一次性 ADB 授权后，App 内一键把系统 NTP 指向任一服务器并触发同步 |
| 精度处理 | T1 用 `System.currentTimeMillis`，T4 用 `elapsedRealtime` 差值推算，避免收包期间时钟跳变污染 RTT |
| 系统时间同步 | `DeviceOwnerTimeSynchronizer` 经 `DevicePolicyManager.setTime()` 修改（需 Device Owner，API 26+） |
| 诚实上报 | 无权限时明确显示“当前应用没有修改系统时间的权限”，并照常展示 NTP 时间 / 本机时间 / 偏差 |
| 地址存档 | DataStore 保存服务器地址列表（含顺序），下次启动自动使用；老版本单地址自动迁移 |
| 自动同步 | 打开 App 即按顺序同步一次；超时 3000ms；所有网络操作在 `Dispatchers.IO`，异常全部兜底 |

## 一次性授权（推荐，多服务器切换用）

原理：`WRITE_SECURE_SETTINGS` 是签名级权限，安装时拿不到，
但可以用 ADB 授予一次。**无需恢复出厂、无需删除账号**，
重启不掉，只有卸载重装才需重授。授权后 App 就能改系统 NTP 服务器，
在局域网和公网之间随便切。

```bash
# 手机开 USB 调试连电脑，执行一次即可
adb shell pm grant com.example.ntpsync android.permission.WRITE_SECURE_SETTINGS
```

之后打开 App：看一眼「系统 NTP」卡片确认已授权 → 点任意服务器右侧
「设为系统」→ 系统在后台完成同步（通常 1 分钟内生效）。

> 说明：曾尝试用 Shizuku 实现“免电脑一键授权”，但查官方源码确认
> Shizuku 13+ 已把“替 App 跑 shell 命令”的 API 设为私有（计划在 14 彻底删除），
> 此路不通，已 revert。免电脑只剩“普通用户 3 步手动填”可用。

不想用 App 也行，直接三行命令（每台设备一次，永久生效）：

```bash
adb shell settings put global ntp_server 192.168.1.100
adb shell settings put global auto_time 0
adb shell settings put global auto_time 1
```

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
