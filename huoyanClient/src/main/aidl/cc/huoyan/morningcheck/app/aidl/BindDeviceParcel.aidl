// 设备绑定请求载体（Issue #73）：accountId/shopId 必填、shopName/shopNo 可选
//（Issue #107：shopName 缺省/空白经请求 DTO 构造期以 shopId 回填）。
package cc.huoyan.morningcheck.app.aidl;

parcelable BindDeviceParcel;
