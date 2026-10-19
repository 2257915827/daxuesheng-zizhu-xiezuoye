# AI 屏幕助手

## 直接下载 APK

[点击下载最新 APK](https://github.com/2257915827/daxuesheng-zizhu-xiezuoye/raw/refs/heads/master/app/build/outputs/apk/debug/app-debug.apk)

下载后在 Android 手机上打开 APK，允许安装未知来源应用即可安装。安装后需要授权录屏和无障碍服务。

Android 10–16 的内部测试 MVP。当前包含：

- Jetpack Compose 控制界面
- MediaProjection 单帧截图捕获
- AccessibilityService 节点读取、点击、输入、滚动和返回动作
- 白名单动作模型与低置信度拒绝
- 可替换的云端 `AiClient` / HTTP multipart 适配器
- 默认不持久化屏幕内容，停止时清理临时截图

## 构建

使用 Android Studio 打开项目根目录，安装 Android SDK 35 后执行 `app` 的 debug 构建。当前环境没有 Gradle 命令或 Gradle Wrapper，因此无法在此工作区直接执行编译。

## 接入 AI 服务

实现 `AiClient` 后注入 `TaskViewModel`，或使用 `HttpAiClient(baseUrl, token)`。服务端需要提供：

- `POST /analyzeScreen`
- `POST /createActionPlan`
- `POST /verifyAction`

响应必须符合 `AiResponse`，动作仅允许 `TAP`、`INPUT_TEXT`、`SCROLL`、`SELECT_OPTION`、`BACK`、`WAIT`。生产接入前应增加服务端鉴权、限流、TLS 证书校验和不落盘策略。

## 当前限制

首版已建立安全边界和系统能力，但 AI 协调循环、前台录屏服务、真实 API 配置和端到端设备测试仍需要接入具体后端后完成；默认 `UnconfiguredAiClient` 不会猜测或执行任何动作。
