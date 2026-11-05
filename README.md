# GalMagan

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/galmagan_icon.png" alt="GalMagan 应用图标" width="132" />
</p>

<p align="center">
  <strong>让每一次回复，都更贴近你想表达的样子。</strong><br />
  面向 Android 的聊天理解与回复辅助工具：结合当前对话、联系人背景和你的表达风格，提供可编辑、由你决定是否发送的候选回复。
</p>

<p align="center">
  <a href="https://github.com/yu821138652/GalMagan/tree/v2.0.8"><img alt="稳定版" src="https://img.shields.io/badge/稳定版-2.0.8-5367D5" /></a>
  <img alt="Android 11+" src="https://img.shields.io/badge/Android-11%2B-3A9B72" />
  <img alt="架构" src="https://img.shields.io/badge/架构-arm64--v8a-697586" />
  <a href="LICENSE"><img alt="许可证" src="https://img.shields.io/badge/license-MIT-8A5CC5" /></a>
</p>

<p align="center">
  <a href="https://github.com/yu821138652/GalMagan/blob/v2.0.8/apk/GalMagan-2.0.8-debug.apk">下载稳定版 APK</a>
  · <a href="STABLE.md">版本与验证记录</a>
  · <a href="CHANGELOG.md">更新日志</a>
</p>

<p align="center">
  <a href="#功能">功能</a> · <a href="#下载与安装">下载与安装</a> · <a href="#常见问题">常见问题</a> · <a href="#隐私与权限">隐私与权限</a> · <a href="#开发者">开发者</a>
</p>

> **当前稳定版：2.0.8**。已在荣耀 Magic7、Android 16、微信 8.0.80 上验证可以读取微信聊天消息。安装包为 Debug APK，下载后请核对 [SHA-256](apk/GalMagan-2.0.8-debug.apk.sha256)。

## GalMagan 是什么

GalMagan 使用 Android 无障碍服务读取当前聊天界面可见的消息，并把对话整理成情境分析、回复策略和候选回复。它适用于希望理解聊天上下文、减少说话人混淆，并让候选回复更贴近个人表达习惯的场景。

项目目前主要面向微信和小红书。不同应用、系统版本和页面布局提供的无障碍信息不同，实际读取效果可能有差异；目前明确完成的微信真机验证范围见上方稳定版说明。

## 功能

| 能力 | 说明 |
| --- | --- |
| 情境与风险分析 | 依次分析对话、评估 0–9 级风险、形成回复策略，再生成并检查候选回复。 |
| 回复方向 | 可选择默认、安抚与化解冲突、自然延续话题或增进好感。 |
| 本次补充 | 可补充事实或表达偏好，并在本轮分析中继续调整；补充不会写入聊天历史。 |
| 联系人知识库 | 记录联系人背景、全局说话风格，以及面向特定联系人的风格。 |
| 历史记录 | 可预加载和自动更新聊天记录，按联系人及聊天环境保存；分析时合并同一联系人的跨环境历史。 |
| 人工把关 | 候选只会填入聊天输入框，不会自动发送；最终内容由你检查并决定。 |
| 故障诊断 | 提供分析停止、界面刷新、识别状态和可复制的错误反馈。 |

## 版本沿革

| 版本 | 类型 | 相较前一版本的主要变化 |
| --- | --- | --- |
| [2.0.8](docs/releases/2.0.8.md) | 当前稳定版 | 相较 1.6.4 改用通用模型串行完成分析、策略、候选和检查；增加聊天方向及本次补充，并修复微信节点读取回归。 |
| [1.6.4](docs/releases/1.6.4.md) | 阶段归档 | 相较 1.6.0 增加按联系人和环境保存的历史、预加载进度、小红书节点优先读取及历史编辑/批量删除。 |
| [1.6.0](docs/releases/1.6.0.md) | 历史稳定版 | 相较 1.5.2 重点重整 GalMagan 品牌、首页、设置、知识库和悬浮分析界面。 |

