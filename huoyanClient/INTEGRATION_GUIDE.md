# 火眼晨检 SDK Android 接入指南

客户端 JAR 2.0.0 · 面向 Android 开发者

业务 App 负责相机、员工档案、晨检流程和结果保存。客户端 JAR 通过 AIDL 或 WebSocket 连接独立安装的晨检 SDK 壳，由壳完成授权与模型推理。首次接入建议按顺序阅读前六章，后续章节提供接口索引、请求规则、部署和联调检查。

## 1  接入环境与工程配置

将发行 JAR 放入宿主的 app/src/main/libs/，同一 App 只保留一个客户端版本，不同时引用客户端源码模块和发行 JAR。

### 1.1 Gradle 配置

```groovy
android {
    defaultConfig { minSdk 21 }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
        coreLibraryDesugaringEnabled true
    }
}
dependencies {
    implementation files(
        'src/main/libs/huoyan-morningcheck-client-2.0.0.jar')
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    coreLibraryDesugaring(
        'com.android.tools:desugar_jdk_libs:2.1.5')
}
```

JAR 未内嵌 OkHttp、Kotlin 和 Okio。通过 Maven 引入 OkHttp 时，其余运行依赖随传递依赖引入；离线交付包的 libs 已提供相应依赖时，不要重复导入。

### 1.2 Manifest 配置

```xml
<uses-permission android:name="android.permission.INTERNET" />
<queries>
    <package android:name="cc.huoyan.morningcheck" />
</queries>
```

上述节点放在 manifest 根节点内。相机和安装权限由宿主按实际业务另行声明和申请。JAR 不合并 Manifest，也不替业务 App 请求权限。

使用 ws://、默认 HTTP 下载地址或 SDK 返回的 HTTP 图片地址时，宿主需通过 Network Security Config 或 application 的明文设置允许对应目标访问。仅在受控部署确有需要时才全局开放明文，生产范围由宿主决定。

### 1.3 打包与混淆

保留 JAR 内 Java 资源 raw/config，宿主不要提供同名资源。JAR 已带 Parcelable CREATOR 保留规则；若宿主重新打包或剥离 META-INF，需同时保留 consumer-rules.pro 中的规则，并验证 Release 构建。

## 2  建立连接与管理生命周期

优先在应用级会话中持有一个 HuoyanClient，页面订阅该会话。页面跳转无需重复 connect；相机、UI 订阅及页面在途请求仍由页面自己的生命周期管理。

### 2.1 创建客户端

```java
HuoyanClient client = new HuoyanClient(
        context.getApplicationContext(),
        new HuoyanClient.Listener() {
            @Override
            public void onConnection(
                    boolean connected, String message) {
                // 这里只表示通信连接状态。
            }

            @Override
            public void onStatus(JSONObject status) {
                // 从共享客户端读取 isReady()，更新业务可用状态。
            }
        });
client.connect(SdkConfig.automatic(SdkConfig.DEFAULT_WS_ENDPOINT));
```

需导入 cc.huoyan.morningcheck.sdk.client.HuoyanClient、SdkConfig 和 org.json.JSONObject。默认状态与业务回调在主线程交付；回调内不要执行磁盘、下载或长时间图像处理。

### 2.2 通道选择

| 配置 | 选择规则 |
| --- | --- |
| SdkConfig.automatic(url) | 回环地址且 API 27+ 使用 AIDL，其余使用传入地址的 WS |
| SdkConfig.aidl() | 强制绑定本机 SDK，API 27 以下会拒绝 |
| SdkConfig.webSocket(url) | 强制使用给定 WS 地址，可连接本机或局域网设备 |

默认地址为 ws://127.0.0.1:18087/。远端示例为 ws://192.168.1.99:18087/；这里的 IP 应替换为 SDK 设备地址。同机自定义端口若要实际走 WS，应显式使用 webSocket，不能依赖 automatic。

connect 会自动执行 init 并查询状态、能力与类别表，自动携带宿主 APK 的 versionName。不要在 onConnection 中再次无条件 init。当前 AIDL 能力版本为 4，WS 为 2，业务应读取真实能力快照。

