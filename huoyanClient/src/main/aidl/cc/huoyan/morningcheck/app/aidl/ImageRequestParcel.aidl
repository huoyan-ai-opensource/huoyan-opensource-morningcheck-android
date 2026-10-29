package cc.huoyan.morningcheck.app.aidl;

// 仅 API 27+ 的 AIDL 通道使用。发送端管理原 SharedMemory，接收端 Stub
// 在请求结束时关闭反序列化所得独立句柄；Reader 只解除自己的读取映射。
parcelable ImageRequestParcel;
