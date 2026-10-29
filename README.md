# 火眼开源晨检 Android App

简体中文 | [English](README.en.md)

[GitHub 仓库](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android) · [问题反馈](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android/issues)

面向食堂、餐饮和食品生产等场所的原生 Android 晨检客户端：管理员维护员工与部门、录入人脸，员工在设备前依次完成身份识别、头部、手背和手心检查，结果与采集照片保存在本机。

App 采用 Java、XML layout 和独立 Activity，连接单独安装的火眼晨检 2.0 SDK。App 和客户端 JAR 源码对所有人开放使用；识别、检测和学习需要配套 SDK、模型及激活，获取方式见[使用与激活说明](USAGE.md)。

| 我想做什么 | 从这里开始 |
| --- | --- |
| 安装、激活并完成一次晨检 | [快速开始](QUICKSTART.md) |
| 在 Android Studio 或命令行构建 | [构建指南](docs/build.md) |
| 在自己的 App 中接入晨检 SDK | [客户端 JAR 说明](huoyanClient/README.md) · [Java 示例](huoyanClient/samples) |
| 离线阅读 Word 接入指南 | [中文](app/src/main/libs/huoyan-morningcheck-client-2.0.0-integration-guide.zh-CN.docx) · [English](app/src/main/libs/huoyan-morningcheck-client-2.0.0-integration-guide.en.docx) |
| 查看硬件要求、排查连接问题 | [硬件](docs/hardware.md) · [常见问题](docs/faq.md) · [版本兼容](docs/version-compatibility.md) |
| 参与开发或反馈问题 | [贡献](CONTRIBUTING.md) · [支持](SUPPORT.md) · [安全反馈](SECURITY.md) |

## 功能与使用边界

| 功能 | 当前实现 |
| --- | --- |
| 员工与部门 | 员工姓名、必填且不可重复的工号、健康证有效期、启用状态；多级部门、搜索、筛选及分页 |
| 人脸录入 | 同页选择员工、拍照学习、查看 SDK 返回的样本照片、删除单张或清空该员工样本 |
| 自动晨检 | 身份识别并采集体温 → 按规则检查口罩和工作帽 → 手背 → 手心 → 保存结果 |
| 连续晨检 | 结果页显示下一位倒计时，可停留查看；同一员工离开取景后才能开始下一轮 |
| 记录查询 | 日期区间、结果、部门、姓名与工号筛选，分页查询，详情及采集照片查看 |
| 设备配置 | Camera2 / USB UVC、测温串口、室内/室外/动态触发模式、串口/GPIO 补光 |
| 系统设置 | 语音、应用字体大小、安全密码、开机自动进入晨检、场所名称 |
| SDK 接入 | 本机 AIDL / 本机或局域网 WebSocket、SDK 安装引导、激活与授权信息 |

头部检查和测温是否启用由晨检规则决定。晨检规则在每轮开始时固定；关闭测温会记录“未测”，不会伪造温度。真实的识别精度、USB 相机和串口能力需要在目标机器上验证。

记录和晨检照片目前仅保存在本机，不提供记录上传、补充照片、人工复核或 App 在线更新。业务云员工导入与 SDK 激活是不同的接口和授权，不会因安装 App 自动获得云端账号。

## 界面示例

![部门管理横屏测试界面](docs/images/departments-landscape.png)

部门管理横屏示例，采集于 2026-10-15，图中为测试部门和人数。此图展示界面布局；后续功能与实际页面以当前源码为准。

## 开始使用

1. 按[构建指南](docs/build.md)生成并安装 App；在提供识别服务的设备安装配套晨检 SDK。
2. 通过[火眼官网](https://www.huoyan.cc)确认设备、应用包名及模型支持，取得激活资料，在 App 激活设备。
3. 配置相机及所需测温、补光设备，新增一名测试员工并录入人脸。
4. 从首页开始晨检，依次完成检查并核对保存的结果与照片。完整步骤见[快速开始](QUICKSTART.md)。

项目仓库为 [huoyan-ai-opensource/huoyan-opensource-morningcheck-android](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android)。目前尚未发布 Release 或完成线上 CI；当前可从源码构建，下载以维护者实际发布的 [GitHub Releases](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android/releases) 为准。App、JAR 与 SDK 分别管理版本，见[版本兼容表](docs/version-compatibility.md)和[更新记录](CHANGELOG.md)。

## 构建

使用 JDK 17、Android SDK 35 和仓库自带的 Gradle Wrapper：

```bash
bash gradlew :app:assembleDebug
bash gradlew :app:testDebugUnitTest :huoyanClient:testDebugUnitTest :huoyanClient:compileExample :huoyanClient:clientDistribution
```

Windows PowerShell 将 `bash gradlew` 替换为 `.\gradlew.bat`。完整工具链、SDK 路径、Release 签名和输出位置见[构建指南](docs/build.md)。

App 使用仓库内的 `app/src/main/libs/huoyan-morningcheck-client-2.0.0.jar`；修改客户端源码或 JAR 版本后，先执行 `:huoyanClient:updateAppSdkJar`，再构建 App。JAR 交付 ZIP 同时提供中英文 Word 指南，ZIP 与 Release Assets 保留项目内相同文件名，规则见[版本与发布](docs/releasing.md)。构建与发布工作流仅手动触发。

## 文档与授权

- [文档导航](docs/README.md)收录构建、测试、部署、兼容和发布说明。
- [代码结构](docs/code-structure.md)说明 Activity、自动流水线、本地数据和 SDK 连接的职责。
- [使用与激活说明](USAGE.md)区分 App/JAR 源码、SDK 模型及第三方组件；本仓库未整体指定 MIT 或 Apache 2.0 等标准许可证。
- 第三方声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，正式安装包使用自己的签名，实验签名规则见[签名说明](signing/README.md)。

公开反馈前请移除员工人脸、健康证、激活码、账号令牌和完整设备标识。私密资料和安全问题按 [SECURITY.md](SECURITY.md) 联系。
