package cc.huoyan.opensource.morningcheck;

import androidx.lifecycle.ViewModel;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;

/** 页面重建只恢复选模阶段，不重新查询或提交激活。 */
public final class ModelSelectionViewModel extends ViewModel {
    private ModelSelectionSession selection;

    ModelSelectionSession session(MorningSdkSession sdk) {
        if (selection == null) {
            selection = new ModelSelectionSession(new ModelSelectionSession.Data() {
                @Override
                public long generation() {
                    return sdk.generation();
                }

                @Override
                public void query(String activationCode, boolean switching, HuoyanClient.Callback callback) {
                    if (switching) sdk.client().queryDeviceModels(callback);
                    else sdk.client().queryLicenseModels(activationCode, callback);
                }

                @Override
                public void certificate(HuoyanClient.Callback callback) {
                    sdk.client().getCertInfo(callback);
                }

                @Override
                public boolean activate(String code, String modelCode, HuoyanClient.Callback callback) {
                    return sdk.activate(code, modelCode, callback);
                }
            });
        }
        return selection;
    }

    @Override
    protected void onCleared() {
        if (selection != null) selection.destroy();
    }
}
