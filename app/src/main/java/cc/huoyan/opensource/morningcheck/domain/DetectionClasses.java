package cc.huoyan.opensource.morningcheck.domain;

/**
 * 晨检 2.0 算法类别，对齐 SDK bridge/McClass.java。
 * 头部和手部使用各自的类别表；相同数字只有在对应检测阶段才有意义。
 */
public final class DetectionClasses {
    public static final int HEAD_HAT = 0;
    public static final int HEAD_MASK = 1;

    public static final int HAND_BACK = 0;
    public static final int HAND_INCORRECT = 1;
    public static final int HAND_NAIL = 2;
    public static final int HAND_PALM = 3;
    public static final int HAND_PLASTER = 4;
    public static final int HAND_RING = 5;
    public static final int HAND_WOUND = 6;
    public static final int HAND_GLOVE = 7;

    private DetectionClasses() {
    }
}
