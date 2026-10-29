# 参与开发

欢迎提交可复现的软件问题、硬件接入经验和改进建议。先查阅[文档导航](docs/README.md)与现有讨论，按 [USAGE.md](USAGE.md)确认源码、SDK 及第三方组件的使用范围。

## 准备环境

按[构建指南](docs/build.md)安装 JDK 17 和 Android SDK 35，使用仓库自带 Wrapper。App 使用已提交的客户端 JAR；改动 `huoyanClient` 后先运行 `:huoyanClient:updateAppSdkJar`，再验证 App。

## 提交修改

1. 从 [GitHub 仓库](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android) 的 `main` 建立自己的工作分支；没有直接写入权限时 Fork 后提交 PR。
2. 说明具体触发场景、原行为和期望结果。将一个问题相关的代码、资源、文档与验证一起提交，避免夹带全项目格式化。
3. 遵循相邻 Java/XML 的缩进和命名、中文注释及 [AGENTS.md](AGENTS.md) 的项目约定。UI 使用现有 layout、尺寸资源和弹窗组件。
4. 运行[测试指南](docs/testing.md)中与修改相关的任务；UI 附横竖屏和字体适配证据，串口与相机问题写明具体设备。没有实际执行的检查不要标记通过。
5. 更新受影响的接入文档和 [CHANGELOG.md](CHANGELOG.md)，提交 PR，说明验证与兼容影响。

App、JAR、SDK 壳与模型独立版本。涉及接口、包名、签名或本机数据时说明升级要求；不要直接套用识物项目的 AIDL、端口、路径或商品协议。

## 数据与外部服务

常规编译和 JVM 测试不需要真实员工、激活码或云端账号。不要在生产设备上运行全部设备测试；部分测试会修改数据库、录入/清除人脸、调用激活接口或操作真实硬件。先阅读目标类并使用授权的隔离测试环境。

不提交 `local.properties`、正式签名、访问令牌、激活码、完整设备标识或真实人脸/健康证照片。公开实验签名按[签名说明](signing/README.md)单独管理，不能当生产身份。普通反馈使用脱敏日志，私密问题按 [SECURITY.md](SECURITY.md) 提交。

## 构建与发布

GitHub 工作流仅允许维护者手动运行，推送代码、PR 或 tag 不自动发布。发布前遵守[发布规则](docs/releasing.md)，两份 Word 指南在项目、交付 ZIP 和 Assets 中必须同名同内容。不要在 PR 中替换用户已经下载的公开附件。

社区交流遵循 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。普通软件问题通过 [GitHub Issues](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android/issues) 交流；激活与私密问题使用 [SUPPORT.md](SUPPORT.md) 和 [SECURITY.md](SECURITY.md) 规定的渠道。
