package cc.huoyan.opensource.morningcheck;

/**
 * 首页各设置入口使用固定路由，每类设置由自己的 Activity 负责。
 */
final class SettingsNavigation {
    private SettingsNavigation() {
    }

    static Class<? extends BaseDeviceSettingsActivity> activityForSection(int section) {
        switch (section) {
            case 1:
                return BusinessCloudSettingsActivity.class;
            case 3:
                return LicenseSettingsActivity.class;
            case 4:
                return CameraSettingsActivity.class;
            case 5:
                return CheckRulesSettingsActivity.class;
            case 6:
                return TemperatureSettingsActivity.class;
            case 7:
                return SystemSettingsActivity.class;
            default:
                return DevicesActivity.class;
        }
    }
}
