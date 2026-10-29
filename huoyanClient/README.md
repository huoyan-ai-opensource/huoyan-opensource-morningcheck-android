# huoyanClient · 晨检壳客户端模块

简体中文 | [English](README.en.md)

完整开发步骤见[中文接入指南](INTEGRATION_GUIDE.md)和[English Integration Guide](INTEGRATION_GUIDE.en.md)。离线 Word 为 `docs/huoyan-morningcheck-client-2.0.0-integration-guide.zh-CN.docx` 和 `docs/huoyan-morningcheck-client-2.0.0-integration-guide.en.docx`，随 `clientDistribution` ZIP 一并交付；项目源文件保存在 `app/src/main/libs/`。将来发布 GitHub Release 时，也必须按原文件名同时附加两份 Word，规则见项目根目录或 ZIP 内的 `docs/releasing.md`。

客户端自动读取宿主 APK 的 `versionName`，首次初始化、重连后的初始化和显式重试
均携带该版本：AIDL 调用 `init(String appVersion)`，WS 发送 `params.app_version`。
版本由 SDK 保存并随后续心跳上报，客户端不新增直接向后台上报版本的请求。
宿主无需手填版本号；包信息不可查时不伪造版本。客户端须配套使用支持该签名的新版 SDK 壳。


本模块参考零售项目 `huoyan-sdk-client` 的组织和交付方式，专用于 `huoyan-morningcheck-sdk-android-universal`。Gradle 使用 Android library 插件编译 AIDL；App 消费无界面、无 Android 资源、无原生库的 JAR，JAR 仅携带发行账号 Java 资源 `raw/config`。模块没有依赖晨检 App，也不能引用其 Activity、数据库、相机或 R 资源。

发行账号可在 JAR 打包后修改，操作方式见 [PACKAGING.md](PACKAGING.md)。客户宿主构建 APK 时必须保留 `raw/config`，不得添加同名资源。修改已安装 App 的账号，需要换用新 JAR 后重新构建和安装。

## 接入

本项目保留 `:huoyanClient` 用于独立打包，App 直接引用 `app/src/main/libs/` 中已打包的 JAR。版本从根 `build.gradle` 读取：

```groovy
def clientVersion = rootProject.ext.morningcheckClientVersion
implementation files("src/main/libs/huoyan-morningcheck-client-${clientVersion}.jar")
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
```

普通 App 构建只使用已同步的 JAR，不触发 `huoyanClient` 源码编译。修改客户端源码或发行版本后，在仓库根目录显式执行 `gradlew.bat :huoyanClient:updateAppSdkJar`，重新打包并替换 App 使用的 JAR，再构建 App。

其他 App 可将发行 JAR 放入宿主 `app/src/main/libs/`，按实际交付版本配置：

```groovy
implementation files('src/main/libs/huoyan-morningcheck-client-2.0.0.jar')
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
```

需要源码联调的宿主仍可改用保留的 `clientJarElements` 配置，由模块构建 JAR 并传递运行依赖；本项目 App 不使用这一方式：

```groovy
implementation project(path: ':huoyanClient', configuration: 'clientJarElements')
```

同一个 App 只引用一个版本，不同时使用模块依赖和本地客户端 JAR。

公共入口为 `cc.huoyan.morningcheck.sdk.client.HuoyanClient` 和 `SdkConfig`。

```java
HuoyanClient client = new HuoyanClient(context.getApplicationContext(), listener);
client.connect(SdkConfig.automatic(SdkConfig.DEFAULT_WS_ENDPOINT));
// 明确连接局域网设备：client.connect(SdkConfig.webSocket("ws://192.168.1.99:18087/"));
client.userCount((code, message, data) -> {
    // 默认主线程回调；只有 code == 0 才读取成功结果。
});
```

