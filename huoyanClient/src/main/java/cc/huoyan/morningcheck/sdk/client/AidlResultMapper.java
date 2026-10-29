package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cc.huoyan.morningcheck.app.aidl.BaseResultParcel;
import cc.huoyan.morningcheck.app.aidl.LicenseModelParcel;
import cc.huoyan.morningcheck.app.aidl.LicenseModelsParcel;
import cc.huoyan.morningcheck.app.aidl.BindDeviceParcel;
import cc.huoyan.morningcheck.app.aidl.BindingResultParcel;
import cc.huoyan.morningcheck.app.aidl.CapabilityParcel;
import cc.huoyan.morningcheck.app.aidl.CertInfoParcel;
import cc.huoyan.morningcheck.app.aidl.ClassTableParcel;
import cc.huoyan.morningcheck.app.aidl.CloudStatusParcel;
import cc.huoyan.morningcheck.app.aidl.DetectionListParcel;
import cc.huoyan.morningcheck.app.aidl.DownloadProgressParcel;
import cc.huoyan.morningcheck.app.aidl.FaceListParcel;
import cc.huoyan.morningcheck.app.aidl.FeedbackGoodsParcel;
import cc.huoyan.morningcheck.app.aidl.FeedbackParcel;
import cc.huoyan.morningcheck.app.aidl.IdentityResultParcel;
import cc.huoyan.morningcheck.app.aidl.McFaceParcel;
import cc.huoyan.morningcheck.app.aidl.PhotoUploadResultParcel;
import cc.huoyan.morningcheck.app.aidl.RegisterResultParcel;
import cc.huoyan.morningcheck.app.aidl.StatusSnapshotParcel;
import cc.huoyan.morningcheck.app.aidl.UserCountParcel;
import cc.huoyan.morningcheck.app.aidl.UserDetailParcel;
import cc.huoyan.morningcheck.app.aidl.UserImageParcel;

/**
 * 将 Binder 回执转换为与晨检 WS 一致的数据，业务层不感知 Parcelable。
 */
final class AidlResultMapper {
    private AidlResultMapper() {
    }

    /**
     * 完整动作结果；code 不混入 data，避免错误回执看起来像成功空结果。
     */
    static final class Reply {
        final int code;
        final String message;
        final JSONObject data;

        Reply(int code, String message, JSONObject data) {
            this.code = code;
            this.message = message == null ? "" : message;
            this.data = data;
        }
    }

