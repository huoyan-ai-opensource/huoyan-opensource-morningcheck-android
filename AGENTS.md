## 弹窗与操作反馈

- 选择操作统一使用项目自定义列表弹窗；确认与风险提示统一使用项目自定义 AlertDialog 样式，不使用系统默认确认框样式。
- 任何错误、异常或成功信息不得通过页面、卡片或表单内的 TextView / tips 回显；统一通过 ToastEx 对应的 error / success 提示，类型依据真实结果，不按文案猜测。
- 请求中、读取中、处理进度等只放在 Loading 弹窗中；完成、失败或页面销毁时关闭，避免遗留遮罩和重复提交。
- 不向用户展示 SDK 是否就绪、密钥状态等内部诊断信息。只有需求明确指定的业务状态可以在对应区域显示，不能据此追加内部状态或操作结果。
- 应用 UI、列表、表单、导航和弹窗均需容纳最大 130% 应用字体；正文允许换行或内容区域滚动，关键操作始终可达。

## 代码风格

- 以项目当前 reformat 后的代码为准，修改前先参考相邻文件，保持缩进、换行、空行和注释格式一致。
- Java 使用 4 个空格缩进，类和方法的左花括号与声明同行，注解独立成行，方法体展开；保留现有简短守卫语句的写法。
- XML 使用 4 个空格缩进，每个属性独立一行，同级控件之间保留空行，style 的 item 分行书写，不将布局或方法重新压缩成单行。
- 只整理本次修改涉及的代码，不顺带重排无关文件，不覆盖用户已完成的格式化。

## 构建与发布（强制）

- 本工程为 `huoyan-opensource-morningcheck-android`，App 包名 `cc.huoyan.opensource.morningcheck`；不得替换晨检 SDK 服务包名、AIDL 契约或客户端 JAR 公共 API。
- 使用 JDK 17、Gradle 8.14.3、Android SDK / Build Tools 35；Java 源码与目标兼容级别为 11。Windows 工具链遵循工作区约定：JDK 从 `D:/JDK` 选用，`GRADLE_USER_HOME=D:/Gradle`，优先复用缓存。
- App 使用 `app/src/main/libs/` 中已提交的客户端 JAR。修改 `huoyanClient` 源码后需要显式执行 `:huoyanClient:updateAppSdkJar`；普通 App 构建不会自动重打 JAR。
- 客户端版本由根 `build.gradle` 的 `morningcheckClientVersion` 维护；App 版本独立维护。升级客户端时同步 JAR、指南源文件名、文档及兼容表，不使用 App 版本替代 JAR 版本。
- 两份 Word 源文件保存在 `app/src/main/libs/`，名称固定为 `huoyan-morningcheck-client-<JAR版本>-integration-guide.zh-CN.docx`（中文）及 `huoyan-morningcheck-client-<JAR版本>-integration-guide.en.docx`（英文）。
- 交付 ZIP 的 `docs/` 与 GitHub Release Assets 必须同时提供两份 Word；文件名、版本及内容与源文件一致。缺失、空文件或不一致时停止发包，不能只在 ZIP 外补英文。
- Assets 直接显示项目完整文件名，不设置自定义 `label`，上传时不追加 `#label`。`SHA256SUMS` 覆盖全部实际附件（不包含清单自身），附件变化后同步更新摘要和发布说明。
- GitHub 检查及发布工作流仅允许 `workflow_dispatch` 手动触发；不得启用 push、PR 或 tag 自动构建和发布，不恢复 CNB Release 下载入口。
- 修改工作流前先在本地执行整组测试与构建，修改后核对实际产物。本地 Windows 检查不能表述为 GitHub Ubuntu 工作流已通过；未建远端或未发布附件时不得虚构仓库、Release、下载链接及验证记录。
- 正式签名私钥和密码不得入库。`signing/xinhai-debug-demo.jks` 是唯一例外，为已有公开实验签名，仅用于非商业实验及测试 Release 打包。正式发布通过未入库的 `local.properties` 完整配置四项签名参数。
- 完整发布步骤见 [docs/releasing.md](docs/releasing.md)，英文规则见 [Agents.en.md](Agents.en.md)。