JAR 不合并 Manifest，第三方宿主需自行声明：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<queries><package android:name="cc.huoyan.morningcheck" /></queries>
<!-- 使用明文 ws:// 时，宿主 application 需允许对应目标的明文流量。 -->
```

主 JAR 不内嵌 OkHttp、Kotlin 和 Okio。使用上述 Maven 依赖时，Gradle 会传递引入 OkHttp 4.12.0 所需的 Kotlin/Okio；完整离线交付可使用 `clientDistribution` ZIP 的 `libs/`，其中已包含运行依赖，不要再重复引入同一组依赖。宿主 minSdk 21，需启用 Java 11 编译与 coreLibraryDesugaring（本 App 已配置）。

## 下载本机 SDK

`cc.huoyan.morningcheck.sdk.client.deployment.SdkShell` 提供晨检壳包名、已安装版本查询和默认安装包地址 `http://f.huoyan.cc/apk/morningcheck_sdk_lastest.apk`。首次部署可用 `SdkApkDownloader(url, directory)` 流式下载到宿主提供的现有缓存目录，调用 `start(listener)` 一次，并在不再需要下载时调用 `cancel()`。

下载回调在后台线程执行；总字节数为负数时表示未知。下载失败或取消会删除未交付文件；成功返回的临时文件由宿主负责校验、安装和清理。模块不持有 Activity，也不请求安装权限或自动安装。宿主使用默认 HTTP 地址时需允许该目标的明文流量。

## 通道与生命周期

- `SdkConfig.automatic(url)`：API 27+ 的回环地址选择 AIDL，旧系统或远端地址选择 WS；不做 DNS 查询来猜测设备身份。
- `SdkConfig.aidl()`：仅 API 27+，显式绑定 `cc.huoyan.morningcheck/cc.huoyan.morningcheck.app.comm.SdkCommService`，action 为 `cc.huoyan.morningcheck.SdkCommService`。
- `SdkConfig.webSocket(url)`：显式选择 WS，包括本机自定义端口。默认壳地址 `ws://127.0.0.1:18087/`。本机 WS 以绑定租约拉起壳，远端连接不启动本机壳。
- 同配置重复 connect 复用连接；切换配置结束旧请求，绝不移交或重放到另一通道。当前协议支持 AIDL api_version=4、WS api_version=2，各自保留真实能力值。
- 连接成功会显式执行 init，并查询状态、能力和类别表。`init=0`、`activate=0`、连接建立都不等于可识别；AIDL 与 WS 的 `isReady()` 均要求状态为 `READY` 或 `OFFLINE`、`model_ready=true`、`certificate_valid=true`，且 `key_lost` 不为 true。
- `OFFLINE` 是本地可用、身份尚未与云端校准的状态；本地识别、检测与登记可用，云端外发仍由壳拒绝。`isReady()` 不表示云端接口可用。SDK 自动校准恢复后会发布 `READY`，宿主只需继续读取状态，不额外重连或重放 `init`。证书过期、模型未就绪、密钥轮换及禁用状态仍不可识别。
- 查询和删除等方法不统一受 `isReady()` 拦截，由壳作最终准入，保留证书过期后可用的管理功能。
- `close()` 断开并停止重连，之后可显式 connect；`destroy()` 最终释放且屏蔽尚未交付的回调，不可复用。客户端可以由宿主应用级会话持有，页面仅订阅状态，不必每次跳转重新连接。

## 命令

公共具名方法覆盖壳的全部 24 个命令：`init`、`queryLicenseModels`、`queryDeviceModels`、`activate`、`refreshStatus`、`refreshCapabilities`、`getCertInfo`、`getDownloadProgress`、`identify`、`detectFace`、`checkHead`、`checkHand`、`register`、`forget`、`forgetByUid`、`userCount`、`userDetail`、`refreshClassTable`、`setBaseUrl`、`setCloudStatus`、`getCloudStatus`、`uploadPhoto`、`submitFeedback`、`bindDevice`。所有方法返回可取消的请求 ID，最后一个参数为 `HuoyanClient.Callback`。具名方法的无效参数同步抛出 `IllegalArgumentException`；有效请求进入同一通道及回执处理，不新建连接或重试写操作。

双查询接口对应晨检 SDK 壳 0.1.3。`queryLicenseModels(activationCode, callback)` 用于正常激活前按输入码查询。`queryDeviceModels(callback)` 无业务参数查询当前设备支持的模型；AIDL 无参，WS 不传 params 或传空对象，不依赖本地激活码。成功返回 `data.models[]`，每项包含 `model_code`、`model_name`、`chip`、`current`。显示 `model_name` 供选择，将所选项的 `model_code` 原样传入 `activate(activationCode, modelCode, callback)`。查询失败或列表为空时不激活。切换时先选模型，缺少本地激活码则弹窗补输或扫码，再携带所选模型重新激活；取消或点当前模型不写入，连接变化使本次选择失效。

