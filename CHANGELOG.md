# 更新记录

本文件记录源码的用户可见变化；实际安装包和构建记录以 [GitHub Releases](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android/releases) 与 [Actions](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android/actions) 为准。App、客户端 JAR、SDK 壳和模型分别版本，配套要求见[兼容表](docs/version-compatibility.md)。

## 2.0.1（2026-10-29）

- App 升级为 `2.0.1`，`versionCode=3`；客户端 JAR 仍为 `2.0.0`，重新构建交付包，不改变公共接口。
- 新增英文界面，按 Android 系统语言偏好选择中文或英文，未配置支持语言时默认中文。
- 页面、弹窗、状态提示、晨检语音和引导动画补齐英文，保留原有数据库值、筛选条件和 SDK 协议。
- 英文 README 使用英文版主页和晨检页截图，公司、场所及设备标识采用虚构示例值。

## 2.0.0（2026-10-29）

- 首次公开发布基线：App `2.0.0`、`versionCode=2`；客户端 JAR 保持 `2.0.0`。两者独立维护，本次版本号一致不代表后续必须同步。
- 补充公开仓库的中英文入口、首次运行、构建、测试、硬件及问题反馈说明。
- 补充 GitHub Issue/PR 模板与仅手动触发的检查、发布配置，仓库为 [huoyan-ai-opensource/huoyan-opensource-morningcheck-android](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android)。
- 统一客户端中英文 Word 指南命名，交付 ZIP 与 Release Assets 要求同名同内容，并提供附件摘要。
- 原生 Activity/XML 晨检客户端，包名 `cc.huoyan.opensource.morningcheck`。
- 员工、部门、人脸录入、自动与连续晨检、本地记录和采集照片。
- Camera2 / USB UVC、串口测温及算法模式切换、串口/GPIO 补光、语音与密码退出。
- 独立客户端 JAR `2.0.0`，支持本机 AIDL 和本机/局域网 WebSocket；App 显式引用已打包 JAR。
- 记录与照片目前仅保存本机，不提供记录上传、补充照片或在线 App 更新。

先推送本版本源码，再创建 tag 和 Release；实际交付以 Release 中的附件及摘要为准。发布说明另行记录实际构建提交、SDK/模型版本、真实硬件验收与升级方式，不将以上源码说明当作完整真机验收结论。