close() 断开连接并停止重连，之后可 connect；destroy() 最终释放且不能复用。切换配置结束旧请求，不会把它们转移或重放到另一台设备。

## 3  激活与模型准备

首次激活按“输入激活码 → 查询可用模型 → 选择模型 → 提交激活 → 等待模型就绪”执行。SDK 已有授权时可先查状态，不要求用户每次重新输入激活码。

### 3.1 查询与激活

```java
client.queryLicenseModels(activationCode, (code, message, data) -> {
    if (code != 0) {
        // 显示真实错误，停止本次激活。
        return;
    }
    JSONArray models = data.optJSONArray("models");
    // 展示 models 供用户选择，不把空列表当成默认模型。
});

// selectedModelCode 来自查询结果的 model_code。
client.activate(activationCode, selectedModelCode,
        (code, message, data) -> {
            if (code == 0) client.refreshStatus(statusCallback);
        });
```

activationCode、selectedModelCode 和 statusCallback 为宿主已取得的变量；需导入 org.json.JSONArray。模型条目包含 model_code、model_name、chip、current，显示名称与提交编码分开处理。

已有授权的设备可用 queryDeviceModels(callback) 查询设备模型，无业务参数，不依赖本地保存的激活码。切换到其他模型仍需选择项的 model_code 和有效激活码；选择当前项或取消时无需提交。

### 3.2 判断就绪

| 就绪条件 | 说明 |
| --- | --- |
| 已连接 | 通信连接仍有效 |
| sdk_state 为 READY 或 OFFLINE | 本地功能可用，仍须满足其余就绪条件 |
| model_ready 为 true | 模型已准备完成 |
| certificate_valid 为 true | 证书有效 |
| key_lost 不为 true | 未丢失设备身份密钥 |

client.isReady() 统一检查上述条件。init=0 或 activate=0 不能代替它。激活成功后查询或等待状态即可，不要再机械重复 init；模型准备可能仍在异步进行。

OFFLINE 表示本地识别、检测和登记可用，仍要求模型就绪、证书有效且密钥未丢失；它不表示云端接口可用。身份尚未与云端校准时，壳会拒绝云端请求；恢复后自行发布 READY，宿主无需额外重连或重放 init。

getDownloadProgress(callback) 返回 downloaded 和 total，total=0 表示总量未知；下载进度不能替代激活结果。证书时间单位为秒，state_updated_at 为毫秒。

证书过期会限制识别、检测和登记。查询、遗忘等不因“过期本身”统一被拒，但仍可能受模型未加载、身份轮换、设备禁用等其他条件限制，以实际回执为准。

## 4  图像输入与坐标转换

### 4.1 输入格式

| 项目 | 要求 |
| --- | --- |
| 像素格式 | 连续 HWC RGB888，依次为 R、G、B |
| 字节长度 | 精确等于 width × height × 3 |
| 单图上限 | 32 MiB |
| 尺寸 | width、height 为实际提交帧的正整数尺寸 |
| 缓冲所有权 | 回调前不得修改或复用提交数组 |

identify、detectFace、checkHead、checkHand、register 都接收上述原始帧，不能直接传 JPEG、PNG、NV21 或 Bitmap 的 ARGB 数组。uploadPhoto 是独立压缩图片接口，不适用这条输入格式。

### 4.2 从 Bitmap 转换

以下方法演示已完成旋转和方向处理的 Bitmap 如何转换。生产采集链建议直接输出 RGB888，避免每帧新建 Bitmap。

```java
static byte[] toRgb888(Bitmap bitmap) {
    int width = bitmap.getWidth(), height = bitmap.getHeight();
    int[] pixels = new int[width * height];
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
    byte[] rgb = new byte[pixels.length * 3];
    for (int i = 0, j = 0; i < pixels.length; i++) {
        int value = pixels[i];
        rgb[j++] = (byte) ((value >> 16) & 0xff);
        rgb[j++] = (byte) ((value >> 8) & 0xff);
        rgb[j++] = (byte) (value & 0xff);
    }
    return rgb;
}
```

