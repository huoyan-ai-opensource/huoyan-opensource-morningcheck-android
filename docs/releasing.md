# 版本与发布

[文档导航](README.md) · [构建](build.md) · [测试](testing.md) · [版本兼容](version-compatibility.md) · [更新记录](../CHANGELOG.md)

项目仓库为 [huoyan-ai-opensource/huoyan-opensource-morningcheck-android](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android)，已准备手动检查与发布配置；目前尚未运行线上工作流或发布 Release。先确认待发布提交，再由维护者手动执行。推送代码、PR 或 tag 均不会自动构建、发布或激活设备。下载及发布入口统一使用本仓库的 [GitHub Releases](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android/releases)，不提供 CNB Release 下载入口。

## 版本配置

| 产物 | 当前版本 | 维护位置 |
| --- | --- | --- |
| App versionName | 0.1.0 | `app/build.gradle` 的 `appVersionName`；可用 `-PappVersionName` 覆盖 |
| App versionCode | 1 | `app/build.gradle` 的 `defaultConfig.versionCode`；发布新安装包需递增 |
| 晨检客户端 JAR / ZIP | 2.0.0 | 根 `build.gradle` 的 `morningcheckClientVersion` |
| 晨检 SDK 壳 | 按实际交付版本 | 与客户端、设备和模型一同验证，见兼容表 |

App 与 JAR 版本独立管理。App 构建只使用已经提交的 `app/src/main/libs/huoyan-morningcheck-client-<JAR版本>.jar`。修改客户端源码后，维护者显式执行 `:huoyanClient:updateAppSdkJar`，确认本次计划交付的账号配置和 JAR 适合公开发布，再运行 App 验证；普通 App 构建不会自动替换该文件。

APK 名称为 `huoyan-morningcheck-<App版本>-<versionCode>-<时间戳>-<构建类型>.apk`。发布说明记录实际构建提交，不仅记录 tag 名称。

## 中英文指南交付规则

两份 Word 源文件位于 `app/src/main/libs/`，按 JAR 版本命名：

| 语言 | 项目源文件与 Release Assets 文件名 | ZIP 内路径 |
| --- | --- | --- |
| 中文 | `huoyan-morningcheck-client-2.0.0-integration-guide.zh-CN.docx` | `docs/huoyan-morningcheck-client-2.0.0-integration-guide.zh-CN.docx` |
| 英文 | `huoyan-morningcheck-client-2.0.0-integration-guide.en.docx` | `docs/huoyan-morningcheck-client-2.0.0-integration-guide.en.docx` |

后续版本只替换文件名中的 JAR 版本，保留 `.zh-CN.docx` 与 `.en.docx` 后缀。两份文档必须同时放进 `huoyan-morningcheck-client-<JAR版本>.zip` 的 `docs/`，并在同一 Release 的 Assets 中单独提供；项目、ZIP 和 Assets 三处文件名、版本、内容一致。

Assets 直接显示完整文件名，不设置自定义 `label`，使用 `gh release` 上传时不追加 `#label`。不能用“英文接入指南 English”等展示名替代，也不能仅在 ZIP 外补一份英文。`clientDistribution` 拦截缺失或空白指南；发布流程从 ZIP 提取 JAR 和两份 Word，分别与本次构建 JAR 和项目源文件比对，失败就停止发布。

## 本地验证与打包

使用 JDK 17、Android SDK / Build Tools 35 和项目自带 Gradle 8.14.3 wrapper。在仓库根目录执行：

```bash
bash gradlew --no-daemon --console=plain -PappVersionName=0.1.0 \
  :app:testDebugUnitTest :huoyanClient:testDebugUnitTest \
  :huoyanClient:compileExample :huoyanClient:clientDistribution \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s huoyanClient/tools -p 'test_*.py'
```

Windows PowerShell 使用 `.\gradlew.bat`，并先设置 `$env:PYTHONDONTWRITEBYTECODE = '1'` 再运行 Python；将 `appVersionName` 替换为目标版本。Windows 工具链路径与离线缓存见[构建说明](build.md)。这些命令不安装 APK、不启动模拟器，也不向业务设备提交晨检请求；设备测试 APK 仅编译，不执行。

修改工作流前先跑整组测试与构建，修改后核对实际 APK、JAR、ZIP、两份 Word 及摘要。Windows 本地通过不代表 GitHub Ubuntu 工作流通过；线上成功必须有对应的实际运行记录。真实相机、人脸识别、测温、灯光和激活仍需在授权测试设备按[测试说明](testing.md)验收。

## 签名与配置

默认 Release 使用随仓库公开的 `signing/xinhai-debug-demo.jks`，仅用于非商业开源实验和测试。Debug 使用本机调试签名。不同签名通常不能覆盖安装，切换前先备份业务数据，不能直接清除业务设备的数据来验证安装。