`getStatus()`、`getCapabilities()`、`getClassTable()` 继续只读取缓存；对应的 `refresh…(callback)` 才查询壳。图像入口统一为 `byte[] rgb, int width, int height`，头/手检测增加 `int maxDetections`，登记增加 `long personId`。上传使用 `uploadPhoto(byte[] encoded, String format, callback)`，format 为 `jpeg` 或 `png`。

`request(command, params, image, callback)` 统一两通道字段。params 在提交时复制；调用方在回调前不得修改 image（推荐每帧独立缓冲）。图像始终在后台编码、写文件或传输，回调默认主线程。

| 命令 | params / image |
| --- | --- |
| init | 自动填入宿主 `app_version`，无图 |
| getStatus / getCapabilities / getCertInfo / getDownloadProgress | 空对象，无图 |
| queryLicenseModels | `activation_code` 非空字符串，用于正常激活 |
| queryDeviceModels | 无；拒绝非空 params |
| activate | activation_code、model_code（均为非空字符串） |
| setBaseUrl | url |
| identify | width、height；RGB888 |
| detectFace | width、height、max_faces（正 int，超过 64 由壳截断，通用 request 省略时默认 64）；RGB888 |
| checkHead / checkHand | width、height、max_detections（1–64）；RGB888 |
| register | person_id（正 long）、width、height；RGB888 |
| forget / userDetail | person_id（正 long） |
| forgetByUid | uid（正 long） |
| userCount | 空对象 |
| getClassTable / get_class_table | 空对象；WS 使用 get_class_table |
| uploadPhoto | format=jpeg/png；压缩图片 |
| submitFeedback | request_id、photo_file_url、feedback_type、goods 数组 |
| bindDevice | account_id、shop_id、shop_name、shop_no |
| setCloudStatus / getCloudStatus | 设置需 enabled 布尔值；查询为空对象 |

门店扫码登录优先调用 `client.bindDevice(externalShopId, callback)`。模块从发行 JAR 的 `raw/config` 读取 `Account_id` 并补入请求，`shop_id` 保留二维码原文。配置缺失、重复或非法时返回 `-20003`，明确本次绑定尚未发送。只有 `code=0` 且 `data.response.account_id/shop_id` 均为非空字符串才代表取得完整归属，`data.request` 只是请求回显。绑定不自动激活或打开云托管。

当前连接自动 `init`（含已有的有界重试）结束前，模块仅在客户端暂存 `bindDevice`，避免占用服务端 WS 的短排队窗口。初始化明确失败也可进入绑定，由壳决定是否已具备注册身份；不额外要求模型 READY 或有效证书。若初始化结果本身未知，不假定其已经结束。暂存期间取消、断开、切换配置或等待超时均明确返回“尚未发送”，不会转移到新连接。

RGB888 必须是连续 HWC 数据，长度精确等于 width×height×3，最大 32 MiB。晨检 FrameParcel 的 FD 在壳内按文件流读取，因此采用 App 私有临时文件的只读 FD（只传句柄，不传跨应用路径）；写完打开 FD 后删除目录项，真正 Binder 返回时关闭句柄。上传照片的 ImageRequestParcel 才采用 SharedMemory。模块不加载任何晨检 native 库。

结果 data 与 WS 一致：身份使用 person_id/similarity/matched，检测使用 boxes[].class_id/score/x/y/w/h，登记使用 uid/image_url，人员详情使用 items 对象数组，类别表使用 head/hand 数组和 classId/name/nameEn。证书时间为秒，状态 state_updated_at 保留壳的毫秒值，未知状态字段不强行转 false。

`detectFace(byte[] rgb, int width, int height, int maxFaces, callback)` 返回 `faces[]`，每项含归一化 `x/y/w/h`、`score` 和 10 个归一化坐标值的 `landmarks`，不包含人员身份。无脸时 `code=0` 且 `faces=[]`，检测失败不得当作无人脸。先检查能力快照 `detect_face`；旧壳缺少该能力时不调用，未激活、模型未就绪或证书过期时保留壳的原始错误。AIDL 方法追加在契约末尾，能力载体保留壳版本尾字段，并在其后追加检测能力位；旧快照读取为 `detect_face=false`。

