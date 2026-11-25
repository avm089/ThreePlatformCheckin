# 网址百宝箱（WebBox）

一款面向 Android 的本地网址收藏与内置浏览器应用。

## 功能
- 添加、编辑、删除网站
- 网站分类、搜索、收藏
- 内置 WebView 浏览器
- 本地保存数据（SharedPreferences）
- 默认提供小黑盒、快手、抖音快捷入口
- GitHub Actions 自动构建 Debug APK

## 构建环境
- Android Studio / Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- compileSdk 35 / minSdk 23

## 本地构建
```bash
gradle :app:assembleDebug
```
APK 输出位置：
`app/build/outputs/apk/debug/app-debug.apk`

也可以在 GitHub Actions 页面手动运行 **Build WebBox APK**，下载 `WebBox-debug-apk` 工件。

## 安全与隐私
网站列表保存在本机，不会由本应用上传。内置浏览器会加载用户主动打开的网站；网站自身可能按其隐私政策处理浏览数据。