调用前限制 Bitmap 尺寸以满足单图上限。转换和采集在后台执行，UI 只更新显示。每个通道同时只提交一张在途图像，收到回执后再提交下一张，避免把所有预览帧排队。

### 4.3 检测框坐标

x、y 是相对提交帧的归一化左上角，w、h 为归一化宽高。原图坐标为 left=x×width、top=y×height、right=(x+w)×width、bottom=(y+h)×height。

绘制到预览时，还需应用与相机一致的旋转、镜像、缩放和裁剪偏移。输入帧与预览方向不一致时，仅乘控件宽高会导致框漂移。

## 5  人脸录入与样本管理

业务人员 ID 与样本 UID 是不同标识。personId 表示员工，uid 表示一次成功登记的样本；一个员工可以有多份样本。

### 5.1 录入与读取图片

```java
long personId = 1001L; // 宿主员工档案中的稳定正整数 ID。
client.register(rgb, width, height, personId,
        (code, message, data) -> {
            if (code != 0) return;
            long uid = data.optLong("uid", 0);
            if (uid <= 0) return; // 未取得有效样本 ID，不记录录入成功。
            String imageUrl = data.optString("image_url", "");
            // 保存 UID 关联，并在后台加载可用的图片地址。
            client.userDetail(personId,
                    (detailCode, detailMessage, detail) -> {
                        if (detailCode != 0) return;
                        JSONArray items = detail.optJSONArray("items");
                        // 按每条记录中的 UID、可用性和 URL 一起处理。
                    });
        });
```

| items 字段 | 类型与含义 |
| --- | --- |
| uid | 正 int64，删除样本时使用的 ID |
| image_available | boolean，原图是否可读取 |
| image_url | 原图 HTTP 地址，不可用时为空 |
| mime_type | 有效 JPEG 为 image/jpeg，不可用时为空 |

优先读取 items；仅旧壳缺少该字段时回退 uids 或 samples。缺图不表示未录入，应保留 UID 并显示图片不可用。当前 SDK 保存提交帧的 JPEG，未自动裁脸，也不在 register 时自动上传 COS。保存图片失败不会把已经成功登记的 UID 改报为登记失败。

### 5.2 删除与清空

forgetByUid(uid, callback) 删除一张样本；forget(personId, callback) 清空该员工全部样本，不会删除业务 App 的员工档案。userCount(callback) 返回壳内已登记人员数，不是宿主员工总数。

personId 与 uid 必须使用正 long，不经过 double 转换，也不要把图片显示序号当作 UID。发生 40009 时先查询 userDetail 核对实际样本，不能立即重新登记或重放删除。

图片 HTTP 端口由 SDK 动态分配，并非 WS 的 18087。重连后重新查询 URL，不自行拼接或永久依赖旧地址。跨设备 WS 使用 SDK 返回的设备地址；本机 AIDL 通常返回回环地址。

## 6  身份识别与头部手部检测

### 6.1 识别身份与检测人脸

```java
client.identify(rgb, width, height, (code, message, data) -> {
    if (code != 0) return;
    boolean matched = data.optBoolean("matched", false);
    if (matched) {
        long personId = data.optLong("person_id", 0);
        double similarity = data.optDouble("similarity");
        // 按 personId 查询宿主员工档案。
    }
});
```

detectFace(rgb, width, height, maxFaces, callback) 只检测人脸框，不识别员工。调用前检查 getCapabilities() 的 detect_face 能力；旧壳没有该能力时不要调用。

detectFace 成功返回 faces 数组，每项含 x、y、w、h、score 和 landmarks；landmarks 为 10 个归一化坐标值。code=0 且 faces=[] 才可解释为本次未检测到人脸。identify 的 matched=false 只表示未匹配成功，不能据此断定无人脸。

### 6.2 检测头部与手部

