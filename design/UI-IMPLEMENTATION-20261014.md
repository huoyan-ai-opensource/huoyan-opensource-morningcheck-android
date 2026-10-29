# 设计稿实现记录

设计源：`design/huoyan-morningcheck.pen`。本轮只读取设计，未修改设计文件；工作区原有的设计改动继续保留。

以设计稿的结构、字号层级与操作位置为准。允许修改展示和交互；数据存储、SDK 协议、识别判定、相机坐标变换及硬件协议保持原有实现。沿用原始尺寸资源表，未修改设备字体、密度或分辨率。

## 页面对应

以下布局均位于 `app/src/main/res`；横竖屏有独立结构的页面使用 `layout-land` 与默认 `layout`。

| 页面 | 横屏 / 竖屏节点 | 当前主要布局 |
| --- | --- | --- |
| 首页 | UoxpD / WiXSI | activity_home_design、view_home_design_* |
| 员工 | ym39n / d3FlcQ | activity_staff、view_staff_filters、item_staff_row |
| 员工编辑 | t2uDc / noGMA | activity_staff_editor_dialog、view_staff_edit_design_form |
| 部门 | pOFpT / j4XRJS | view_departments_design、item_department_design |
| 部门弹窗 | jck8w / T0gmRP、W0bic / PMuan | dialog_department_editor、view_department_fields |
| 人脸录入 | W7eV5A / RqgYY | activity_face_design、view_face_* |
| 选择员工 | vqefb / PHsID | view_face_staff_selection、CenteredDialogLayout |
| 晨检待机 / 检查 | OlRVP / f5BMbz、cAoE4 / Q8x2Y、g72E6Z / Jh0un | activity_check_design_*、view_check_design_info、view_check_hand_guide |
| 结果 | QELKo / xcfDH、heZbD / f7hY6 | activity_result_design、view_result_design_* |
| 记录列表 / 详情 | Fz7sh / VkCYX、dplu9 / JOlrd | activity_records、activity_record_design |
| 照片查看 | iwVQd / nGtBI | activity_record_photo |
| SDK 连接 | JHhNK / e3Aj93、w70Bab / La04p | view_sdk_design |
| 设备授权 | zyDtS / kPSDR | view_license_design、view_license_device、view_license_certificate |
| 相机 | kTzKC / ciUm0 | view_camera_design_* |
| 晨检规则 | Cccmp / Ogl2X | view_rules_design_* |
| 测温设备 | B7M7Zy / xlZP8 | view_temperature_design_* |
| 系统设置 | GoqeE / T6I1sd | view_system_design、view_system_preferences、view_system_information |
| 业务云地址 | GMo6t / b2QXC | dialog_business_cloud_design |
| SDK 状态 | dZ0Cp / Iis7n | activity_sdk_details、item_record_design_field |
| 场所信息 | k3jx9 / j9hQH | view_shop_design |
| 激活码输入 | c0KAo / VWxYE、EF0fj / V9Ipo | dialog_sdk_activation_code、SdkActivationCodeDialog |
| 扫码激活 / 登录 | M1Xyrj / eMAuX、vWRn0 / p8Qjh | activity_sdk_activation_scan、activity_login、ScanDialogLayout |
| 补光 | GtqxT / UFdF3、Z9Jhi / vxsNq | activity_lighting_settings、view_lighting_* |
| 样本删除／清空确认 | M39i9A / Ug563 | ProjectDialog（已统一确认弹窗样式） |
| 日期范围 | LFqbX / T14i40 | dialog_record_date_design |
| 相机／部门单选、空态、滚动末尾 | k7YLn5、d6Cy7 / yfx4P、s5KEq5 / Z7yWY0、vZJI0 / D9NojM | dialog_design_choice、item_design_choice |
| 首页 SDK 未安装／未激活／等待启动 | wSYYg / v10PVs、Pcfcv / O8aGq9、lHNjt / ogVD0 | view_home_design_hero |
| 设备断连 | T0pzN / cXreZ | activity_check_disconnected |
| 体温阈值数字键盘 | y353v0 / eeXe2 | dialog_temperature_threshold |

## 交互调整

