package cc.huoyan.opensource.morningcheck;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

import cc.huoyan.opensource.morningcheck.data.RecordPhotos;

/**
 * 当前记录的照片一次采样到内存，翻页只滚动视图；离页时统一回收。
 */
public final class RecordPhotoActivity extends BaseMorningActivity {
    public static final String EXTRA_PHOTO_INDEX = "photo_index";
    private final Set<Integer> reportedErrors = new HashSet<>();
    private String recordId;
    private LoadingDialog readingDialog;
    private int selected;
    private volatile int readVersion;
    private PhotoBatch batch;
    private RecyclerView pager;
    private PagerSnapHelper snapHelper;
    private PhotoViewerImageView fullscreenImage;
    private Dialog fullscreenDialog;
    private boolean fullscreenRequested, reading;

    @Override
    protected String pageKey() {
        return "record_photo";
    }

    @Override
    protected int activityLayout() {
        return R.layout.activity_record_photo;
    }

    @Override
    protected void setup(Bundle state) {
        recordId = getIntent().getStringExtra(RecordDetailActivity.EXTRA_RECORD_ID);
        selected = Math.max(0, state == null
                ? getIntent().getIntExtra(EXTRA_PHOTO_INDEX, 0) : state.getInt("selected", 0));
        fullscreenRequested = state != null && state.getBoolean("fullscreen", false);
    }

    @Override
    protected void beforeRender() {
        dismissFullscreen(true);
        detachPager();
    }

