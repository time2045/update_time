package com.example.ntpsync

import android.app.admin.DeviceAdminReceiver

/**
 * 设备管理接收器：Device Owner 模式的前置条件。
 *
 * 普通安装时它处于未激活状态，不影响 App 运行；
 * 通过 ADB / 零接触等方式把本应用设为 Device Owner 后，
 * DeviceOwnerTimeSynchronizer 才能真正调用 setTime() 改系统时间。
 */
class NtpDeviceAdminReceiver : DeviceAdminReceiver()
