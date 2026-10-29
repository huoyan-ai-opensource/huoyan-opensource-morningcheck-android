package cc.huoyan.opensource.morningcheck;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import cc.huoyan.opensource.morningcheck.data.RecordPhotos;

/**
 * 历史记录详情只读取采集快照，不调度现场晨检或云端操作。
 */
public final class RecordDetailActivity extends RecordSupportActivity {
    public static final String EXTRA_RECORD_ID = "record_id";
    private final Runnable recordsChanged = () -> reload(false, null);
    private LoadingDialog readingDialog;
    private String recordId, readError = "", photoSignature = "";
    private int readVersion, photoGeneration;
    private boolean reading;
    private Photos photos;
    private Parcelable photoScrollState;

    @Override
    protected String pageKey() {
        return "record_detail";
    }

    @Override
    protected int activityLayout() {
        return R.layout.activity_record_design;
    }

    @Override
    protected void setup(Bundle state) {
        recordId = getIntent().getStringExtra(EXTRA_RECORD_ID);
        if (state != null) photoScrollState = state.getParcelable("record_detail.photos");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ensureAppFontCurrent()) return;
        application.addRecordChangeListener(recordsChanged);
    }

    @Override
    protected void onStop() {
        application.removeRecordChangeListener(recordsChanged);
        readVersion++;
        releasePhotos();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        releasePhotos();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        savePhotoPosition();
        state.putParcelable("record_detail.photos", photoScrollState);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void beforeRender() {
        releasePhotos();
    }

    @Override
    protected void bindPage() {
        findViewById(R.id.header_home).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.header_back_label)).setText(getString(R.string.i18n_b_back_records));
        findViewById(R.id.header_home).setContentDescription(getString(R.string.i18n_b_back_record_list));
        content.findViewById(R.id.record_detail_empty).setOnClickListener(v -> {
            if (!reading) reload(false, null);
        });
        content.findViewById(R.id.record_detail_summary).setOnLongClickListener(v -> {
            if (currentRecord == null) return false;
            ImmersiveUi.show(new ProjectDialog.Builder(this).setTitle(getString(R.string.i18n_b_record_extra))
                    .setMessage(getString(R.string.i18n_b_health_prefix) + value(currentRecord, "health_expiry", getString(R.string.i18n_b_not_registered))
                            + getString(R.string.i18n_b_temperature_source_prefix)
                            + UiMessages.text(this, value(currentRecord, "temperature_source", getString(R.string.i18n_b_not_recorded)))
                            + getString(R.string.i18n_b_place_prefix) + value(currentRecord, "capture_shop_name", getString(R.string.i18n_b_not_recorded)))
                    .setPositiveButton(getString(R.string.i18n_b_close), null).create());
            return true;
        });
        bindRecord();
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> reload(recover, done));
            return;
        }
        if (destroyed || !foreground) return;
        if (readingDialog == null) readingDialog = new LoadingDialog(this);
        readingDialog.show(getString(R.string.i18n_b_reading_record));
        int version = ++readVersion;
        reading = true;
        readError = "";
        bindRecord();
        runIo(() -> {
            try {
                JSONObject record = recordId == null || recordId.trim().isEmpty() ? null : store.record(recordId);
                main.post(() -> {
                    if (destroyed || !foreground || version != readVersion) return;
                    readingDialog.dismiss();
                    currentRecord = record;
                    reading = false;
                    loaded = true;
                    if (done != null) done.run();
                    else onDataLoaded();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || !foreground || version != readVersion) return;
                    readingDialog.dismiss();
                    reading = false;
                    loaded = true;
                    readError = failure(error);
                    notice(getString(R.string.i18n_b_read_record_failed) + readError);
                    bindRecord();
                });
            }
        });
    }

    @Override
    protected void onDataLoaded() {
        bindRecord();
    }

    private void bindRecord() {
        if (destroyed || content == null) return;
        boolean available = currentRecord != null && readError.isEmpty();
        for (int id : new int[]{R.id.record_detail_summary, R.id.record_detail_tabs})
            content.findViewById(id).setVisibility(available ? View.VISIBLE : View.GONE);
        TextView empty = content.findViewById(R.id.record_detail_empty);
        content.findViewById(R.id.record_photo_hint).setVisibility(available ? View.VISIBLE : View.GONE);
        empty.setVisibility(!available && loaded && !reading ? View.VISIBLE : View.GONE);
        empty.setText(getString(R.string.i18n_b_empty_retry));
        if (!available) {
            for (int id : new int[]{R.id.record_detail_fields, R.id.record_detail_photos})
                content.findViewById(id).setVisibility(View.GONE);
            return;
        }
        JSONObject record = currentRecord;
        bindSummaryValue(R.id.record_detail_name, record, "staff_name", getString(R.string.i18n_b_name_missing), R.color.mc_ink);
        bindSummaryValue(R.id.record_detail_staff_no, record, "staff_no", getString(R.string.i18n_b_not_recorded), R.color.mc_ink);
        ((TextView) content.findViewById(R.id.record_detail_name)).setText(
                value(record, "staff_name", getString(R.string.i18n_b_name_missing)) + " · " + value(record, "staff_no", getString(R.string.i18n_b_not_recorded)));
        ((View) content.findViewById(R.id.record_detail_staff_no).getParent()).setVisibility(View.GONE);
        ((View) content.findViewById(R.id.record_detail_shop).getParent()).setVisibility(View.GONE);
        bindSummaryValue(R.id.record_detail_department, record, "department", getString(R.string.i18n_b_not_recorded), R.color.mc_blue);
        bindSummaryValue(R.id.record_detail_shop, record, "capture_shop_name", getString(R.string.i18n_b_not_recorded), R.color.mc_ink);
        TextView date = content.findViewById(R.id.record_detail_date);
        long createdAt = record.optLong("created_at");
        date.setText(timestamp(createdAt));
        date.setTextColor(ContextCompat.getColor(this, createdAt > 0 ? R.color.mc_ink : R.color.mc_muted));
        TextView outcome = content.findViewById(R.id.record_detail_outcome);
        int status = record.optInt("status", -1);
        outcome.setText(status == 1 ? getString(R.string.i18n_b_normal) : status == 0 ? getString(R.string.i18n_b_abnormal) : getString(R.string.i18n_b_not_recorded));
        outcome.setTextColor(ContextCompat.getColor(this, status == 1 ? R.color.mc_green
                : status == 0 ? R.color.mc_orange : R.color.mc_muted));
        outcome.setBackgroundResource(status == 1 ? R.drawable.mc_badge_success
                : status == 0 ? R.drawable.mc_badge_warning : R.drawable.mc_field);
        ListView fields = content.findViewById(R.id.record_detail_fields);
        if (fields.getAdapter() instanceof Fields)
            ((Fields) fields.getAdapter()).update(checkFields(record));
        else fields.setAdapter(new Fields(checkFields(record)));
        content.findViewById(R.id.record_detail_fields).setVisibility(View.VISIBLE);
        content.findViewById(R.id.record_detail_photos).setVisibility(View.VISIBLE);
        bindPhotos(record);
    }

    private List<Field> checkFields(JSONObject record) {
        List<Field> fields = new ArrayList<>();
        String temperature = value(record, "temperature", "未测");
        fields.add(new Field(getString(R.string.i18n_b_temperature), UiMessages.text(this, temperature)
                + ("未测".equals(temperature) ? "" : " °C"), flagColor(record, "abnormal_temperature")));
        boolean headRecorded = record.has("head_enabled") && !record.isNull("head_enabled");
        boolean headEnabled = headRecorded && record.optBoolean("head_enabled");
        fields.add(new Field(getString(R.string.i18n_b_head_check), !headRecorded ? flag(record, "abnormal_face")
                : !headEnabled ? getString(R.string.i18n_b_not_enabled) : flag(record, "abnormal_face"),
                headRecorded && !headEnabled ? R.color.mc_muted : flagColor(record, "abnormal_face")));
        fields.add(new Field(getString(R.string.i18n_b_hand_check), flag(record, "abnormal_hand"), flagColor(record, "abnormal_hand")));
        JSONArray findings = record.optJSONArray("findings");
        if (findings == null || findings.length() == 0) {
            boolean normal = record.optInt("status", -1) == 1;
            fields.add(new Field(getString(R.string.i18n_b_findings), normal ? getString(R.string.i18n_b_no_findings) : getString(R.string.i18n_b_findings_missing), normal ? R.color.mc_green : R.color.mc_muted));
        } else for (int i = 0; i < findings.length(); i++)
            fields.add(new Field(getString(R.string.i18n_b_finding_prefix) + (i + 1), UiMessages.text(this, findings.optString(i)), R.color.mc_orange));
        return fields;
    }

    private void bindPhotos(JSONObject record) {
        List<RecordPhotos.Photo> values = RecordPhotos.read(record);
        content.findViewById(R.id.record_photo_hint).setVisibility(values.isEmpty() ? View.GONE : View.VISIBLE);
        StringBuilder signature = new StringBuilder(record.optString("id"));
        ((TextView) content.findViewById(R.id.record_photo_count)).setText(getString(R.string.i18n_b_photo_count, values.size()));
        for (RecordPhotos.Photo photo : values)
            signature.append('|').append(photo.file).append('|').append(photo.label);
        if (!foreground || signature.toString().equals(photoSignature)) return;
        releasePhotos();
        photoSignature = signature.toString();
        RecyclerView list = content.findViewById(R.id.record_detail_photos);
        if (list.getLayoutManager() == null)
            list.setLayoutManager(new GridLayoutManager(this, 2));
        if (list.getItemDecorationCount() == 0) {
            int gap = getResources().getDimensionPixelSize(R.dimen.dp_8);
            list.addItemDecoration(new RecyclerView.ItemDecoration() {
                @Override
                public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                    int position = parent.getChildAdapterPosition(view);
                    if (position == RecyclerView.NO_POSITION) return;
                    outRect.set(position % 2 == 0 ? 0 : gap - gap / 2, 0,
                            position % 2 == 0 ? gap / 2 : 0, gap);
                }
            });
        }
        photos = new Photos(values, photoGeneration, content, list);
        list.setAdapter(photos);
        if (photoScrollState != null) list.getLayoutManager().onRestoreInstanceState(photoScrollState);
    }

    private void releasePhotos() {
        photoGeneration++;
        photoSignature = "";
        if (photos != null) {
            savePhotoPosition();
            photos.list.setAdapter(null);
            photos.release();
            photos = null;
        }
    }

    private void savePhotoPosition() {
        if (photos != null && photos.list.getLayoutManager() != null)
            photoScrollState = photos.list.getLayoutManager().onSaveInstanceState();
    }

    private void bindSummaryValue(int id, JSONObject record, String key, String fallback, int recordedColor) {
        TextView field = content.findViewById(id);
        String recorded = value(record, key, "");
        field.setText(recorded.isEmpty() ? fallback : recorded);
        field.setTextColor(ContextCompat.getColor(this, recorded.isEmpty() ? R.color.mc_muted : recordedColor));
    }

    private String timestamp(long time) {
        return time <= 0 ? getString(R.string.i18n_b_time_missing) : new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(time));
    }

    private static String value(JSONObject record, String key, String fallback) {
        String value = record.isNull(key) ? "" : record.optString(key).trim();
        return value.isEmpty() ? fallback : value;
    }

    private String flag(JSONObject record, String key) {
        int flag = record.optInt(key, -1);
        return flag == 0 ? getString(R.string.i18n_b_normal) : flag == 1 ? getString(R.string.i18n_b_abnormal) : getString(R.string.i18n_b_result_missing);
    }

    // 状态色只取采集时保存的标记；缺标记不能推断成正常，也不按当前配置重算历史结果。
    private static int flagColor(JSONObject record, String key) {
        int flag = record.optInt(key, -1);
        return flag == 0 ? R.color.mc_green : flag == 1 ? R.color.mc_orange : R.color.mc_muted;
    }

    private static final class Field {
        final String label, value;
        final int color;

        Field(String label, String value) {
            this(label, value, R.color.mc_ink);
        }

        Field(String label, String value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    private final class Fields extends BaseAdapter {
        private List<Field> values;

        Fields(List<Field> fields) {
            values = fields;
        }

        void update(List<Field> fields) {
            values = fields;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return values.size();
        }

        @Override
        public Field getItem(int position) {
            return values.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View row = convert == null ? getLayoutInflater().inflate(R.layout.item_record_design_field, parent, false) : convert;
            Field field = getItem(position);
            ((TextView) row.findViewById(R.id.record_detail_field_label)).setText(field.label);
            TextView value = row.findViewById(R.id.record_detail_field_value);
            value.setText(field.value);
            value.setTextColor(ContextCompat.getColor(RecordDetailActivity.this, field.color));
            return row;
        }
    }

    private final class Photos extends RecyclerView.Adapter<PhotoHolder> {
        final List<RecordPhotos.Photo> values;
        final int generation;
        final View host;
        final RecyclerView list;
        final List<PhotoHolder> holders = new ArrayList<>();

        Photos(List<RecordPhotos.Photo> values, int generation, View host, RecyclerView list) {
            this.values = values;
            this.generation = generation;
            this.host = host;
            this.list = list;
        }

        @Override
        public int getItemCount() {
            return values.size();
        }

        @Override
        public PhotoHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View row = getLayoutInflater().inflate(R.layout.item_record_photo, parent, false);
            PhotoHolder holder = new PhotoHolder(row);
            holders.add(holder);
            return holder;
        }

        @Override
        public void onBindViewHolder(PhotoHolder holder, int position) {
            RecordPhotos.Photo photo = values.get(position);
            if (holder.photo == photo) return;
            holder.clear();
            holder.photo = photo;
            holder.label.setText(UiMessages.text(RecordDetailActivity.this, photo.label));
            holder.image.setContentDescription(UiMessages.text(RecordDetailActivity.this, photo.label));
            holder.empty.setVisibility(View.VISIBLE);
            holder.empty.setText(photo.file.isEmpty() ? R.string.record_detail_photo_not_saved : R.string.record_detail_photo_loading);
            final PhotoHolder expected = holder;
            final int version = holder.version;
            if (!photo.file.isEmpty()) runIo(() -> {
                Bitmap decoded = null;
                try {
                    String path = store.photo(photo.file).getAbsolutePath();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, options);
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        options.inSampleSize = 1;
                        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 480)
                            options.inSampleSize *= 2;
                        options.inJustDecodeBounds = false;
                        decoded = BitmapFactory.decodeFile(path, options);
                    }
                } catch (Exception ignored) { /* 缺失照片保留占位，不能替换成其它记录的图。 */ }
                final Bitmap result = decoded;
                main.post(() -> {
                    if (destroyed || !foreground || generation != photoGeneration || content != host || expected.version != version) {
                        if (result != null) result.recycle();
                        return;
                    }
                    expected.bitmap = result;
                    // setImageBitmap(null) 仍会留下空 BitmapDrawable，读取失败需明确清空图片。
                    if (result == null) expected.image.setImageDrawable(null);
                    else expected.image.setImageBitmap(result);
                    expected.empty.setVisibility(result == null ? View.VISIBLE : View.GONE);
                    expected.empty.setText(R.string.record_detail_photo_unreadable);
                    View.OnClickListener openPhoto = v -> {
                        int currentPosition = expected.getBindingAdapterPosition();
                        if (currentPosition != RecyclerView.NO_POSITION) showPhoto(currentRecord, currentPosition);
                    };
                    expected.image.setOnClickListener(result == null ? null : openPhoto);
                    expected.itemView.setOnClickListener(result == null ? null : openPhoto);
                });
            });
        }

        @Override
        public void onViewRecycled(PhotoHolder holder) {
            holder.clear();
            super.onViewRecycled(holder);
        }

        void release() {
            for (PhotoHolder holder : holders) holder.clear();
            holders.clear();
        }
    }

    private static final class PhotoHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView empty, label;
        RecordPhotos.Photo photo;
        int version;
        Bitmap bitmap;

        PhotoHolder(View row) {
            super(row);
            image = row.findViewById(R.id.record_photo_thumbnail);
            empty = row.findViewById(R.id.record_photo_empty);
            label = row.findViewById(R.id.record_photo_label);
        }

        void clear() {
            version++;
            photo = null;
            image.setImageDrawable(null);
            image.setOnClickListener(null);
            itemView.setOnClickListener(null);
            if (bitmap != null) bitmap.recycle();
            bitmap = null;
        }
    }
}