```java
client.checkHead(rgb, width, height, 32,
        (code, message, data) -> {
            if (code != 0) return;
            JSONArray boxes = data.optJSONArray("boxes");
            // 用头部类别表解释 class_id。
        });
```

头部请求回执返回、业务进入手部阶段后，再调用 checkHand(rgb, width, height, 32, handCallback)，其中 handCallback 为宿主回调。不要同时提交头部和手部图像。maxDetections 范围为 1 至 64；maxFaces 必须为正 int，大于 64 时由壳截断。

一次检测可以返回多个目标，boxes 是这些目标组成的列表。下表中的 boxes[] 表示列表中的任意一个目标，例如 boxes[0].score 就是第一个目标的置信度。

| 检测结果字段 | 含义与用法 |
| --- | --- |
| boxes[].class_id | 该目标的类别编码；在对应类别表中找到 classId 相同的条目，再读取类别名称 |
| boxes[].score | 该目标的检测置信度，取值为 0 至 1；值越大，模型对该结果越确信，可结合业务阈值筛选 |
| boxes[].x | 检测框左上角的横向位置；乘以提交图像的宽度，得到像素横坐标 |
| boxes[].y | 检测框左上角的纵向位置；乘以提交图像的高度，得到像素纵坐标 |
| boxes[].w | 检测框的宽度；乘以提交图像的宽度，得到像素宽度 |
| boxes[].h | 检测框的高度；乘以提交图像的高度，得到像素高度 |

x、y、w、h 都相对于本次提交图像归一化，不是像素值。绘制到相机预览时，还要按第 4 章处理旋转、镜像与缩放。

类别表用来把类别编码转换成可显示的名称。checkHead 的结果对照 head 类别表，checkHand 的结果对照 hand 类别表；两张类别表中的每个条目包含以下字段。

| 类别表字段 | 说明 |
| --- | --- |
| classId | 类别编码，与检测结果中的 class_id 对应；注意两个字段的命名不同 |
| name | 类别名称，可用于界面显示 |
| nameEn | 类别的英文名称 |

使用 refreshClassTable(callback) 查询类别表，getClassTable() 只读取缓存。业务 App 根据查到的类别名称和自身规则判定口罩、工作帽、手背、手心及异常，不把某个类别编码写死为固定业务含义。

## 7  公共接口索引

所有具名业务方法最后一个参数均为 HuoyanClient.Callback，返回 String requestId。表中省略该回调参数；图像参数 rgb、width、height 含义见第 4 章。

### 7.1 连接状态与授权

| 方法与参数 | 用途 |
| --- | --- |
| init() | 显式初始化一次；正常连接已自动执行 |
| queryLicenseModels(String activationCode) | 按输入激活码查询可用模型 |
| queryDeviceModels() | 无参查询当前设备授权模型 |
| activate(String activationCode, String modelCode) | 使用选择的模型编码激活 |
| refreshStatus() | 查询状态并更新缓存 |
| refreshCapabilities() | 查询能力并更新缓存 |
| getCertInfo() | 查询证书及 SDK 版本等信息 |
| getDownloadProgress() | 查询模型下载字节进度 |

### 7.2 识别检测与人员底库

| 方法与参数 | 用途 |
| --- | --- |
| identify(rgb, width, height) | 识别员工身份 |
| detectFace(rgb, width, height, int maxFaces) | 检测人脸框和关键点 |
| checkHead(rgb, width, height, int maxDetections) | 头部检测 |
| checkHand(rgb, width, height, int maxDetections) | 手部检测 |
| register(rgb, width, height, long personId) | 为员工登记一份样本 |
| forget(long personId) | 清空员工全部样本 |
| forgetByUid(long uid) | 删除指定样本 |
| userCount() | 查询已登记人员数量 |
| userDetail(long personId) | 查询样本 UID 和图片信息 |
| refreshClassTable() | 查询当前模型的头手类别表 |

方法入参不合法时，具名入口通常同步抛出 IllegalArgumentException；这不代表请求已发送。成功回执结构由方法决定，只有 code=0 才按成功结构解析。

