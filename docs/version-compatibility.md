# 版本与升级

App、客户端 JAR、晨检 SDK APK 和模型分别版本，不要求相同；不要用 App 版本推断 SDK 原生引擎、API 或模型版本。

## 当前源码基线

| 部分 | 当前基线 | 位置 |
| --- | --- | --- |
| App | `0.1.0`，`versionCode=1`，包名 `cc.huoyan.opensource.morningcheck` | `app/build.gradle` |
| 客户端 JAR | `2.0.0`，Java 11，minSdk 21 | 根 `build.gradle` 的 `morningcheckClientVersion`，`app/src/main/libs/` |
| 晨检 SDK | 配套 2.0 壳，包名 `cc.huoyan.morningcheck` | 交付 APK、SDK 状态与能力回执 |
| 人脸/头部/手部模型 | 由设备芯片、授权和选择决定 | 设备授权、当前模型及 SDK 实际状态 |

这是当前源码和文档基线，不代表某个未知 SDK 版本已经完成兼容验收。取得 SDK 时确认其支持当前 AIDL 契约、命令、账号配置、样本图片 URL 和模型查询接口，记录实际安装版本与校验值。`latest` 地址会变化，不能作为不可变版本证据。

## 通信约定

| 部署 | 客户端要求 | 配置 |
| --- | --- | --- |
| 同机 AIDL | API 27+ | `SdkConfig.aidl()`，服务 `cc.huoyan.morningcheck.app.comm.SdkCommService` |
| 同机 WS | API 21+ | `SdkConfig.webSocket("ws://127.0.0.1:18087/")` |
| 局域网 WS | 客户端 API 21+，SDK 机器满足自身要求 | `SdkConfig.webSocket("ws://<SDK设备IP>:18087/")` |

AIDL action 是 `cc.huoyan.morningcheck.SdkCommService`，不可替换为旧版 `com.huoyan` 或识物壳契约。自动通道仅对回环地址且 API 27+ 采用 AIDL；远端继续 WS，不在请求失败后误绑本机另一设备。

JAR 地址配置支持 WS/WSS，但地址可被接受不代表交付壳已启用 TLS；当前 App 默认局域网部署是明文 WS。远端服务的监听范围、访问控制和 TLS 由 SDK 部署确定。

## 升级顺序

1. 阅读目标更新记录与交付说明，核对 App、JAR、SDK、模型、Android 和 ABI。
2. 确认本机员工档案、`person_id`、人脸库及记录的保留方式，不通过卸载来替换正常升级。
3. 保持签名兼容，按交付要求升级 SDK 和 App；核对授权、模型、当前连接和相机映射。
4. 用一个测试员工验证身份、样本查看、头部/手部、温度及记录，再部署其他设备。

App 修改客户端源码或版本后需显式更新 JAR：

```bash
bash gradlew :huoyanClient:updateAppSdkJar :app:assembleDebug
```

Windows 使用 `.\gradlew.bat`。完整交付采用 `:huoyanClient:clientDistribution`，运行依赖和中英文 Word 指南必须随 ZIP 提供。发包命名见[发布规则](releasing.md)。

## 数据与身份边界

员工内部主键和 SDK `person_id` 分开。`person_id` 由本机持久序列分配、不可修改，工号改变不改变人脸归属。软删除保留历史与 ID 占用，新员工不会复用旧 `person_id`；多个独立名册共用远端 SDK 时要另行统一身份规划。

当前 SQLite 版本为 6，升级代码保留旧记录与身份关联；这不提供跨包名或卸载后的数据恢复。改包名会被 Android 视作独立 App；App 私有数据和 SDK 私有数据具有各自生命周期，不能假设重新安装后自动配对。

覆盖安装要求签名兼容且 versionCode 不低于已装版本。正式签名、公开实验签名和本机 Debug 签名分别管理，见[签名说明](../signing/README.md)。

发布时记录实际构建提交、版本组合、真机与外设、执行过的流程、已知限制和 SHA-256；未测试的组合明确说明，不把源码编译通过当作硬件支持承诺。
