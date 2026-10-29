// AIDL 通讯入口（晨检壳，识物契约骨架 + 晨检识别/检测/学习功能面）：
// 跨进程调用同一 SDK 门面。方法名/参数顺序即对外契约，破坏性变更须整体
// 定稿（方法删除使后续方法 Binder 事务码整体平移，旧客户端须同步升级）；
// 错误模型统一 code（0=成功）+ reason。
// 整个 AIDL 通道仅 API 27+ 可用；API 21～26 不装配、不注册、不返回 Binder，使用 WS。
// 晨检帧输入经 FrameParcel（fd/bytes 双通道；跨进程发送端自行 unmap/close
// 原对象，接收端 Stub 在图像入口请求结束时统一 close 接收句柄，含拒绝和异常）。
// native 壳内等待预算：普通 30 秒；init/activate 120 秒；反馈/上传各 60 秒；
// 执行前排队最多 2 秒。40010=尚未执行，可退避重试；40009=结果未知，
// 已开始工作继续安全收尾，写操作不得自动重放。
// 绑定流程不在上述 native/反馈总预算的完整覆盖范围内。
package cc.huoyan.morningcheck.app.aidl;

import cc.huoyan.morningcheck.app.aidl.BaseResultParcel;
import cc.huoyan.morningcheck.app.aidl.ClassTableParcel;
import cc.huoyan.morningcheck.app.aidl.BindDeviceParcel;
import cc.huoyan.morningcheck.app.aidl.BindingResultParcel;
import cc.huoyan.morningcheck.app.aidl.CapabilityParcel;
import cc.huoyan.morningcheck.app.aidl.CertInfoParcel;
import cc.huoyan.morningcheck.app.aidl.CloudStatusParcel;
import cc.huoyan.morningcheck.app.aidl.DetectionListParcel;
import cc.huoyan.morningcheck.app.aidl.FaceListParcel;
import cc.huoyan.morningcheck.app.aidl.DownloadProgressParcel;
import cc.huoyan.morningcheck.app.aidl.FeedbackParcel;
import cc.huoyan.morningcheck.app.aidl.FrameParcel;
import cc.huoyan.morningcheck.app.aidl.IdentityResultParcel;
import cc.huoyan.morningcheck.app.aidl.ImageRequestParcel;
import cc.huoyan.morningcheck.app.aidl.PhotoUploadResultParcel;
import cc.huoyan.morningcheck.app.aidl.RegisterResultParcel;
import cc.huoyan.morningcheck.app.aidl.StatusSnapshotParcel;
import cc.huoyan.morningcheck.app.aidl.UserCountParcel;
import cc.huoyan.morningcheck.app.aidl.UserDetailParcel;

import cc.huoyan.morningcheck.app.aidl.LicenseModelsParcel;

interface ISdkCommService {
    // ---- 版本/能力 ----
    // 接口版本（晨检面定稿为 3：识物 detect/recognize/item 族移除，改晨检
    // identify/checkHead/checkHand/register 族）
    // 能力快照：apiVersion + 晨检识别/检测/学习支持标志
    CapabilityParcel getCapabilities();

    // ---- 状态/授权 ----
    // 状态快照：13 字段（键名与 WS getStatus 完全一致）；退役后 sdk_state=STOPPED
    // 且活动事实（设备/证书/模型/busy）清空
    StatusSnapshotParcel getStatus();
    CertInfoParcel getCertInfo();
    DownloadProgressParcel getDownloadProgress();
    BaseResultParcel activate(String activationCode, String modelCode);
    BaseResultParcel setBaseUrl(String url);

    // ---- 晨检识别/检测（引擎 = libhuoyan-morningcheck.so，经 FrameParcel
    //      帧载体；未匹配/无目标为正常结果非错误） ----
    // 人脸身份比对：IdentityResultParcel（personId/similarity/matched）
    IdentityResultParcel identify(in FrameParcel frame);
    // 头部检查（hat/mask）：DetectionListParcel 归一化框数组
    DetectionListParcel checkHead(in FrameParcel frame, int maxDetections);
    // 手部检查（8 类）：DetectionListParcel 归一化框数组
    DetectionListParcel checkHand(in FrameParcel frame, int maxDetections);

    // ---- 晨检人员底库（学习/遗忘/查询；personId 为业务人员 ID，
    //      register 返回自增 uid 供 forgetByUid 单条遗忘） ----
    RegisterResultParcel register(in FrameParcel frame, long personId);
    BaseResultParcel forget(long personId);
    BaseResultParcel forgetByUid(long uid);
    UserCountParcel userCount();
    UserDetailParcel userDetail(long personId);

    // ---- 业务上报（原识物反馈面保留；setAuthToken 已随识物契约删除） ----
    BaseResultParcel submitFeedback(in FeedbackParcel feedback);

    // ---- 图片上传（两段式：uploadPhoto 独立字节上传能力，复用
    //      ImageRequestParcel 载体） ----
    PhotoUploadResultParcel uploadPhoto(in ImageRequestParcel image);

    // ---- 服务初始化（携带客户端版本；就绪查询经 getStatus） ----
    // appVersion 为宿主客户端 versionName；版本保存后随 SDK 后续心跳上报。
    BaseResultParcel init(String appVersion);

    // ---- 设备绑定（云端 wujing 接口；回执 BindingResultParcel 区分
    //      两组字段） ----
    BindingResultParcel bindDevice(in BindDeviceParcel req);

    // ---- 云端托管开关（AIDL/WS 同名小驼峰 setCloudStatus/getCloudStatus） ----
    CloudStatusParcel setCloudStatus(boolean enabled);
    CloudStatusParcel getCloudStatus();

      // ---- 类别表（追加式；语义真源 bridge/McClass ↔ docs/model.md §2 类别表。
      //      纯追加不改动前 20 方法顺序 ⇒ Binder 事务码不变、apiVersion 保持 3）----
      ClassTableParcel getClassTable();

      // 人脸检测纯追加：保留所有旧事务码；返回框、分数及关键点，maxFaces 必须 >=1。
      FaceListParcel detectFace(in FrameParcel frame, int maxFaces);
      // 按输入激活码查询支持模型，适用于尚未激活的设备。
      LicenseModelsParcel queryLicenseModels(String activationCode);
      // 无参查询当前设备绑定授权的支持模型，不依赖本地激活码。
      LicenseModelsParcel queryDeviceModels();
}
