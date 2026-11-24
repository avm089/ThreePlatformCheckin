# 三平台签到助手 Android 工程

当前版本提供 Kotlin Android 工程、平台开关、WorkManager 每日后台检查和通知。**尚未接入小黑盒、快手、抖音的真实官方签到接口**，所以目前不能实现真实自动签到，也不会伪造成功结果。

## 本地构建
使用 Android Studio 打开项目，等待 Gradle 同步，然后选择 Build > Build Bundle(s) / APK(s) > Build APK(s)。输出通常位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 云端构建
将整个项目推送到 GitHub 仓库，在 Actions 页面运行 `Build debug APK` 工作流；构建完成后下载名为 `ThreePlatformCheckin-debug-apk` 的 Artifact。

WorkManager 的 24 小时周期任务不是精确闹钟，系统省电策略可能造成延迟。真实签到必须在核实平台官方接口、授权范围和条款后，逐个平台实现适配器；验证码和安全验证需由用户完成。