### 7.3 可选云接口

| 方法与参数 | 用途 |
| --- | --- |
| bindDevice(String externalShopId) | 以发行账号绑定外部门店 |
| setBaseUrl(String url) | 修改 SDK 云服务基址 |
| setCloudStatus(boolean enabled) | 设置 SDK 云托管开关 |
| getCloudStatus() | 查询实际云托管开关 |
| uploadPhoto(byte[] encoded, String format) | 上传 JPEG 或 PNG 图片 |
| submitFeedback(JSONObject feedback) | 提交反馈，字段见第 9 章 |

上述方法与前述接口合计 24 个具名业务入口。setBaseUrl 修改的是 SDK 的云服务基址，与业务 App 自己的服务器地址不同。

### 7.4 客户端控制与缓存

| 方法 | 行为 |
| --- | --- |
| connect(SdkConfig config) | 异步建立连接并自动初始化 |
| isConnected() 和 isReady() | 分别读取连接与推理就绪状态 |
| getActiveChannel() | 返回选用通道；未 connect 或 close 后为 null，断线重连期间保留通道 |
| getStatus() | 只读状态缓存，无快照时为空对象 |
| getCapabilities() | 只读能力缓存 |
| getClassTable() | 只读类别表缓存 |
| cancel(String requestId) | 停止等待指定请求 |
| close() | 断开，可再次 connect |
| destroy() | 最终释放，不可复用 |

### 7.5 通用请求入口

request(String command, JSONObject params, byte[] image, Callback callback) 用于直接提交统一协议请求。优先使用具名方法，避免手工拼错字段。params 在提交时复制，图像数组仍须保持到回调。

通用请求的 register 参数为 person_id、width、height 和 format="rgb"。queryDeviceModels 不接受业务参数。类别表命令由客户端将 getClassTable 映射为 WS 的 get_class_table，无需宿主自行处理 AIDL 与 WS 的字段差异。

## 8  回调错误与请求控制

### 8.1 回调与请求 ID

```java
String requestId = client.userDetail(personId,
        (code, message, data) -> {
            if (code == 0) {
                // 处理本次成功数据。
            } else {
                // 根据错误码和真实 message 处理。
            }
        });
// 页面停止等待该请求时：client.cancel(requestId);
```

取消是停止等待，不代表 SDK 已停止执行，也不会立即释放底层在途图像槽位。页面退出或切换员工后，需丢弃旧页面、旧员工的回调；共享客户端则继续服务其他页面。

### 8.2 常用错误码

| 错误码 | 含义与处理 |
| --- | --- |
| 0 | 成功，按当前接口解析 data |
| -20001 | 未连接或可以确认尚未发送；恢复连接后再由业务决定 |
| -20002 | 停止等待，不等于撤销已开始的操作 |
| -20003 | 客户端参数或发行账号配置无效；修正后再发 |
| 40010 | 忙或明确拒绝；降低并发并按业务退避 |
| 40009 | 已发送后的断连或超时等，结果未知；先核对实际状态 |
| -3 等壳错误 | 保留真实原因，结合状态定位；不可统一解释为清理中 |

登记、删除、绑定、反馈等写操作收到 40009 后不能自动重放。可查询人员详情、门店归属或其他实际状态，再决定是否人工重试。

### 8.3 等待预算

init、activate、bindDevice 的客户端回执等待为 130 秒；uploadPhoto、submitFeedback 为 70 秒，其余请求为 40 秒。bindDevice 在发送前等待当前自动初始化另有 130 秒上限，两段预算独立。

SDK 壳可能更早返回超时；客户端预算不会延长服务端执行时间。连接时的内部初始化只对确定暂态做有界重试；公开 init(callback) 返回一次真实回执。

## 9  部署与可选接口

### 9.1 安装 SDK 壳

默认发行地址保留以下原始拼写：

http://f.huoyan.cc/apk/morningcheck_sdk_lastest.apk