- 员工和记录改为内部纵向滚动、连续加载。控制器按现有每页 20 条接口累积数据，未修改 AppStore。
- 员工行直接提供录入、编辑、删除；删除仍经过原有 SDK 状态检查和确认。
- 部门树在同一列表展开。长按部门可查看员工、记录或新增下级部门。
- 员工与部门编辑使用居中弹窗，键盘出现时保留固定操作区。
- 人脸录入保留现有采集、即时学习、删除及清空流程；选择人员改为覆盖层，横屏三列、竖屏两列，内部连续加载，查询后重置。
- 录入工作台增加“当前人员”标签，工号与姓名同行；样本横屏三列、竖屏四列，删除按钮位于缩略图右上角。长按身份条可查看部门／录入状态，长按预览面板或下方提示可进入原相机设置。清空／单删使用设计确认框，仍先检查员工、设备、查询代次与共享写锁。
- 录入预览移除旧标题和白色引导边框，检测框与图像坐标变换不变。返回按钮统一显示“返回”；原刷新功能保留在“已录入图片”标题长按入口。
- 记录详情同时展示摘要和照片。长按摘要查看健康证、测温来源和场所等补充信息；上传按钮复用原有上传方法。
- 晨检沿用原有自动启动条件，底部提供退出；长按进度文字可显示原有暂停按钮。
- 首页保留统计功能在主按钮长按入口；人脸录入、部门与补光入口分别保留在员工页和相机页。
- 首页加入设计品牌图、水印与版权；版本、设备型号和连接方式显示实际值。
- 结果页分开呈现体温、头部和手部结果；异常项按记录显示，正常记录不占用异常区。照片页增加元数据栏和固定上一张／下一张操作。
- 系统设置按横屏双栏、竖屏上下排列；字号选择后仍需应用，业务云地址用居中表单。长按业务云入口保留原有 Token 等完整配置。
- 场所名称可编辑，联系电话与地址待接入；业务门店 ID、设备 ID、登录等原功能保留在可展开的业务配置区。
- SDK 连接标签只修改表单草稿，保存并连接时才写入配置。选择本机后使用默认本地端点，切回局域网保留未提交地址。
- 激活码弹窗按设计改为纵向排列：顶部扫码、四段输入、四行大写键盘、字符说明、清空、取消及激活。保留字符过滤、选区编辑、退格和重复提交保护。
- 扫码激活／登录改为透明 Activity 上的居中弹窗，预览保持 16:9。点遮罩可取消；异常时显示原重试、选相机和权限操作，长按预览可展开操作区。扫码解析、激活返回和登录协调器保持原流程。
- 晨检待机补充头部相机、手部相机、测温设备三行状态；自动检查未启动时显示“待检测”，不把设计中的示例连接状态当成真实状态。
- 日期弹窗使用今天、昨天、近 7 天、本月四个快捷入口；取消不应用草稿，重置后应用恢复全部日期。昨天／本月转换为原有自定义日期范围，普通日历选取覆盖完整自然日。长按标题保留原快捷范围、精确时间、跟随当前时刻和年月跳转。
- 部门筛选统一使用单选弹窗，长列表内部滚动，长按说明展开原搜索功能；重复选择与取消不改变筛选。相机选择沿用原草稿和保存机制，弹窗按打开时的设备列表回传，取消不切换镜头。
- 体温阈值使用独立数字键盘，取消保留原值，确定后沿用原 36.1–43°C 校验与保存；无效输入留在弹窗中提示。
- 首页按真实状态显示红色未安装、黄色待激活、灰色等待及蓝色就绪卡片；安装复用原流程，立即激活进入设备授权。检查过程中 SDK 断连显示居中的断连状态，保留原丢弃未完成检查及恢复流程，提供首页和连接设置入口。

## 待定及尚未完成的差异

这些项目不能作为已完成设计验收：

- 相机分辨率选择、镜像开关与画面裁剪没有现成配置能力。当前分辨率显示真实帧尺寸，未取得帧时显示待获取；镜像标为待定。
- SDK 局域网自动发现、云端服务开关暂无接入；保留现有完整 WebSocket 地址、打开 SDK、初始化和连接能力。
- 授权页硬件序列号在无系统权限时显示待确认，不用设计样例或 Android ID 冒充序列号。
- 补光开灯命令显示设备实现中的固定 ASCII 命令 A00101A0，自定义命令待定。
- 用户已确认人脸录入保留拍照后即时提交，底部按稿保留“拍照录入／遗忘／保存录入”三个入口，保存入口明确标为待定并说明无需重复保存。未实现暂存批次，也未放宽原 5 秒帧有效期或改变 SDK 写入流程。
- 分辨率单选（31）与 SDK 发现设备列表（37）缺少原有配置／发现能力，继续待定；通用单选样式已用于现有相机和部门选择。待机阶段不新增硬件探测，三行设备状态在实际检查开始前显示待检测。
- 场所电话、地址没有对应持久化字段，显示待定；原业务配置继续可达。
- StaffStatusUiTest 已迁移。DataPaginationUiTest 仅迁移本次运行的照片浏览、SDK 详情两项；其余旧固定分页／分层部门断言、部门数量门槛、日期旧几何及激活码／扫码测试的旧自适应几何断言，尚未全部迁移。不能宣称全量设备测试通过。

## 验证边界