    @Override
    protected void bindPage() {
        findViewById(R.id.header_home).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.header_back_label)).setText(getString(R.string.i18n_b_back_details));
        ((TextView) findViewById(R.id.header_page_title)).setText(getString(R.string.i18n_b_check_photos));
        pager = content.findViewById(R.id.photo_pager);
        pager.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        pager.setItemAnimator(null);
        snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(pager);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView list, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE || batch == null) return;
                View snapped = snapHelper.findSnapView(list.getLayoutManager());
                if (snapped == null) return;
                int position = list.getChildAdapterPosition(snapped);
                if (position == RecyclerView.NO_POSITION) return;
                selected = position;
                updatePhoto();
            }
        });
        content.findViewById(R.id.photo_previous).setOnClickListener(v -> changePhoto(-1));
        content.findViewById(R.id.photo_next).setOnClickListener(v -> changePhoto(1));
        bindBatch();
        if (foreground) reload(false, null);
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        if (destroyed || !foreground) return;
        if (batch != null) {
            bindBatch();
            if (done != null) done.run();
            return;
        }
        if (reading) return;
        int request = ++readVersion;
        reading = true;
        if (readingDialog == null) readingDialog = new LoadingDialog(this);
        readingDialog.show(getString(R.string.i18n_b_reading_photos));
        int maxPixels = Math.min(2048, Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels));
        runIo(() -> {
            try {
                PhotoBatch result = readBatch(maxPixels, request);
                main.post(() -> {
                    if (destroyed || !foreground || request != readVersion) {
                        result.recycle();
                        return;
                    }
                    reading = false;
                    readingDialog.dismiss();
                    batch = result;
                    selected = Math.min(selected, batch.photos.size() - 1);
                    bindBatch();
                    if (done != null) done.run();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (destroyed || !foreground || request != readVersion) return;
                    reading = false;
                    readingDialog.dismiss();
                    notice(failure(failure));
                });
            }
        });
    }

    private PhotoBatch readBatch(int maxPixels, int request) throws Exception {
        JSONObject record = store.record(recordId);
        if (record == null) throw new IllegalStateException(getString(R.string.i18n_b_record_removed));
        List<RecordPhotos.Photo> photos = RecordPhotos.read(record);
        if (photos.isEmpty()) throw new IllegalStateException(getString(R.string.i18n_b_no_record_photos));
        long createdAt = record.optLong("created_at");
        String recordInfo = record.optString("staff_name", getString(R.string.i18n_b_name_missing)) + " · " + (createdAt > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(createdAt)) : getString(R.string.i18n_b_time_missing));
        PhotoBatch result = new PhotoBatch(photos, recordInfo);
        // 全部照片共享采样预算，照片较多时也不能按原图大小成批占满堆内存。
        long pixelBudget = Math.min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8) / 4;
        long pixelsPerPhoto = Math.max(1, pixelBudget / photos.size());
        try {
            for (RecordPhotos.Photo photo : photos) {
                // 离页后终止尚未开始的解码，避免旧批次继续占用共享 IO 队列。
                if (request != readVersion) throw new CancellationException();
                Bitmap decoded = null;
                String error = "";
                try {
                    if (photo.file.isEmpty()) throw new IllegalStateException(getString(R.string.i18n_b_photo_not_saved_prefix)
                            + UiMessages.text(this, photo.label));
                    String path = store.photo(photo.file).getAbsolutePath();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, options);
                    if (options.outWidth <= 0 || options.outHeight <= 0)
                        throw new IllegalStateException(UiMessages.text(this, photo.label) + getString(R.string.i18n_b_photo_missing_suffix));
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxPixels
                            || (long) Math.ceil(options.outWidth / (double) options.inSampleSize)
                            * (long) Math.ceil(options.outHeight / (double) options.inSampleSize) > pixelsPerPhoto)
                        options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    decoded = BitmapFactory.decodeFile(path, options);
                    if (decoded == null) throw new IllegalStateException(UiMessages.text(this, photo.label)
                            + getString(R.string.i18n_b_photo_unreadable_suffix));
                } catch (Exception failure) {
                    error = failure(failure);
                } catch (OutOfMemoryError exhausted) {
                    error = UiMessages.text(this, photo.label) + getString(R.string.i18n_b_photo_memory_suffix);
                }
                result.images.add(decoded);
                result.errors.add(error);
            }
            return result;
        } catch (Throwable failure) {
            result.recycle();
            throw failure;
        }
    }

    private void bindBatch() {
        if (pager == null) return;
        if (batch != null && pager.getAdapter() == null) {
            pager.setAdapter(new PhotoPages());
            ((LinearLayoutManager) pager.getLayoutManager()).scrollToPositionWithOffset(selected, 0);
        }
        updatePhoto();
    }

    private void updatePhoto() {
        if (content == null) return;
        int count = batch == null ? 0 : batch.photos.size();
        String label = count == 0 ? getString(R.string.i18n_b_captured_photos)
                : UiMessages.text(this, batch.photos.get(selected).label);
        ((TextView) content.findViewById(R.id.photo_caption)).setText(label);
        ((TextView) content.findViewById(R.id.photo_record_info)).setText(batch == null ? "" : batch.recordInfo);
        ((TextView) content.findViewById(R.id.photo_position)).setText(
                getString(R.string.record_photo_position, count == 0 ? 0 : selected + 1, count));
        Bitmap bitmap = count == 0 ? null : batch.images.get(selected);
        if (fullscreenRequested && bitmap != null) showFullscreen(bitmap);
        if (fullscreenImage != null) {
            if (bitmap == null) fullscreenImage.setImageDrawable(null);
            else if (!(fullscreenImage.getDrawable() instanceof BitmapDrawable)
                    || ((BitmapDrawable) fullscreenImage.getDrawable()).getBitmap() != bitmap)
                fullscreenImage.setImageBitmap(bitmap);
            fullscreenImage.setContentDescription(getString(R.string.record_photo_fullscreen_description, label));
            ((TextView) fullscreenDialog.findViewById(R.id.photo_fullscreen_position)).setText(
                    getString(R.string.record_photo_position, selected + 1, count));
        }
        if (count > 0 && !batch.errors.get(selected).isEmpty() && reportedErrors.add(selected))
            notice(batch.errors.get(selected));
        content.findViewById(R.id.photo_previous).setEnabled(selected > 0 && count > 0);
        content.findViewById(R.id.photo_next).setEnabled(count > 0 && selected < count - 1);
        for (int id : new int[]{R.id.photo_previous, R.id.photo_next}) {
            View action = content.findViewById(id);
            action.setAlpha(action.isEnabled() ? 1f : .45f);
        }
    }

    private void changePhoto(int step) {
        if (batch == null || pager == null) return;
        int next = selected + step;
        if (next < 0 || next >= batch.photos.size()) return;
        selected = next;
        pager.smoothScrollToPosition(next);
        updatePhoto();
    }

    private void showFullscreen(Bitmap bitmap) {
        if (!foreground || destroyed || isFinishing() || fullscreenDialog != null) return;
        fullscreenDialog = new Dialog(this, R.style.McRecordPhotoFullscreenTheme);
        fullscreenDialog.setContentView(R.layout.dialog_record_photo_fullscreen);
        fullscreenImage = fullscreenDialog.findViewById(R.id.photo_fullscreen_image);
        fullscreenImage.setOnClickListener(v -> dismissFullscreen(false));
        fullscreenImage.setImageBitmap(bitmap);
        Dialog preview = fullscreenDialog;
        PhotoViewerImageView previewImage = fullscreenImage;
        fullscreenDialog.setOnDismissListener(dialog -> {
            previewImage.setImageDrawable(null);
            if (fullscreenDialog != preview) return;
            fullscreenRequested = false;
            fullscreenImage = null;
            fullscreenDialog = null;
        });
        ImmersiveUi.bindInsets(fullscreenDialog.findViewById(R.id.photo_fullscreen_root));
        fullscreenDialog.show();
        fullscreenDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        fullscreenDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ImmersiveUi.enable(fullscreenDialog.getWindow());
    }

    private void dismissFullscreen(boolean retainSelection) {
        if (!retainSelection) fullscreenRequested = false;
        if (fullscreenDialog == null) return;
        fullscreenDialog.setOnDismissListener(null);
        fullscreenImage.setImageDrawable(null);
        fullscreenDialog.dismiss();
        fullscreenImage = null;
        fullscreenDialog = null;
    }

    private void detachPager() {
        if (pager == null) return;
        pager.clearOnScrollListeners();
        snapHelper.attachToRecyclerView(null);
        pager.setAdapter(null);
        pager.getRecycledViewPool().clear();
        pager = null;
    }

    private void releasePhotos() {
        // 全屏和滚动页共用缓存；先移除所有 drawable，再统一回收位图。
        dismissFullscreen(true);
        detachPager();
        if (batch != null) {
            batch.recycle();
            batch = null;
        }
        reportedErrors.clear();
    }

    @Override
    protected void onStart() {
        // onStop 已释放缓存，重新进入时重新装配分页视图。
        if (pager == null && content != null) bindPage();
        super.onStart();
    }

    @Override
    protected void onStop() {
        readVersion++;
        reading = false;
        releasePhotos();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        releasePhotos();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (fullscreenDialog != null) dismissFullscreen(false);
        else finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected", selected);
        state.putBoolean("fullscreen", fullscreenRequested);
        super.onSaveInstanceState(state);
    }

    private final class PhotoPages extends RecyclerView.Adapter<PhotoPage> {
        @Override
        public PhotoPage onCreateViewHolder(ViewGroup parent, int viewType) {
            return new PhotoPage(getLayoutInflater().inflate(R.layout.item_record_photo_page, parent, false));
        }

        @Override
        public void onBindViewHolder(PhotoPage holder, int position) {
            Bitmap bitmap = batch.images.get(position);
            if (bitmap == null) holder.image.setImageDrawable(null);
            else holder.image.setImageBitmap(bitmap);
            holder.image.setContentDescription(UiMessages.text(RecordPhotoActivity.this, batch.photos.get(position).label));
        }

        @Override
        public int getItemCount() {
            return batch.photos.size();
        }

        @Override
        public void onViewRecycled(PhotoPage holder) {
            holder.image.setImageDrawable(null);
        }
    }

    private final class PhotoPage extends RecyclerView.ViewHolder {
        final PhotoViewerImageView image;

        PhotoPage(View root) {
            super(root);
            image = root.findViewById(R.id.photo_image);
            image.setZoomEnabled(false);
            image.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (batch == null || position == RecyclerView.NO_POSITION
                        || pager.getScrollState() != RecyclerView.SCROLL_STATE_IDLE || batch.images.get(position) == null) return;
                selected = position;
                fullscreenRequested = true;
                updatePhoto();
            });
        }
    }

    private static final class PhotoBatch {
        final List<RecordPhotos.Photo> photos;
        final List<Bitmap> images = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final String recordInfo;

        PhotoBatch(List<RecordPhotos.Photo> photos, String recordInfo) {
            this.photos = photos;
            this.recordInfo = recordInfo;
        }

        void recycle() {
            for (Bitmap image : images) if (image != null && !image.isRecycled()) image.recycle();
        }
    }
}
