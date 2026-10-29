# 更新记录

本文件记录源码的用户可见变化，不代表已经发布安装包。App、客户端 JAR、SDK 壳和模型分别版本，配套要求见[兼容表](docs/version-compatibility.md)。

## 未发布

- 补充公开仓库的中英文入口、首次运行、构建、测试、硬件及问题反馈说明。
- 补充 GitHub Issue/PR 模板与仅手动触发的检查、发布配置，仓库为 [huoyan-ai-opensource/huoyan-opensource-morningcheck-android](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android)；尚未运行线上工作流或发布 Release。
- 统一客户端中英文 Word 指南命名，交付 ZIP 与 Release Assets 要求同名同内容，并提供附件摘要。

## 当前源码基线 0.1.0

- 原生 Activity/XML 晨检客户端，包名 `cc.huoyan.opensource.morningcheck`，`versionCode=1`。
- 员工、部门、人脸录入、自动与连续晨检、本地记录和采集照片。
- Camera2 / USB UVC、串口测温及算法模式切换、串口/GPIO 补光、语音与密码退出。
- 独立客户端 JAR `2.0.0`，支持本机 AIDL 和本机/局域网 WebSocket；App 显式引用已打包 JAR。
- 记录与照片目前仅保存本机，不提供记录上传、补充照片或在线 App 更新。

首次公开 Release 应另外记录实际构建提交、SDK/模型版本、真实硬件验收与升级方式，不将以上源码说明当作完整真机验收结论。