- 只使用 C106（e4962a74f46630da）横屏与 NB55（NB802026050075）竖屏。未操作连接中的 C50D，未使用模拟器。
- 实机 Activity 测试使用独立 UUID 数据库、回环 SDK 端点和不存在的 USB 镜头 ID；结束后恢复配置并删除测试库。不修改现场员工、晨检记录或外设状态。
- 因此截图覆盖真实设备布局和交互，不代表已完成真实相机识别、串口测温、补光或在线激活联调。
- 单元测试：app 313 项、huoyanClient 76 项通过，无失败、错误或跳过。
- APK 与 androidTest APK 构建成功。Lint 仍失败：AppInputKeyboard 原有 6 项 WindowCallbackWrapper RestrictedApi 错误，1849 项警告；未添加抑制或 baseline。新增确认框误用的受限 Builder 方法已改为公开 Dialog 方法。
- 两台真机各通过 21 项专项回归：DesignUiDeviceTest 5 项、CheckGuidanceUiTest 7 项、StaffStatusUiTest 4 项、照片导航／SDK 详情 2 项、激活码编辑 3 项。
- 随后添加扫码弹窗和待机状态行，重新构建安装；两台真机各通过 6 项定向回归：扫码弹窗几何／恢复入口、待机／相机／补光／录入布局，以及激活扫码取消、合法二维码返回、错误二维码拒绝和重建保留临时镜头 4 项。合法二维码为生成的图像夹具，未提交真实激活。
- 本轮补齐日期／单选／数字键盘／状态页后，两台各通过 9 项定向回归：日期草稿与自然日范围、部门长列表与取消、阈值校验与取消、首页状态与断连页、补光与录入布局，以及样本连续浏览、取消删除、单删、清空。单删／清空只操作隔离 SDK 夹具。
- 截图复核后修正日期补充区挤压日历、返回文字换行和样本刷新入口，再各通过 4 项受影响页面回归。最后各通过 2 项日期完整自然日及确认框回归，并等待窗口稳定后补拍截图；这些复测不计为新的独立测试项目。
- 连续加载覆盖员工、记录、录入选择各 124 条，检查唯一 ID、列表末尾和搜索重置。键盘检查包含员工／部门表单与云地址弹窗的固定按钮可见性。
- 累计实机截图每台 56 张，保存在 `implementation-20261014/c106/` 与 `implementation-20261014/nb55/`。未改页面沿用先前截图，改动页面已更新。手部检测、首页状态、断连和图片均使用夹具；相机空选项截图通过空列表夹具生成，不代表现场没有镜头。图片占位及离线提示不能作为真实采集验收。

### 可视证据

- [C106 横屏总览](implementation-20261014/c106-overview.jpg)
- [NB55 竖屏总览](implementation-20261014/nb55-overview.jpg)
- [C106 激活码](implementation-20261014/c106/activation-code-complete.jpg)、[NB55 激活码](implementation-20261014/nb55/activation-code-complete.jpg)
- [C106 扫码弹窗](implementation-20261014/c106/activation-scan.jpg)、[NB55 扫码登录](implementation-20261014/nb55/login-scan.jpg)
- [C106 人员选择](implementation-20261014/c106/face-selection.jpg)、[NB55 待机](implementation-20261014/nb55/check-ready.jpg)
- [C106 录入样本](implementation-20261014/c106/face-samples.jpg)、[NB55 录入样本](implementation-20261014/nb55/face-samples.jpg)
- [C106 日期弹窗](implementation-20261014/c106/record-date-filter.jpg)、[NB55 数字键盘](implementation-20261014/nb55/temperature-threshold.jpg)
- [C106 部门长列表](implementation-20261014/c106/department-choice-end.jpg)、[NB55 断连](implementation-20261014/nb55/check-disconnected.jpg)
- [C106 回归日志](implementation-20261014/c106-tests.txt)、[NB55 回归日志](implementation-20261014/nb55-tests.txt)
- [C106 扫码与待机回归日志](implementation-20261014/c106-scan-tests.txt)、[NB55 扫码与待机回归日志](implementation-20261014/nb55-scan-tests.txt)
- [C106 弹窗与样本 9 项回归](implementation-20261014/c106-dialogs-samples-tests.txt)、[NB55 弹窗与样本 9 项回归](implementation-20261014/nb55-dialogs-samples-tests.txt)
- [C106 4 项复测](implementation-20261014/c106-polish-tests.txt)、[NB55 4 项复测](implementation-20261014/nb55-polish-tests.txt)
- [C106 最后 2 项复测](implementation-20261014/c106-evidence-tests.txt)、[NB55 最后 2 项复测](implementation-20261014/nb55-evidence-tests.txt)、[Lint 日志](implementation-20261014/lint.txt)

未提交、推送或发布代码。调试 APK 保留安装在两台指定设备上。