正式发布使用独立私钥，在未入库的 `local.properties` 完整填写四项签名参数；缺少任一项都会拒绝构建。配置示例见 [local.properties.example](../local.properties.example)，公开实验签名说明见 [signing/README.md](../signing/README.md)。生产私钥、密码、激活码和私密账号不得随附件公开。

## GitHub 工作流

- 先核对下节的仓库配置，再在 [Actions](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android/actions) 手动执行工作流；配置文件存在不表示工作流已经运行。
- `android.yml`：在 Actions 选择“Android 检查”并手动运行，执行 App/JAR 单元测试、独立示例编译、JAR ZIP 打包、Debug 和 AndroidTest APK 编译及 Python 账号工具测试，只取得仓库读取权限，保存报告和调试产物。
- `release.yml`：在 Actions 选择“Android 实验版本发布”，填写已经存在的 `vMAJOR.MINOR.PATCH` 或 `MAJOR.MINOR.PATCH` tag；构建任务检出该 tag，执行测试及 Release 打包，记录真实源码提交。只有后续发布任务具有 `contents: write`，使用 GitHub 内置令牌创建对应 Release。
- 两份工作流均仅 `workflow_dispatch` 手动触发。第三方 Actions 固定到提交版本；升级时核对上游提交及变化，不直接改成浮动版本。

确认版本、签名、目标提交和远端后，可由维护者创建并推送目标 tag，例如：

```bash
git tag v0.1.0
git push origin v0.1.0
```

执行前用 `git remote -v` 确认 `origin` 指向 `https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android.git`。仅推 tag 不会发布，仍需手动运行发布工作流。不要重复创建已经公开的版本；Release 已存在时流程会失败，必须先检查现状，不能静默覆盖用户已经下载的文件。

## GitHub 仓库网页配置核对

本节是维护者操作指引，不表示这些网页设置已经在远端生效。目标是上述晨检仓库，不能直接使用识物仓库地址。

1. 首次推送后确认默认分支为 `main`，源码和文档来自本项目的公开基线。不要另行生成 README、许可证或 `.gitignore` 覆盖已有内容；自有源码使用与 SDK 激活规则以 `USAGE.md` 为准。
2. 在仓库首页的 About 填写“火眼开源晨检 Android 客户端，支持员工管理、人脸录入、连续晨检和记录查询”，网站可填写实际官网 `https://www.huoyan.cc`。Topics 可使用 `android`、`java`、`morning-check`、`health-inspection`、`aidl`、`websocket`、`computer-vision`；只有实际发布后才添加下载或演示入口。
3. 在 Settings 的 General 中启用 Issues，确认问题模板、功能建议模板和 PR 模板能正常显示。授权、激活码和漏洞按 `SUPPORT.md`、`SECURITY.md` 的私密渠道处理，不要求用户在公开 Issue 提交敏感数据；支持时可启用私密漏洞报告。
4. 在 Actions 设置中允许本项目使用的固定版本 Actions，默认工作流权限保持只读。发布工作流仅在 `publish` job 声明 `contents: write`；组织策略需允许该发布操作，不需要把全部工作流改成写权限。首次手动运行检查流程并查看测试报告，再运行发布流程。
5. 为 `main` 配置合适的分支规则，禁止强制推送和删除，团队协作时启用 PR 审阅。当前工作流不会在 PR 自动运行，不要把不存在的自动 PR 检查设为必过条件，否则会阻塞合并；需要自动检查时先明确修改触发策略，再调整分支规则。
6. 在实际 Release 中提供本页要求的附件和摘要，核对仓库中的源码链接及下载地址，再更新 README、问题模板和支持说明中的外部链接。推送和发布是独立操作，执行前分别确认目标，不能仅因文档已存在就假定已经发布。

## 附件与摘要

一次完整发布包含：

1. 本版本已签名 App Release APK。
2. `huoyan-morningcheck-client-<JAR版本>.jar`。
3. `huoyan-morningcheck-client-<JAR版本>.zip`，包含主 JAR、运行时依赖、示例、账号配置工具、使用说明、第三方声明和两份 Word。
4. `huoyan-morningcheck-client-<JAR版本>-integration-guide.zh-CN.docx`。
5. `huoyan-morningcheck-client-<JAR版本>-integration-guide.en.docx`。
6. `SHA256SUMS`。

`SHA256SUMS` 必须覆盖所有实际附件，清单自身除外。手动增加、替换或删除附件后同步更新摘要、下载链接和发布说明。手动在 Releases 页面上传与工作流发布遵守相同规则；本地打包不会自动创建远程 Release。

下载同一 Release 的清单和其中全部附件，Linux 执行 `sha256sum -c SHA256SUMS`，macOS 执行 `shasum -a 256 -c SHA256SUMS`；Windows 使用 `Get-FileHash -Algorithm SHA256 <文件名>` 逐份比较。摘要用于检查完整性，不能替代 APK 签名及下载来源验证。

SDK 壳 APK、原生识别库、模型和激活码不包含在客户端 ZIP 中，获取和授权规则见 [USAGE.md](../USAGE.md)。