    /**
     * 只映射真实字段，不补造授权状态或识别命中结果。
     */
    static Reply map(Object value) throws JSONException {
        JSONObject data = new JSONObject();
        if (value == null)
            return new Reply(HuoyanClient.ERROR_RESULT_UNKNOWN, "SDK 未返回回执，结果未知", data);
        int code;
        String reason;
        if (value instanceof BaseResultParcel) {
            BaseResultParcel p = (BaseResultParcel) value;
            code = p.getCode();
            reason = p.getReason();
        } else if (value instanceof StatusSnapshotParcel) {
            StatusSnapshotParcel p = (StatusSnapshotParcel) value;
            code = p.getCode();
            reason = p.getReason();
            put(data, "device_id", p.getDeviceId());
            put(data, "certificate_valid_until", p.getCertificateValidUntil());
            put(data, "trust_level", p.getTrustLevel());
            put(data, "model_version", p.getModelVersion());
            put(data, "key_lost", p.getKeyLost());
            put(data, "backend_connected", p.getBackendConnected());
            put(data, "sdk_state", p.getSdkState());
            put(data, "model_ready", p.getModelReady());
            put(data, "busy", p.getBusy());
            put(data, "busy_operation", p.getBusyOperation());
            put(data, "state_updated_at", p.getStateUpdatedAt());
            put(data, "certificate_valid", p.getCertificateValid());
            put(data, "recent_error", p.getRecentError());
        } else if (value instanceof CapabilityParcel) {
            CapabilityParcel p = (CapabilityParcel) value;
            code = p.getCode();
            reason = p.getReason();
            data.put("api_version", p.getApiVersion()).put("shared_memory_image", p.isSharedMemoryImage())
                    .put("check", p.isCheck()).put("identify", p.isIdentify())
                    .put("register", p.isRegister()).put("forget", p.isForget()).put("user_query", p.isUserQuery())
                    .put("sdk_version", p.getSdkVersionName()).put("sdk_version_code", p.getSdkVersionCode())
                    .put("detect_face", p.isDetectFace());
        } else if (value instanceof CertInfoParcel) {
            CertInfoParcel p = (CertInfoParcel) value;
            code = p.getCode();
            reason = p.getReason();
            put(data, "model_code", p.getModelCode());
            put(data, "model_channel", p.getModelChannel());
            put(data, "license_type", p.getLicenseType());
            data.put("activated_at", p.getActivatedAt()).put("issued_at", p.getIssuedAt())
                    .put("renewed_at", p.getRenewedAt()).put("has_renewed_at", p.hasRenewedAt())
                    .put("next_renew_at", p.getNextRenewAt()).put("has_next_renew_at", p.hasNextRenewAt())
                    .put("expires_at", p.getExpiresAt()).put("sdk_version_code", p.getSdkVersionCode());
            put(data, "sdk_version", p.getSdkVersionName());
            put(data, "activation_serial", p.getActivationSerial());
        } else if (value instanceof LicenseModelsParcel) {
            LicenseModelsParcel p = (LicenseModelsParcel) value;
            code = p.getCode();
            reason = p.getReason();
            JSONArray models = new JSONArray();
            for (LicenseModelParcel model : p.getModels()) {
                JSONObject item = new JSONObject();
                put(item, "model_code", model.getModelCode());
                put(item, "model_name", model.getModelName());
                put(item, "chip", model.getChip());
                item.put("current", model.isCurrent());
                models.put(item);
            }
            data.put("models", models);
        } else if (value instanceof DownloadProgressParcel) {
            DownloadProgressParcel p = (DownloadProgressParcel) value;
            code = p.getCode();
            reason = p.getReason();
            data.put("downloaded", p.getDownloaded()).put("total", p.getTotal());
        } else if (value instanceof IdentityResultParcel) {
            IdentityResultParcel p = (IdentityResultParcel) value;
            code = p.code;
            reason = p.reason;
            if (p.identity != null) data.put("person_id", p.identity.personId)
                    .put("similarity", p.identity.similarity).put("matched", p.identity.matched);
        } else if (value instanceof DetectionListParcel) {
            DetectionListParcel p = (DetectionListParcel) value;
            code = p.code;
            reason = p.reason;
            JSONArray boxes = new JSONArray();
            for (int i = 0; i < p.count; i++)
                boxes.put(new JSONObject().put("x", p.boxX[i])
                        .put("y", p.boxY[i]).put("w", p.boxW[i]).put("h", p.boxH[i])
                        .put("class_id", p.classId[i]).put("score", p.score[i]));
            data.put("boxes", boxes);
        } else if (value instanceof FaceListParcel) {
            FaceListParcel p = (FaceListParcel) value;
            code = p.code;
            reason = p.reason;
            JSONArray faces = new JSONArray();
            for (McFaceParcel face : p.toItems()) {
                JSONArray landmarks = new JSONArray();
                for (float point : face.getLandmarks()) landmarks.put(point);
                faces.put(new JSONObject().put("x", face.x).put("y", face.y)
                        .put("w", face.w).put("h", face.h).put("score", face.score)
                        .put("landmarks", landmarks));
            }
            data.put("faces", faces);
        } else if (value instanceof RegisterResultParcel) {
            RegisterResultParcel p = (RegisterResultParcel) value;
            code = p.code;
            reason = p.reason;
            data.put("uid", p.uid).put("image_url", p.imageUrl);
        } else if (value instanceof UserCountParcel) {
            UserCountParcel p = (UserCountParcel) value;
            code = p.code;
            reason = p.reason;
            data.put("count", p.count);
        } else if (value instanceof UserDetailParcel) {
            UserDetailParcel p = (UserDetailParcel) value;
            code = p.getCode();
            reason = p.getReason();
            JSONArray items = new JSONArray(), uids = new JSONArray(), samples = new JSONArray();
            for (UserImageParcel item : p.getItems()) {
                items.put(new JSONObject().put("uid", item.getUid())
                        .put("image_available", item.isImageAvailable())
                        .put("image_url", item.getImageUrl()).put("mime_type", item.getMimeType()));
                // 旧字段从同一对象派生，只供尚未升级的业务客户端读取。
                uids.put(item.getUid());
                samples.put(new JSONObject().put("uid", item.getUid()).put("image_url", item.getImageUrl()));
            }
            data.put("items", items).put("uids", uids).put("samples", samples);
        } else if (value instanceof PhotoUploadResultParcel) {
            PhotoUploadResultParcel p = (PhotoUploadResultParcel) value;
            code = p.getCode();
            reason = p.getReason();
            put(data, "photo_file_url", p.getPhotoFileUrl());
        } else if (value instanceof CloudStatusParcel) {
            CloudStatusParcel p = (CloudStatusParcel) value;
            code = p.getCode();
            reason = p.getReason();
            data.put("enabled", p.isEnabled());
        } else if (value instanceof BindingResultParcel) {
            BindingResultParcel p = (BindingResultParcel) value;
            code = p.getCode();
            reason = p.getReason();
            if (p.getRequest() != null) {
                BindDeviceParcel request = p.getRequest();
                JSONObject echo = new JSONObject().put("account_id", request.getAccountId());
                put(echo, "shop_id", request.getShopId());
                put(echo, "shop_name", request.getShopName());
                put(echo, "shop_no", request.getShopNo());
                data.put("request", echo);
            }
            JSONObject response = new JSONObject();
            put(response, "account_id", p.getAccountId());
            put(response, "account_name", p.getAccountName());
            put(response, "shop_id", p.getShopId());
            put(response, "shop_name", p.getShopName());
            put(response, "shop_no", p.getShopNo());
            data.put("response", response);
        } else if (value instanceof ClassTableParcel) {
            ClassTableParcel p = (ClassTableParcel) value;
            code = p.code;
            reason = null;
            JSONArray head = new JSONArray(), hand = new JSONArray();
            for (int i = 0; i < p.count; i++) {
                JSONObject item = new JSONObject().put("classId", p.classId[i])
                        .put("name", p.name[i]).put("nameEn", p.nameEn[i]);
                if ("head".equals(p.modelType[i])) head.put(item);
                else if ("hand".equals(p.modelType[i])) hand.put(item);
            }
            data.put("head", head).put("hand", hand);
        } else {
            throw new IllegalArgumentException("不支持的 Binder 回执：" + value.getClass().getSimpleName());
        }
        return new Reply(code, reason, code == 0 ? data : new JSONObject());
    }

