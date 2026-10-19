# AI 屏幕助手

## 直接下载 APK

[点击下载最新 APK](https://github.com/2257915827/daxuesheng-zizhu-xiezuoye/raw/refs/heads/master/app/build/outputs/apk/debug/app-debug.apk)

下载后在 Android 手机上打开 APK，并允许安装未知来源应用。应用面向 Android 10+ 内部测试；首次使用时需要按提示启用无障碍并授权录屏。

## 配置 OpenAI-compatible API

在“OpenAI 兼容 API”区域填写服务根地址或以 `/v1` 结尾的地址、Token，然后点击“拉取模型”。选择支持图像输入的模型，点击“保存”；“测试连接”只验证 `/v1/models` 鉴权和可用性，不调用推理接口。

例如 `https://example.com` 会规范化为 `https://example.com/v1`。请求使用 Bearer Token、`GET /models` 和多模态 `POST /chat/completions`。模型是否支持视觉输入需由用户确认。

## 使用辅助

先保存 API 配置并选择手机上的作业应用，点击“启用无障碍并继续”。启用服务并返回后，应用会请求录屏授权；授权完成后打开所选应用并开始监测。状态区分别显示无障碍设置是否启用、服务是否连接以及录屏是否运行。

当前自动化只允许 AI 选择页面中可访问的选项、滚动和点击明确标注的“下一题/继续答题”。置信度不足、控件找不到或页面状态不明确时会暂停。它不会填写自由文本答案，也不会提交、发送或支付。AI 判断题目已完成后会询问是否结束辅助。通知栏和应用内均可停止录屏/辅助。

## 隐私与限制

截图会发送到用户配置的 API 服务进行识别；应用默认不保存截图或题目内容。请只在本人有权使用的应用和内容上启用。不同学习软件暴露给 Android 无障碍服务的控件信息可能不同，需在真机验证；若选项不可访问，助手会暂停而不会猜测点击。

## 构建

使用 Android Studio 打开项目并安装项目要求的 Android SDK 与许可，然后运行 `gradle assembleDebug` 或 Android Studio 的 debug 构建。APK 路径为 `app/build/outputs/apk/debug/app-debug.apk`。
