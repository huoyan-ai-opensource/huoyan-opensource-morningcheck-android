package cc.huoyan.morningcheck.app.aidl;

// 手写对称 Parcelable（禁 StableParcelable/VersionedParcelable/@Parcelize/
// writeArray/readArray/writeTypedList/writeParcelableArray/writeValue；
// 集合一律平行数组 + 条数 + 读出后回溯断言）。实现见同包 FrameParcel.java。
parcelable FrameParcel;
