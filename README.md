# MobileCut 0.0.1

原生 Android 视频剪辑应用，所有素材在手机本地处理，无需登录、联网或存储权限。

## 功能

- 从 Android 系统文件选择器导入视频
- 视频播放、暂停、进度拖动及素材时长显示
- 自动提取八张时间轴缩略图
- 单个视频片段的起点、终点调整，显示片段时长
- 通过 Android MediaExtractor / MediaMuxer 无损复制视频和兼容音轨
- MP4 保存到相册的 **Movies/MobileCut**，并支持系统分享
- 简洁深色中文界面

0.0.1 暂不支持多视频拼接、字幕、配音、变速及视频特效。当前版本采用关键帧对齐裁剪，**实际输出起点可能早于滑块指定时间**；部分视频编解码格式或音轨可能无法无损封装为 MP4。

## 技术

| 项目 | 参数 |
|---|---|
| 语言 | Java 17 |
| Android Gradle Plugin | 8.9.2 |
| Gradle | 8.11.1 |
| Android API | 29 ~ 35（编译 SDK 35） |
| 应用包名 | com.mobilecut.editor |
| 应用版本 | 0.0.1（versionCode 1） |

## 构建与下载

进入 [Actions](https://github.com/WehaoColion/MobileCut/actions) 查看 `Android APK` 工作流。每次向 `main` 推送代码会先运行单元测试，再构建调试版 APK，并将 `MobileCut-0.0.1-debug` 上传至构建产物。首次成功构建会创建 [v0.0.1 预发布](https://github.com/WehaoColion/MobileCut/releases/tag/v0.0.1)，供直接下载 APK。

本工程没有提交 Gradle wrapper JAR。CI 通过 Gradle Actions 安装 Gradle 8.11.1；在本地 Android Studio 选择 Gradle 8.11.1 和 JDK 17 后打开工程，也可在命令行执行：

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

调试包采用 Android 开发证书签名，仅供测试。正式版需要统一签名证书并完成设备兼容性、长视频性能、音视频同步与崩溃测试。
