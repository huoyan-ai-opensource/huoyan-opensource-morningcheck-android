# 测试与验收

[文档导航](README.md) · [构建](build.md) · [贡献](../CONTRIBUTING.md) · [发布](releasing.md)

## 不需要设备的检查

使用 JDK 17 和已配置的 Android SDK，在仓库根目录运行：

```bash
bash gradlew :app:testDebugUnitTest :huoyanClient:testDebugUnitTest :huoyanClient:compileExample :huoyanClient:clientDistribution :app:assembleDebug :app:assembleDebugAndroidTest
python3 -m unittest discover -s huoyanClient/tools -p 'test_*.py'
```

Windows 把 `bash gradlew` 换成 `.\gradlew.bat`，`python3` 换为本机 Python 3。以上命令需要构建工具和依赖，不要求真实激活码、业务账号或外设。`assembleDebugAndroidTest` 只编译设备测试，不执行它们。

| 位置 | 验证内容 |
| --- | --- |
| `app/src/test/java` | 晨检流水线、身份/异常判定、日期、语音、会话及硬件协议逻辑 |
| `huoyanClient/src/test/java` | AIDL/WS 生命周期、初始化、请求映射、超时及本机回环通信 |
| `huoyanClient/samples` | 只依赖发行 JAR 与 Android API 的第三方 Java 接入示例 |
| `huoyanClient/tools/test_set_account.py` | JAR 账号配置工具的标准库测试 |
| `app/src/androidTest/java` | 页面、数据库、图片、相机及真实 SDK 的设备测试 |

App 测试使用已提交在 `app/src/main/libs/` 的 JAR；客户端模块测试源码。改动客户端后要先显式更新 JAR，两类测试不能互相代替。

Gradle 单测报告分别在 `app/build/reports/tests/testDebugUnitTest/` 与 `huoyanClient/build/reports/tests/testDebugUnitTest/`，JUnit XML 位于各模块的 `build/test-results/testDebugUnitTest/`。同时查看失败、错误和跳过数量，不以命令退出码替代全部验收记录。

## Android Lint 补充检查

```bash
bash gradlew :app:lintDebug
```

截至 2026-10-29，本地检查报告为 3 个错误、1483 个警告，命令会失败。三个错误分别是 `ResultActivity.java` 的图片列表点击回调保留绑定时的 `position`，以及 `layout/`、`layout-land/` 中 `view_home_design_header.xml` 的动态子项容器未显式声明 `orientation`。这些问题仍待处理，不能把单测和打包成功表述为 Lint 通过。

完整报告在 `app/build/reports/lint-results-debug.html`。当前手动 GitHub 工作流执行上面的单测、示例和打包检查，不包含 Lint，也未通过基线或关闭规则屏蔽上述问题。

## 指定设备检查

先用 `adb devices` 确认序列号及授权，多设备时明确指定目标。阅读测试类后，只运行与修改相关的测试，例如：

```bash
bash gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cc.huoyan.opensource.morningcheck.RecordDateFilterUiTest
```

设备测试会安装测试 APK 并操作页面，可能改变本地数据。`MorningCheckFullFlowTest`、真实授权/模型测试、人脸与数据库测试不能未经检查就对生产名册运行。模拟器上的隔离 WS 或 MockWebServer 只验证 App 编排，不证明真实模型、串口或灯光通过。

真实相机、温度和灯光测试使用授权的隔离设备。先核对相机/串口是否被其他任务占用；复用可用模拟器，不为每次测试启动新实例，同尺寸最多一台。

## 完整业务验收

按[快速开始](../QUICKSTART.md)使用测试员工，记录实际 App/JAR/SDK/模型、Android、ABI、设备与外设型号，逐项确认：

1. SDK 未安装引导、系统安装取消/成功、首次激活、模型准备和连接重试。
2. 员工工号唯一校验、部门筛选、健康证日期、人脸录入、删除单张与清空样本。
3. 人脸未入镜、未匹配员工、匹配后的头部/双手流程，温度过期、未测及异常处理。
4. 异常图片标注、结果保存、倒计时下一位、停留查看、同人离场和密码退出。
5. 记录日期范围、结果/部门/姓名筛选、分页、详情与照片，与首页统计口径分别核对。
6. 横屏/竖屏、130% 应用字体、系统输入法、权限拒绝、切后台、重连及进程重启。
7. 测温三算法模式的真实 `success` 应答、主动/被动读取、补光实际亮灭与离页释放。

离线验证须先具备有效授权和已准备模型；激活及业务云请求另需网络。证书过期后的管理与记录访问也应验证。没有执行的硬件或服务明确标记未验证，不能把 JVM/编译通过写成现场全流程通过。