    /**
     * JSON null 与 false 的语义不同，状态未知时保留 null。
     */
    private static void put(JSONObject target, String key, Object value) throws JSONException {
        target.put(key, value == null ? JSONObject.NULL : value);
    }

    /**
     * 使用与 WS 相同的输入字段构造反馈载体，未提供的可选数值保留为 null。
     */
    static FeedbackParcel feedback(JSONObject params) throws JSONException {
        JSONArray values = RequestParameters.feedbackGoods(params);
        List<FeedbackGoodsParcel> goods = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.getJSONObject(i);
            goods.add(new FeedbackGoodsParcel(optionalString(item, "goods_id"),
                    optionalString(item, "goods_name"), optionalString(item, "goods_plu"),
                    RequestParameters.optionalInteger(item, "selected"),
                    RequestParameters.optionalNumber(item, "distance"), optionalString(item, "source"),
                    RequestParameters.optionalNumber(item, "width"), RequestParameters.optionalNumber(item, "height"),
                    RequestParameters.optionalNumber(item, "x"), RequestParameters.optionalNumber(item, "y")));
        }
        Integer type = RequestParameters.optionalInteger(params, "feedback_type");
        return new FeedbackParcel(RequestParameters.requiredString(params, "request_id"), RequestParameters.requiredString(params, "photo_file_url"),
                type == null ? 0 : type, goods);
    }

    static String optionalString(JSONObject params, String key) throws JSONException {
        return RequestParameters.optionalString(params, key);
    }
}