标签和归档状态说明见 [STABLE.md](STABLE.md)，完整逐版本变化见 [CHANGELOG.md](CHANGELOG.md)。

## 下载与安装

1. 下载 [GalMagan 2.0.8 APK](https://github.com/yu821138652/GalMagan/blob/v2.0.8/apk/GalMagan-2.0.8-debug.apk)，并按需核对 [SHA-256 校验值](apk/GalMagan-2.0.8-debug.apk.sha256)。
2. 在 Android 设置中为 GalMagan 开启无障碍服务和“显示在其他应用上层”权限。
3. 打开应用，在设置中配置 OpenAI 兼容接口地址、自己的 API 密钥和模型 ID。新安装默认模型为 `gpt-6-luna`；具体模型需由你所用的接口服务支持。
4. 在微信或小红书的聊天页面打开 GalMagan 悬浮窗，选择联系人或“默认”，再开始分析。

微信自动截图默认关闭。手动“截屏识别一次”仍可使用；识别聊天内容优先依赖无障碍节点。预加载历史时，请选择联系人和聊天环境，并确认应用显示完成状态。

## 常见问题

**为什么需要开启无障碍权限？**

GalMagan 通过 Android 无障碍服务读取当前聊天页面提供的可见文本，用于识别说话人和整理上下文。它不读取聊天应用的内部数据库。

**聊天内容会发送到哪里？**

分析上下文会发送到设置中配置的模型服务。请使用你信任的服务，并查看其数据处理政策；API 密钥由你在应用内配置。

**为什么有时读不到消息？**

聊天应用和系统版本可能提供不同的无障碍节点。当前确认的微信验证组合是荣耀 Magic7、Android 16、微信 8.0.80；其他设备或版本需要单独验证。识别失败时，可在应用内复制诊断信息用于排查。

**候选回复会自动发出去吗？**

不会。候选只会填入输入框，发送前由你检查并操作。

## 隐私与权限

- 无障碍权限用于读取当前聊天界面可见内容。应用不读取微信或小红书的内部数据库，也不修改这些应用。
- 联系人资料、历史记录和风格档案保存在 GalMagan 的应用私有目录中。
- 分析时，对话上下文会发送到你配置的模型服务。请先了解该服务商的数据处理规则；项目不会内置你的 API 密钥。
- 候选回复不会自动发送。应用不会把“候选已生成”当作用户同意发送。
- 微信自动截图关闭；是否发起手动截图由用户操作。

## 开发者

GalMagan 是 Android-only 的个人维护项目。欢迎查看源码、运行测试或基于许可证要求进行修改；上游出处和再分发说明见 [NOTICE](NOTICE) 与 [LICENSE](LICENSE)。

### 本地构建与测试

需要 JDK 17、Android SDK 35 和 Gradle Wrapper 所需依赖：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。稳定版 APK 与校验文件位于 `apk/`；其他构建产物和缓存不纳入版本控制。最近一次稳定版验证见 [2.0.8 归档记录](docs/releases/2.0.8.md)。

### 仓库导览

```text
app/                 Android 应用源码、资源与单元测试
docs/releases/       版本归档与验证记录
docs/history/        已完成开发阶段的技术记录
tools/jev/           Jev 辅助评估工具及说明
apk/                 当前稳定版 APK 与校验值
```

- [STABLE.md](STABLE.md)：当前稳定版本及历史基线
- [CHANGELOG.md](CHANGELOG.md)：版本变化记录
- [docs/README.md](docs/README.md)：补充文档索引
- [NOTICE](NOTICE) 与 [LICENSE](LICENSE)：上游出处、版权与许可证信息

## 项目来源

GalMagan 由 [yu821138652](https://github.com/yu821138652) 个人维护，基于 [Finderchangchang/jev-chat-JARVIS](https://github.com/Finderchangchang/jev-chat-JARVIS) 二次开发。项目与上游作者没有官方从属关系；使用和再分发请遵守 [NOTICE](NOTICE) 与 [LICENSE](LICENSE) 中的要求。