deployment.SdkShell 提供包名、已安装版本查询与默认下载地址。SdkApkDownloader(url, directory) 将文件下载到宿主提供的现有目录，start(listener) 启动，cancel() 停止；同一下载器只启动一次。

下载回调在后台线程交付，未知总大小为负值。宿主负责文件校验、安装权限、FileProvider、安装交互和清理；下载器不持有 Activity，也不自动安装。默认下载为 HTTP，需配置对应明文流量许可。

### 9.2 门店绑定

```java
client.bindDevice(externalShopId, (code, message, data) -> {
    if (code != 0) return;
    JSONObject response = data.optJSONObject("response");
    // 校验 response.account_id 和 response.shop_id 非空。
});
```

externalShopId 原样传入，发行账号由 JAR 的 raw/config 读取。只有 code=0 且 data.response 的归属字段有效才表示取得完整归属，data.request 只是回显。账号配置异常明确返回尚未发送。

绑定不自动激活，也不自动打开云托管。初始化未结束时，客户端暂存绑定请求，初始化结束后只提交一次，不额外要求模型 READY。

### 9.3 图片上传与反馈

uploadPhoto(encoded, "jpeg", callback) 或 uploadPhoto(encoded, "png", callback) 接收对应的压缩图片，成功读取 data.photo_file_url。submitFeedback(feedback, callback) 必填 request_id、photo_file_url，可选 feedback_type 与 goods 数组。

goods 条目可携带 goods_id、goods_name、goods_plu、source、selected、distance、width、height、x、y。仅需要反馈业务时调用；正常人脸录入不会隐式替你执行 uploadPhoto 或 submitFeedback。

JAR 内发行账号用于绑定归属，不是客户端访问鉴权。此版本未增加客户端配对、AIDL 授权或 WS 鉴权，不能把导入 JAR 视为机器已受访问保护。

## 10  联调检查与问题定位

### 10.1 建议验收顺序

| 步骤 | 验证内容 |
| --- | --- |
| 1 工程与安装 | Debug 和 Release 构建成功；SDK 壳已安装 |
| 2 建立连接 | 本机通道正确；远端 WS 连接目标设备 |
| 3 授权准备 | 按激活码查模型；激活后最终 isReady 为 true |
| 4 人脸底库 | 录入后取得正 UID；详情能读图片；可删单张和清空 |
| 5 识别检测 | 已录员工识别正确；无人脸与未匹配分开处理 |
| 6 框与类别 | 旋转镜像后框仍对齐；类别按 head 和 hand 表解析 |
| 7 生命周期 | 切页、重连、切员工不消费旧回执或重复写入 |
| 8 异常恢复 | 断网、忙、证书过期、结果未知都有对应处理 |

### 10.2 常见问题

| 现象 | 优先检查 |
| --- | --- |
| AIDL 无法绑定 | SDK 包名可见性、安装状态、系统 API 与配套契约版本 |
| WS 连不上 | 地址与端口、同网段可达性、明文设置、服务是否开始监听 |
| 激活成功仍不能识别 | 最新状态、模型下载与证书，不重复堆叠 init |
| 图像调用参数错误 | RGB 顺序、精确字节长度、尺寸、上限和缓冲复用 |
| 框位置漂移 | 输入与预览的旋转、镜像、裁剪和缩放矩阵 |
| UID 存在却无图片 | image_available；旧样本缺图不会丢失学习状态 |
| 图片地址突然不可用 | 重连后重新查 userDetail，不沿用旧动态端口 |
| 工号无法直接登记 | 当前接口只接收正 long，应使用稳定内部人员 ID |

联调日志建议保留客户端版本、实际通道、请求 ID、命令、错误码、message 和状态快照；激活码及业务个人信息按宿主的日志规则处理。模拟测试与代码编译不能代替现场相机、授权服务器及真实模型验收。

本指南按客户端发行 JAR 2.0.0 的公共接口和配套晨检壳契约核对。升级 JAR 或 SDK 壳后，应重新验证能力版本、图像接口、人员 ID 类型及激活流程。