`userDetail` 每项为 `{uid,image_available,image_url,mime_type}`，UID 与图片信息放在同一条记录中；删除单张样本时使用该项的 UID。当前 SDK 保存 JPEG，有地址时 MIME 为 `image/jpeg`；历史缺图仍返回 UID，图片可用性为 false，地址和 MIME 为空。AIDL `UserDetailParcel.getItems()` 返回不可变的 `List<UserImageParcel>`，单项提供 `getUid()/isImageAvailable()/getImageUrl()/getMimeType()`。编解码内部保留旧 UID 前缀与 URL 尾块，方法签名和事务顺序不变；回调里的 `uids` 和 `samples` 仅为同一对象列表的旧客户端兼容投影。业务层优先读取 `items`，旧壳没有该字段时才回退旧字段。

## 超时与错误

- init/activate/bindDevice 的客户端回执等待为 130 秒；uploadPhoto/submitFeedback 70 秒；其余 40 秒。绑定开始前等待初始化另有 130 秒上限，两段预算独立。
- 壳自身可能更早返回超时：当前晨检壳 WS 的 bindDevice 仍受普通命令 30 秒总预算限制，收到 40009 要保留“结果未知”，客户端等待预算不会延长服务端预算或撤销云端操作。
- `-20001`：未连接或可以确认未发送；`-20003`：客户端参数无效。
- `40010`：忙，本次未发送或壳明确拒绝。每通道只接受一张在途图像，总排队请求有限。
- `40009`：已发送后断连/超时，或切换配置时无法证明未发送；结果未知。登记、删除、反馈、绑定等写操作不得自动重放。
- `-20002`：停止等待，不代表撤销 native 操作。取消在途图片不会立即释放通道槽位。
- `-3`、`-110`、`-111` 等壳错误保留原始原因，不把所有 NotReady 都解释为清理中。连接、重连时的内部初始化只对确定暂态做有界退避；公开显式 `init` 返回一次真实回执，由调用方决定是否重试，避免与激活后的准备流程重复发起。

模块接入本身不增加客户端配对、访问授权或 WS 鉴权；访问控制策略由壳负责。

## 构建与验证

JAR 版本统一在根 `build.gradle` 中配置，独立于 App 的 `versionName`：

```groovy
ext.morningcheckClientVersion = '2.0.0'
```

`huoyanClient/build.gradle` 的 `releaseVersion` 和 App 的 JAR 引用读取同一个值。修改这一处后，JAR 文件名、JAR Manifest 的 `Implementation-Version`、发行 ZIP 文件名和 App 引用的文件名同步更新；随后仍需显式执行 `updateAppSdkJar` 生成对应文件。

在项目根目录执行独立打包：

```powershell
$env:JAVA_HOME='D:\JDK\jbr-17.0.12'
$env:GRADLE_USER_HOME='D:\Gradle'
.\gradlew.bat :huoyanClient:packageReleaseJar --offline --console=plain
```

默认输出 `huoyanClient/build/outputs/jar/huoyan-morningcheck-client-2.0.0.jar`。原 `clientJar` 任务仍可使用。更新本项目 App 的客户端时执行：

```powershell
.\gradlew.bat :huoyanClient:updateAppSdkJar --offline --console=plain
```

该命令会先构建客户端 JAR，再复制到 `app/src/main/libs/huoyan-morningcheck-client-2.0.0.jar`。交付包含运行依赖和示例的完整包时，执行 `:huoyanClient:clientDistribution`。

验证命令：

```powershell
$env:JAVA_HOME='D:\JDK\jbr-17.0.12'
$env:GRADLE_USER_HOME='D:\Gradle'
.\gradlew.bat :huoyanClient:testDebugUnitTest :huoyanClient:clientDistribution :huoyanClient:updateAppSdkJar --offline --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

输出位于 `huoyanClient/build/outputs/jar/` 和 `huoyanClient/build/distributions/`，文件名包含配置的版本（默认 `2.0.0`）。`compileExample` 独立编译 samples，`verifySdkBoundary` 检查 App/UI 反向依赖。

测试用模拟传输核对回执关联、未知结果、取消、断连重连、初始化退避、字段映射与通道切换。模拟传输通过不代表真实模型、激活、相机和云端业务验收完成。
