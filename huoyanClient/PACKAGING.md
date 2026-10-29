# 按客户设置晨检客户端发行账号

晨检客户端 JAR 在根目录携带 Java 资源 `raw/config`，默认内容为：

```properties
Account_id=2097500510197125120
```

制作客户专用 JAR 时，在仓库根目录执行（Python 3，无需重新编译 Java）：

先运行 `gradlew.bat :huoyanClient:packageReleaseJar`；版本统一由根 `build.gradle` 的 `ext.morningcheckClientVersion` 配置，模块的 `releaseVersion` 读取该值。下面以默认 `2.0.0` 为例，使用其他版本时同步替换输入和输出文件名。

```powershell
python huoyanClient/tools/set_account.py --jar huoyanClient/build/outputs/jar/huoyan-morningcheck-client-2.0.0.jar --account-id 1234567890123456789 --output huoyanClient/build/outputs/jar/huoyan-morningcheck-client-2.0.0-customer.jar
```

将示例账号改为已核实的发行账号。输出路径必须尚不存在，父目录必须已存在。工具仅替换 `raw/config` 内的账号数字，不覆盖原 JAR，也不修改 class；支持再次修改已经生成的客户 JAR。配置错误、账号非正整数或超出 Java `long` 范围、签名 JAR、重复 ZIP 条目或旧版缺少配置的 JAR 都会拒绝处理。

交付 `clientDistribution` ZIP 时，解压后用客户专用 JAR **替换** `libs/huoyan-morningcheck-client-2.0.0.jar`，再压缩交付；不要同时保留两个主 JAR。ZIP 附带本说明和 `tools/set_account.py`，但工具不会自动修改已有 ZIP。重新运行 Gradle 打包仍会使用源码中默认账号，客户专用包需重新替换。

ZIP 的 `docs/` 必须保留 `huoyan-morningcheck-client-2.0.0-integration-guide.zh-CN.docx` 和 `huoyan-morningcheck-client-2.0.0-integration-guide.en.docx`，版本与主 JAR 一致。若发布到 GitHub Release，也必须按项目原文件名同时上传这两份 Word，不设置替代展示名。修改 ZIP 或独立 JAR 后重新计算 `SHA256SUMS`，不要沿用修改前的摘要；完整约定见项目根目录或 ZIP 内的 `docs/releasing.md`。

本项目 App 使用 `app/src/main/libs/` 中的本地 JAR。普通版本可执行 `:huoyanClient:updateAppSdkJar` 更新；使用客户专用账号时，将上述客户 JAR 复制到该目录，并按 App 引用的名称保存为 `huoyan-morningcheck-client-2.0.0.jar`。后续再执行 `updateAppSdkJar` 会重新打包源码中的默认账号版本，客户专用 JAR 需要再次替换。

客户应先替换 JAR，再构建并安装其 Android App。已经安装的 APK 不会因电脑上的 JAR 被修改而改变账号。宿主打包时必须保留 Java 资源 `raw/config`，不要用 `packagingOptions` 排除它，也不要添加同名资源。运行时配置缺失、重复或非法时，`bindDevice` 返回 `-20003` 且明确提示绑定尚未发送，不回退到默认账号。

发行账号只决定门店绑定请求的客户归属；后端仍须核对设备与账号的绑定权限。它不是客户端身份认证或防篡改机制。

验证工具：

```powershell
python -m unittest discover -s huoyanClient/tools -p 'test_*.py'
```
