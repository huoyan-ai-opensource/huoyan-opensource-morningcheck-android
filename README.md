# 火眼晨检开源 App

面向 **食堂、餐饮、食品生产** 场景的 Android 员工晨检客户端。可直接运行完整 App，也可把客户端 JAR 接入自己的 Android 应用。

[English](README.en.md) · [官网与激活联系](https://www.huoyan.cc) · [下载](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android/releases) · [第一次运行](QUICKSTART.md) · [文档导航](docs/README.md)

**源码所有人都可以使用；识别功能需要联系火眼激活配套 SDK。** App 源码、SDK 运行授权和第三方组件的范围分别见[使用与激活说明](USAGE.md)、[第三方组件说明](THIRD_PARTY_NOTICES.md)。

## 选择你的使用方式

| 你要做什么 | 从这里开始 |
| --- | --- |
| 安装并体验完整 App | [快速开始](QUICKSTART.md)：安装 App 与 SDK → 激活选模 → 等模型就绪 → 新增员工 → 录入人脸 → 自动晨检 |
| 在自己的 App 中接入晨检 | [JAR 中文接入指南](huoyanClient/INTEGRATION_GUIDE.md) · [English Integration Guide](huoyanClient/INTEGRATION_GUIDE.en.md) · [Java 示例](huoyanClient/samples/MorningClientExample.java) |
| 修改本项目 | [构建环境](docs/build.md) → [代码结构](docs/code-structure.md) → [测试](docs/testing.md) → [贡献指南](CONTRIBUTING.md) |

完整 App 使用 `cc.huoyan.opensource.morningcheck` 包名，JAR 使用 `cc.huoyan.morningcheck.sdk.client`。晨检 SDK 是另行安装的应用，包名为 `cc.huoyan.morningcheck`；JAR 负责通信，不包含识别引擎或模型。

## 功能与界面

- 员工与部门管理、工号唯一校验、健康证有效期、搜索筛选及分页。
- 同页选人和拍照学习、人脸样本预览、删除单张及清空员工样本。
- 身份识别与测温、口罩和工作帽检查、手背与手心检查；连续晨检支持下一位倒计时和停留查看。
- 本地晨检记录、日期范围与结果筛选、分页查询、详情及采集照片查看。
- USB UVC 与 Camera2 相机接入、测温算法模式切换、串口和 GPIO 补光。
- 语音、字体大小、安全密码与自动进入晨检设置，SDK 本机或局域网连接及激活选模。

| 主页 | 现场晨检 |
| --- | --- |
| ![火眼晨检 App 主页](docs/images/home-landscape.png) | ![火眼晨检 App 现场晨检](docs/images/check-landscape.png) |

以上为 2026-10-29 在 YF023D 上采集的中文横屏界面，公司、场所和设备序列号已替换为虚构示例值；晨检页展示等待设备的引导画面，不含真人照片。中英文 README 使用相同的双列排列，截图时安装的 App 为 0.1.0、SDK 为 2.0.0，来源见[截图说明](docs/images/README.md)。横竖屏设计维护在[设计源文件](design/huoyan-morningcheck.pen)中。

## 下载与激活

App APK、客户端 JAR、交付 ZIP、中英文 Word 接入指南和 SHA-256 摘要通过 [GitHub Releases](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android/releases) 提供。ZIP 内和 Assets 中的两份 Word 均使用项目原始文件名，交付约定见[发布规则](docs/releasing.md)。也可按[构建指南](docs/build.md)从源码构建。

晨检 SDK 的当前默认[下载入口](http://f.huoyan.cc/apk/morningcheck_sdk_lastest.apk)也已集成在 App 安装引导中。下载前通过[官网](https://www.huoyan.cc)联系火眼，确认设备、芯片、包名登记、激活和模型支持。下载入口沿用既有文件名 `lastest`，不表示任意版本都配套；版本关系见[兼容说明](docs/version-compatibility.md)。

连接成功、初始化成功和激活成功都不能单独证明模型可用。客户端以 `client.isReady()` 检查连接、`READY` / `OFFLINE` 状态、模型就绪、证书有效及密钥未丢失；命令能否执行仍以 SDK 回执为准。`OFFLINE` 满足这些条件时可本地晨检，不代表云端功能可用，完整步骤见[快速开始](QUICKSTART.md)。

晨检规则决定是否测温和检查口罩、工作帽；关闭测温会记录“未测”。记录与照片目前仅保存在本机，暂不提供记录上传、补充照片、人工复核或 App 在线更新。

## 构建与测试

需要 JDK/JBR **17**、Android SDK **35** 和 Build Tools **35.0.0**，使用仓库自带的 Gradle **8.14.3** Wrapper。工程不自行编译 SDK 原生引擎，无需为本工程另配 NDK/CMake；环境安装、Android Studio 配置和签名说明见[构建指南](docs/build.md)。

Windows PowerShell，在配置好 `JAVA_HOME` 后运行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :huoyanClient:testDebugUnitTest
```

macOS / Linux：

```bash
bash gradlew :app:assembleDebug
bash gradlew :app:testDebugUnitTest :huoyanClient:testDebugUnitTest
```

App 使用已打包的 `app/src/main/libs/huoyan-morningcheck-client-2.0.0.jar`，构建不会自动更新它。修改 JAR 源码后，先运行 `:huoyanClient:updateAppSdkJar`，再构建 App；完整交付包由 `:huoyanClient:clientDistribution` 生成。

Debug 使用本机调试签名；默认 Release 使用公开的[实验签名](signing/README.md)，仅供非商业实验与测试。正式发布使用自己的签名，通过未入库的 `local.properties` 配置；该实验签名限制不等同于 App 自有源码的使用规则。

## 文档与协作

| 内容 | 文档 |
| --- | --- |
| 运行与使用 | [快速开始](QUICKSTART.md) · [硬件](docs/hardware.md) · [常见问题](docs/faq.md) |
| 开发与交付 | [构建](docs/build.md) · [测试](docs/testing.md) · [版本兼容](docs/version-compatibility.md) · [发布](docs/releasing.md) · [更新记录](CHANGELOG.md) |
| JAR 交付 | [客户端说明](huoyanClient/README.md) · [账号配置与出包](huoyanClient/PACKAGING.md) |
| Word 对接文档 | [中文接入指南](app/src/main/libs/huoyan-morningcheck-client-2.0.0-integration-guide.zh-CN.docx) · [English Integration Guide](app/src/main/libs/huoyan-morningcheck-client-2.0.0-integration-guide.en.docx)，与 JAR 一同放在 `app/src/main/libs` |
| 支持与贡献 | [联系与支持](SUPPORT.md) · [贡献指南](CONTRIBUTING.md) · [安全问题](SECURITY.md) · [交流约定](CODE_OF_CONDUCT.md) |

普通问题和 PR 可在 [GitHub](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android) 提交。报告问题时附所用平台和提交号；激活码、账号、员工人脸和安全漏洞通过[私密反馈渠道](SECURITY.md)处理。

GitHub 构建检查与版本发布均由维护者手动触发，推送分支、PR 或版本 tag 不自动构建和发布，操作方式见[发布说明](docs/releasing.md)。编码代理约定见 [AGENTS.md](AGENTS.md)。
