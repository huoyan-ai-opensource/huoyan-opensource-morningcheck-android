# 激活模型选择 · 2026-10-20

> 本文保留当时的实现与验收记录。当前首页与“授权与密钥”页统一使用 `ActivationFlowFragment`；激活按成功回执立即结束，当前模型信息统一放在“授权与密钥”页，切换模型入口暂时隐藏，晨检规则不再提供该入口。现行流程以 [README](../README.md) 为准，以下历史下载与就绪等待描述不代表当前实现。

晨检客户端按识物客户端的 `ModelActivationPresenter` 流程接入：输入或扫码 → 查询激活码支持的模型 → 点选模型 → 携带 `activation_code` 与 `model_code` 激活。首次激活不预选模型；列表名称来自接口，写入使用对应的模型编码。取消、空列表或查询失败返回原输入草稿。查询与激活使用 Loading，失败和成功使用 ToastEx。

## 设计与实现

已先更新并保存 `huoyan-morningcheck.pen`，再实现客户端。设计中的模型 A/B/C 是示例数据。

| 页面 | 横屏 / 竖屏设计节点 | 实现 |
| --- | --- | --- |
| 激活码输入 | c0KAo / VWxYE；EF0fj / V9Ipo | `SdkActivationCodeDialog`、`dialog_sdk_activation_code.xml`；确认按钮改为“下一步” |
| 53 / 激活选择模型 | Dei21 / DAWJ6 | `LicenseSettingsActivity`、`DesignChoiceDialog`、`dialog_design_choice.xml`、`item_design_choice.xml` |
| 查询与激活进度 | 沿用项目 Loading | `LoadingDialog`；删除旧 `dialog_sdk_activation.xml` 及专用文案 |

`ModelSelectionViewModel` 保留同一次查询和选择会话；页面重建不重放请求，取消及连接变化使迟到回调失效。激活期间的下载进度和成功回执后的就绪确认由 `MorningSdkSession` 持有。SDK 壳在 `activate` 内完成模型准备；客户端收到成功后只查询 `getStatus`，不再追加 `init`。

当前接口已拆分为 `queryLicenseModels(activationCode)`（正常激活，要求非空码）和 `queryDeviceModels()`（当前设备，无参）。切换时先查询设备模型，选中其他模型后才在缺码情况下弹窗补码；提交 `activation_code + model_code`，不再次查询。当前模型与取消均不提交。

AIDL 契约以 SDK 仓库的 `ISdkCommService.aidl` 为准，模型信息使用 `LicenseModelParcel` / `LicenseModelsParcel`；WS 与 AIDL 均支持上述两个独立查询接口。`queryLicenseModels` 拒绝缺省、null 和空白激活码；`queryDeviceModels` 不接受业务参数。`activate` 必须包含非空 `activation_code` 与 `model_code`。

## C106 验证

设备 `e4962a74f46630da`，原生横屏 1280×800、density 240；SDK 壳版本 0.1.1。安装客户端 Debug 和测试包，无清数据操作。测试结束恢复应用偏好和连接配置，最终启动客户端桌面入口。

- 真实 SDK：`RealLicenseModelsDeviceTest` 通过。AIDL 与本机 WS 均成功查询当前授权、指定激活码的模型列表，检查非空模型、列表数量、名称/芯片/current 字段及跨通道首项编码一致。
- 页面回归：`LicenseSettingsUiTest` 9 项和 `ShopLoginNavigationTest` 7 项均通过。覆盖选模后才激活、携带所选编码、重复点击、输入和选模重建、取消/错误/空列表恢复草稿、连接配置变更拒绝写入、进度/初始化/失败及离页恢复。
- 首轮 16 项中 1 项旧测试加载已停用的授权布局而失败；改为当前 Activity 返回的布局后，失败项与大字体项补跑均通过。原始与补跑日志保留在同目录下，未把首轮当作全绿。
- 130% 应用字体（当前标准 1.15 × 1.30，实际 fontScale 1.495）下，长名称换行，12 项列表可到达末项，取消按钮固定可见。系统字体、分辨率和 density 未改动。
- C106 固件 `mSupportAutoRotation=false`，不响应竖屏请求。已保存并检查横竖屏设计，但本轮没有完成竖屏实机验收；旋转设置恢复为原生 `lock 0`。
- 真机页面回归使用隔离 WS 服务模拟激活回执；真实 SDK 验证仅连接、初始化及查询，未在真实授权上提交激活或变更人员底库。

## 构建

JDK 17.0.14，执行 `:app:assembleDebug :app:assembleDebugAndroidTest :huoyanClient:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug` 成功。App 349 项、客户端库 87 项单元测试全部通过。Lint 0 errors、2309 warnings；本次新增选模类和授权 Activity 无 Lint 报告。`git diff --check` 通过。

产物：`app/build/outputs/apk/debug/app-debug.apk`，已覆盖安装至 C106。

## 本轮证据

- [真实 SDK 双通道日志](qa-model-selection-20261020/c106-real-models.log)
- [页面回归首轮日志](qa-model-selection-20261020/c106-regression-first.log)
- [修正后补跑日志](qa-model-selection-20261020/c106-regression-rerun.log)
- [标准字号选择模型](qa-model-selection-20261020/c106/model-selection-qa/model-selection-landscape.png)
- [130% 字体：长名称](qa-model-selection-20261020/c106/model-selection-qa/model-selection-130-native-top.png)
- [130% 字体：列表末项与取消](qa-model-selection-20261020/c106/model-selection-qa/model-selection-130-native-bottom.png)

## 激活后移除重复初始化

客户端收到 `activate` 成功回执后直接读回 `getStatus`，确认 `READY`、模型就绪、证书有效且未丢失密钥。已删除激活后的 `init` 调用、重试计数和专用阶段。

验证：35 项 `MorningSdkSessionTest` 通过；C106 上 5 项隔离 WS 页面回归通过，覆盖重复点击与重建、跨页等待、未知下载总量、进度 BUSY 和激活失败，均断言没有追加 `init`。Debug 客户端已覆盖安装到 C106 并启动；本轮未使用真实激活码重新激活。
