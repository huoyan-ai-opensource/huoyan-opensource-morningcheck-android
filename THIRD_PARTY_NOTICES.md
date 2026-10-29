# 第三方组件与版权说明

本文件适用于晨检 App、`huoyanClient` 客户端模块和随附 JAR。火眼自有源码的使用与 SDK 激活规则见 [USAGE.md](USAGE.md)；第三方组件继续按各自许可使用，不因源码仓库公开而变更许可。

清单依据 `app/build.gradle`、`huoyanClient/build.gradle`、2026-10-29 的 `releaseRuntimeClasspath` 解析结果，以及对应版本的 POM、AAR 和上游源码整理。依赖升级后应重新核对，不能直接使用识物项目的组件清单。

许可全文集中在[原文汇总](docs/licenses/THIRD_PARTY_LICENSES.txt)与[中文参考译文](docs/licenses/THIRD_PARTY_LICENSES.zh-CN.md)，不为每个组件重复建立独立文件。中文译文用于阅读，效力按对应上游原文判断。

## App 与客户端运行时组件

| 组件 | 当前版本或实际解析结果 | 许可与来源 |
| --- | --- | --- |
| AndroidX AppCompat | 1.7.0 | Apache 2.0；[AndroidX](https://developer.android.com/jetpack/androidx/releases/appcompat) |
| AndroidX RecyclerView | 1.2.1 | Apache 2.0；[AndroidX](https://developer.android.com/jetpack/androidx/releases/recyclerview) |
| OkHttp | 4.12.0 | Apache 2.0；[OkHttp 4.12.0](https://github.com/square/okhttp/tree/parent-4.12.0) |
| Okio / Okio JVM | 3.6.0，由 OkHttp 引入 | Apache 2.0；[Okio 3.6.0](https://github.com/square/okio/tree/parent-3.6.0) |
| Kotlin 标准库系列 | App 解析为 1.9.10；独立客户端依赖按其 ZIP 与解析结果为准 | Apache 2.0；[Kotlin](https://github.com/JetBrains/kotlin) |
| Kotlin Coroutines | App 解析为 1.6.4，由 AndroidX 引入 | Apache 2.0；[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/tree/1.6.4) |
| Android SerialPort API | `com.licheedev:android-serialport:2.1.4` | Apache 2.0；[上游项目](https://github.com/licheedev/Android-SerialPort-API)，POM 声明及原有 Google 文件头继续有效 |
| UVCAndroid | `com.herohan:UVCAndroid:1.0.13` | 顶层 Apache 2.0；[对应版本源码](https://github.com/shiyinghan/UVCAndroid/tree/1.0.13)，native 组件分别见下表 |
| Hutool Core | 5.8.35，由 UVCAndroid 引入 | 木兰宽松许可证第 2 版；[对应版本许可](https://github.com/dromara/hutool/blob/5.8.35/LICENSE) |
| ZXing Core | 3.5.4 | Apache 2.0；[ZXing](https://github.com/zxing/zxing/tree/zxing-3.5.4) |
| Core Library Desugaring | `desugar_jdk_libs:2.1.5`，由 Android 构建工具处理 | GPL 第 2 版及 Classpath 例外；[desugar_jdk_libs](https://github.com/google/desugar_jdk_libs)，完整条款见许可汇总 |
| AndroidX 其他传递依赖 | 包括 Core 1.13.1、Activity 1.7.0、Fragment 1.5.4、Lifecycle 2.6.2、Annotation 1.8.0 等 | Apache 2.0；[AndroidX 源码](https://cs.android.com/androidx/platform/frameworks/support) |
| JetBrains Annotations / Guava ListenableFuture | 13.0 / 1.0，由 Kotlin 和 AndroidX 引入 | Apache 2.0；各自 POM 与上游声明 |

客户端普通 JAR 不内嵌 OkHttp、Okio 和 Kotlin。`clientDistribution` 将解析后的运行时 JAR 放入 ZIP 的 `libs/`，分发时仍需保留相关第三方声明。Android 系统自带的 `org.json` 不作为客户端运行时依赖另行打包。

复核当前解析结果：

```bash
bash gradlew :app:dependencies --configuration releaseRuntimeClasspath
bash gradlew :huoyanClient:dependencies --configuration releaseRuntimeClasspath
```

## 相机与串口 native 组件

`UVCAndroid-1.0.13.aar` 包含以下相机库；`android-serialport-2.1.4.aar` 提供串口库。本仓库通过 Maven 依赖使用它们，不把这些 native 库声明为火眼原创。

| 文件 / 组件 | 源码与许可 |
| --- | --- |
| `libUVCCamera.so` 的 Java / JNI 封装 | UVCAndroid / UVCCamera，Apache 2.0，保留 saki 等作者的源文件声明 |
| `libuvc.so` | [UVCAndroid 1.0.13 内 libuvc](https://github.com/shiyinghan/UVCAndroid/tree/1.0.13/libuvccamera/src/main/jni/libuvc)，BSD |
| `libusb1.0.so` | [对应 libusb 源码](https://github.com/shiyinghan/UVCAndroid/tree/1.0.13/libuvccamera/src/main/jni/libusb)，LGPL 2.1；分发时保留其获取源码与再分发条件 |
| `libjpeg-turbo212.so` | [对应 libjpeg-turbo 源码](https://github.com/shiyinghan/UVCAndroid/tree/1.0.13/libuvccamera/src/main/jni/libjpeg-turbo)，IJG、BSD 与 zlib 条款；完整许可和 IJG README 已收入汇总 |
| RapidJSON | [相机 JNI 使用的 RapidJSON](https://github.com/shiyinghan/UVCAndroid/tree/1.0.13/libuvccamera/src/main/jni/rapidjson)，MIT |
| `libyuv.so` | [对应 libyuv 源码](https://github.com/shiyinghan/UVCAndroid/tree/1.0.13/libuvccamera/src/main/jni/libyuv)，BSD 风格许可，保留其许可、专利授权及作者声明 |
| `libserial_port.so` | Android SerialPort API，Apache 2.0；源文件的 Google 等版权声明不变 |

This software is based in part on the work of the Independent JPEG Group.

以上是 App 的外设支持库。晨检识别引擎、模型和激活证书属于单独安装的晨检 SDK 壳，不包含在本仓库客户端 JAR 中，其授权不能从本清单推导。

UVCAndroid 1.0.13 的 libyuv 源码头注明 BSD 风格许可和 PATENTS 授权，版本头为 1840，但该目录未附完整许可文件。汇总补入 libyuv 上游镜像固定提交的 LICENSE、PATENTS 和 AUTHORS；该提交用于固定许可文本，实际相机源码仍以上表的 UVCAndroid 版本为准。Java 兼容库的 Classpath 例外按其原文适用范围保留，不能简化为整个 App 改用 GPL。

## 字体和项目自有文件

`app/src/main/res/font/retail_sans_regular.ttf` 和 `retail_sans_bold.ttf` 是本项目使用的 Noto Sans SC 字体资源，其字体内版权声明为 Adobe 2014–2021、保留字体名称 `Source`。完整版权声明和 SIL OFL 1.1 字体许可保留在[许可原文汇总](docs/licenses/THIRD_PARTY_LICENSES.txt)第 10 节，中文参考译文见[字体许可译文](docs/licenses/THIRD_PARTY_LICENSES.zh-CN.md#source-han-sans)。原文与译文随交付包保留；OFL 仅适用于字体，不是整个 App 或客户端 JAR 的源码许可证。

`app/src/main/libs/huoyan-morningcheck-client-2.0.0.jar` 来自本仓库 `huoyanClient` 模块。普通 App 构建消费这份已提交 JAR，不自动重编客户端源码；更新方式见[客户端交付说明](huoyanClient/README.md)。发行账号配置和激活资料不应作为第三方许可内容公开。

## 测试与构建组件

| 组件 | 版本 / 范围 | 许可 |
| --- | --- | --- |
| JUnit | 4.13.2，JVM 测试 | EPL 1.0 |
| Hamcrest Core | JUnit 的传递测试依赖，1.3 | BSD；保留上游版权与免责声明 |
| JSON-java | 20240303，仅 JVM 测试 | 该版本上游声明为 Public Domain；不套用旧版本 JSON 许可 |
| MockWebServer | 4.12.0，测试 | Apache 2.0 |
| AndroidX Test Runner / Core / Ext JUnit | 1.6.2 / 1.6.1 / 1.2.1，设备测试 | Apache 2.0 |
| Android Gradle Plugin / Gradle Wrapper | 8.7.3 / 8.14.3，构建工具 | Apache 2.0 |

测试或构建依赖不因被本文件列出而进入 Release APK 的业务依赖。组件清单以本项目实际配置及解析结果为准。
