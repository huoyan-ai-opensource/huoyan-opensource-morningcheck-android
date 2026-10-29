package cc.huoyan.opensource.morningcheck;

import org.json.JSONObject;

/**
 * 结果页与记录详情共用采集照片查看入口。
 */
public abstract class RecordSupportActivity extends BaseMorningActivity {
    protected void showPhoto(JSONObject record, int index) {
        if (destroyed || !foreground || navigating || isFinishing() || index < 0
                || index >= cc.huoyan.opensource.morningcheck.data.RecordPhotos.read(record).size())
            return;
        navigating = true;
        startActivity(new android.content.Intent(this, RecordPhotoActivity.class)
                .putExtra(RecordDetailActivity.EXTRA_RECORD_ID, record.optString("id"))
                .putExtra(RecordPhotoActivity.EXTRA_PHOTO_INDEX, index));
    }
}
