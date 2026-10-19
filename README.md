# AI 悬浮答案助手

## 直接下载 APK

上个版本：[下载稳定版 APK](https://github.com/2257915827/daxuesheng-zizhu-xiezuoye/raw/refs/heads/master/app/build/outputs/apk/debug/app-debug.apk)

本次更新：[下载测试版 APK（0.2.0-test）](https://github.com/2257915827/daxuesheng-zizhu-xiezuoye/raw/refs/heads/master/app/build/outputs/apk/debug/app-test.apk)

下载后在 Android 手机上打开 APK，并允许安装未知来源应用。应用面向 Android 10+ 内部测试。

## 配置 OpenAI-compatible API

在“OpenAI 兼容 API”区域填写服务根地址或以 `/v1` 结尾的地址、Token，然后点击“拉取模型”。选择支持图像输入的模型并保存；“测试连接”只验证 `/v1/models`，不调用推理接口。

例如 `https://example.com` 会规范化为 `https://example.com/v1`。识题请求使用 Bearer Token 和多模态 `POST /chat/completions`。模型必须支持图像输入。

## 使用悬浮助手

保存 API 配置后，点“允许悬浮窗并开启助手”，在 Android 设置中允许“显示在其他应用上层”，返回后再次点开启并确认系统录屏。悬浮助手显示为渐变球形按钮：单点截图识题，长按打开功能面板；拖到屏幕边缘后会自动收进去，点露出的球形边缘即可弹出。用户可在面板中选择并打开已安装应用、复制答案、再拍一题或关闭悬浮助手（关闭时同时停止录屏）。每次只分析一屏，不会自动点击、填写、翻页或提交。

选择题答案会在气泡上显示 5 秒，之后可点气泡打开答案面板；填空题和简答题会直接展开面板。模型只返回最终答案，不提供原理解析；若题干可判断而可见选项不完整且正确答案不在可见选项中，会提示正确答案未显示。面板提供复制、再拍、跳转应用和关闭功能。置信度不足或 API 出错时会显示错误并允许重试。通知栏或助手页面的“停止辅助”会移除悬浮窗并停止录屏。

固定识题指令作为稳定的 system 提示词发送，图片单独放在 user 消息中；应用会限制答案输出长度，并在答案面板显示 API 返回的缓存命中 token 数（若服务返回该字段）。截图每次不同，因此缓存命中不保证发生。

## 隐私说明

每次识题都会将当前截图发送到用户配置的 AI 服务。截图和答案只在内存中处理，默认不保存。录屏授权会在辅助运行期间持续有效，停止辅助后结束。请勿在包含密码、支付或其他敏感信息的页面使用。

## 构建

使用 Android Studio 打开项目并安装 Android SDK 与所需许可，然后运行 `gradle assembleDebug` 或 Android Studio 的 debug 构建。APK 路径为 `app/build/outputs/apk/debug/app-debug.apk`。
