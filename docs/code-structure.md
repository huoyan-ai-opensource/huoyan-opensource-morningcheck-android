# 代码结构与职责

[文档导航](README.md) · [构建](build.md) · [测试](testing.md)

工程由 `:app` 和 `:huoyanClient` 两个模块组成。App 是员工、设备与晨检业务 UI；客户端模块封装与独立 SDK 壳的通信，App 消费其已打包 JAR，不包含原生推理引擎。

```text
App Activity / 本地业务
    → MorningSdkSession
    → huoyan-morningcheck-client JAR
    → AIDL 或 WebSocket
    → 独立晨检 SDK APK / 模型
```

## 源码导航

`app/src/main/java/cc/huoyan/opensource/morningcheck/`：

| 位置 | 职责 |
| --- | --- |
| `HomeActivity` | 首页入口、连接/安装引导、当天本地统计、入口滚动位置 |
| `StaffActivity` / `StaffEditActivity` | 员工分页列表与独立 Activity 承载的资料编辑器 |
| `DepartmentsActivity` | 部门树、搜索、分页、编辑及员工/记录筛选入口 |
| `FaceEnrollmentActivity` | 同页选人、预览、拍照学习、样本展示和删除 |
| `CheckActivity` / `ResultActivity` | 采集编排、异常提示、保存、连续下一位与密码退出 |
| `RecordsActivity` / `RecordDetailActivity` / `RecordPhotoActivity` | 日期及业务筛选、分页、快照和照片查看 |
| 各 `*SettingsActivity` | SDK、授权、相机、规则、温度、补光和系统配置 |
| `domain/` | 纯业务流水线、规则、语音、日期、人脸与采集会话判定 |
| `data/` | SQLite、部门树、员工策略、记录及照片文件 |
| `camera/` / `hardware/` | Camera2/UVC、图像转换、测温协议、串口和 GPIO |
| `sdk/` | App 连接配置、进程共享会话、状态到业务提示的映射 |
| `cloud/` / `login/` | 业务云员工查询与导入、登录/绑定协调 |
| `input/` | 既有输入组件；普通文本输入当前使用系统输入法 |

XML 布局统一位于 `app/src/main/res/layout*`，多尺寸资源按 Android 限定符选择；layout 目录不再建立子目录。页面头部和通用样式由共享资源复用。

## 晨检与采集

`domain/CheckPipeline` 只消费串行有效结果，不启动计时器、不保存数据库。`CheckActivity` 持有页面采集生命周期、超时与旧回包隔离，每轮开始固定规则；正常身份、头部与手部需要连续有效样本，不能用上一轮温度或旧图像推进新员工。

`CameraMorningActivity` 管理采集页面相机生命周期。实际 RGB 画面可以先定位人脸，但员工身份仍来自 SDK；“有人脸框”不等于“员工已匹配”。异常证据按采集结果保存，手部标记、时间和体温不能由展示层猜测。

语音按身份、头部、手背、手心和结果分阶段：同阶段不互相打断，跨阶段切换取消上一阶段语音；未入镜与未匹配员工分别处理。结果自动下一位与“停留查看”由结果页面协调，退出验证期间暂停自动推进。

`TemperatureSource` 在页面生命周期内持有串口，算法模式切换与主动/被动读数独立；切模式只在真实 `success` 后确认。`LightController` 复用统一配置，晨检、人脸采集和测试页按各自生命周期开关，退出和后台释放。

## 数据与照片

`AppStore` 持有员工、部门和记录数据库，当前版本 6。员工本机主键、工号与 SDK `person_id` 分别管理；工号面向操作人员，`person_id` 是不可变 SDK 身份。员工软删除保留历史占用，查询和当前名册排除已删除档案。

晨检记录及证据照片存放 App 私有空间，照片目录为 `files/check-photos`。记录展示采集时的快照，不因员工改名/调岗重写历史；当前不调度记录上传或补充照片。

录入样本原图由 SDK 保存。App 通过 `register.image_url` 与 `userDetail.items[]` 读取展示，不在 App 再建样本原图库。样本 UID 保留 64 位精度，删除和清空必须走 SDK 回执，不能只改缩略图列表。

首页统计按当前名册与当天最新有效记录计人数，记录列表保留每次采集。日期筛选使用本机时区，按 `[起点, 终点)` 处理，不把次日零点包含进当天。

## 会话与模块边界

`MorningApplication` 持有进程共享 `MorningSdkSession`、本地存储和业务网络执行器。页面订阅会话，停止后退订，不因切页关闭整个连接；已提交的写操作由会话继续收尾，过期页面回调不得改写新状态。

`huoyanClient` 公共包为 `cc.huoyan.morningcheck.sdk.client`，负责 AIDL、WS、请求超时、重连、图片协议和 SDK APK 下载辅助，不持有 App Activity、布局或本地业务数据库。第三方示例必须只依赖发行 JAR 与 Android API 编译。

激活输入、模型选择与提交由 `ActivationFlowFragment` 及模型会话协调。激活回执不能被后续只读刷新延后或改写，也不能人为标记模型 READY。SDK 自身仍是授权与推理准入的决定者。

实现维护时以代码及[项目约定](../AGENTS.md)为准，不将历史设计稿、联调截图或旧文档当作现行接口契约。变更行为后同步公开文档及对应测试。
