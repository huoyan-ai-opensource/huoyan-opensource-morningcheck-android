# 构建 App 与客户端 JAR

[文档导航](README.md) · [首次运行](../QUICKSTART.md) · [测试](testing.md) · [发布](releasing.md)

## 获取源码

项目仓库为 [huoyan-ai-opensource/huoyan-opensource-morningcheck-android](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android)：

```bash
git clone https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android.git
cd huoyan-opensource-morningcheck-android
```

## 工具链

在项目根目录运行仓库自带 Wrapper，不需要另装 Gradle。构建 Java 与源码级别不同：用 JDK 17 运行 Gradle，项目 Java 源码目标为 11。

| 组件 | 当前配置 |
| --- | --- |
| 构建 JDK / Android Studio Gradle JDK | 17 |
| Java source / target | 11 |
| Gradle Wrapper / AGP | 8.14.3 / 8.7.3 |
| Android 平台 | API 35 |
| Build Tools | 35.0.0，与项目及手动工作流配置一致 |
| App | minSdk 21，targetSdk 35 |
| 本机 SDK 通道 | AIDL 要求 API 27+；API 21–26 使用 WS |

当前工程不自行编译 SDK 原生引擎，Gradle 未声明 NDK 或 CMake 构建。UVC、串口依赖自带的原生库仍受 ABI 与设备系统约束，不能据此推断所有 ABI 均可运行。

Android Studio 中打开本项目根目录，Gradle JDK 选 17。通过 SDK Manager 安装 Android 15 / API 35、Platform Tools 和所需 Build Tools；命令行可使用：

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
sdkmanager --licenses
```

首次同步需要可访问依赖仓库的网络。项目使用阿里云 Google/Central 镜像及 Maven Central；缓存齐全后可加 `--offline`，离线模式不会补齐缺失依赖。

## SDK 路径

取得项目源码后，在 Android Studio 中打开 `huoyan-opensource-morningcheck-android` 目录。Studio 通常自动生成 `local.properties`；命令行可参考 [local.properties.example](../local.properties.example)，至少配置：

```properties
sdk.dir=D:/AndroidSDK
```

换成本机 Android SDK 根目录。Windows 路径可用正斜杠；`local.properties` 不提交到版本库，也不随发行 ZIP 提供。

## Windows

PowerShell 示例，JDK 路径按本机安装位置调整：

```powershell
$env:JAVA_HOME = 'D:\JDK\jbr-17.0.14'
$env:GRADLE_USER_HOME = 'D:\Gradle'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug
```

CMD 对应写法：

```bat
set "JAVA_HOME=D:\JDK\jbr-17.0.14"
set "GRADLE_USER_HOME=D:\Gradle"
set "PATH=%JAVA_HOME%\bin;%PATH%"
gradlew.bat :app:assembleDebug
```

## macOS 与 Linux

```bash
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
java -version
bash gradlew --version
bash gradlew :app:assembleDebug
```

Wrapper 没有执行位时，使用 `bash gradlew`。macOS 可用 `/usr/libexec/java_home -v 17` 查找 JDK 17 路径。

## 常用任务

Windows 将下表 `bash gradlew` 替换为 `.\gradlew.bat`。

| 目标 | 命令 | 输出目录 |
| --- | --- | --- |
| Debug App | `bash gradlew :app:assembleDebug` | `app/build/outputs/apk/debug/` |
| Release App | `bash gradlew :app:assembleRelease` | `app/build/outputs/apk/release/` |
| 独立 JAR | `bash gradlew :huoyanClient:packageReleaseJar` | `huoyanClient/build/outputs/jar/` |
| 完整 JAR ZIP | `bash gradlew :huoyanClient:clientDistribution` | `huoyanClient/build/distributions/` |
| 更新 App 依赖 JAR | `bash gradlew :huoyanClient:updateAppSdkJar` | 替换 `app/src/main/libs/` 中对应 JAR |

当前主 JAR 名为 `huoyan-morningcheck-client-2.0.0.jar`，ZIP 为 `huoyan-morningcheck-client-2.0.0.zip`。版本统一配置在根 `build.gradle` 的 `ext.morningcheckClientVersion`；App 版本独立维护。

**App 使用已提交 JAR，不直接依赖 `huoyanClient` 源码编译。** 修改 JAR 源码或版本后，先运行 `updateAppSdkJar`，再构建并测试 App。只运行 `assembleDebug` 不会把源码变化写入 App 所用 JAR。

## 签名与安装

Debug 使用本机调试签名。Release 的实验签名与正式签名配置见[签名说明](../signing/README.md)。正式发布使用自己的私钥，并在未入库的 `local.properties` 完整填写签名参数，不能使用公开实验签名充当生产身份。

安装前核对包名、版本与签名。不同签名通常不能覆盖安装；不要为解决签名问题直接卸载业务 App，卸载会删除员工档案、记录和本机照片。构建 APK 不会激活 SDK，后续按[快速开始](../QUICKSTART.md)部署。

## 构建排障

| 问题 | 先检查 |
| --- | --- |
| Java / class version 不兼容 | `JAVA_HOME`、`java -version` 与 Studio Gradle JDK 均为 17 |
| SDK location not found | `sdk.dir` 是否指向正确 SDK 根目录 |
| 找不到平台或 Build Tools | SDK Manager 安装上表版本，不随意降编译平台 |
| 下载超时、仓库不可达 | 出错仓库、代理和网络；缺缓存时先取消离线模式 |
| App 行为仍像旧 JAR | 执行 `:huoyanClient:updateAppSdkJar` 后重新构建 |
| Windows JVM 无法建立回环连接 | 检查本机临时目录及回环访问限制；不需要重新下载整套工具链 |

反馈时附任务名、JDK/Gradle 版本及第一个失败原因，并移除私密路径、激活码与账号资料。
