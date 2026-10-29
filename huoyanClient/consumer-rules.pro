# 跨进程 Parcelable 的 CREATOR 由 Android 框架读取，字段顺序由壳契约固定。
-keepclassmembers class cc.huoyan.morningcheck.app.aidl.** implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
