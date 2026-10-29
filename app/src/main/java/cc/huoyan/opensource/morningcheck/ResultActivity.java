package cc.huoyan.opensource.morningcheck;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cc.huoyan.opensource.morningcheck.data.RecordPhotos;
import cc.huoyan.opensource.morningcheck.domain.CheckVoiceText;

/**
 * 独立结果页从数据库按 ID 读取证据，不通过 Intent 传递图像。
 */
public final class ResultActivity extends RecordSupportActivity {
    private boolean paused;
    private boolean nextScheduled;
    private long nextPersonAt;
    private String recordId;
    private MorningVoice speech;
    private boolean resumed;
    private int evidenceGeneration, recordReadVersion;
    private EvidencePhotos evidencePhotos;
    private AlertDialog exitDialog;
    private boolean exitRequested, exitApproved, pausedBeforeExit;
    private final Runnable nextAfterSpeech = this::nextEmployee;
    private final Runnable nextPerson = new Runnable() {
        @Override
        public void run() {
            nextScheduled = false;
            if (!foreground || !resumed || paused || exitRequested || currentRecord == null || navigating || isFinishing()) return;
            long remaining = nextPersonAt - SystemClock.uptimeMillis();
            updateFlowState();
            if (remaining > 0) {
                nextScheduled = true;
                main.postDelayed(this, Math.min(1000, remaining));
                return;
            }
            if (!sdkReady()) {
                speech.clearStatus();
                nextScheduled = true;
                main.postDelayed(this, 2000);
                return;
            }
            // 自动下一位等待本次短结论播完，不能由页面跳转截断最后一句。
            if (speech.awaitingOrSpeaking()) {
                nextScheduled = true;
                main.postDelayed(this, 500);
                return;
            }
            nextEmployee();
        }
    };

    @Override
    protected String pageKey() {
        return "result";
    }

    @Override
    protected int activityLayout() {
        return R.layout.activity_result_design;
    }

    @Override
    protected void setup(Bundle state) {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 结果仍属于连续晨检，返回键不能绕过密码出口。
            }
        });
        recordId = getIntent().getStringExtra("record_id");
        paused = state != null && state.getBoolean("paused");
        speech = new MorningVoice(this, main, () -> rule("voice", true));
        if (state != null && recordId != null && recordId.equals(state.getString("voice_record_id"))
                && state.getBoolean("result_voice_announced"))
            speech.restoreAnnounced("result:" + recordId);
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> reload(recover, done));
            return;
        }
        if (destroyed) return;
        int version = ++recordReadVersion;
        // 结果页只读取本次记录；历史列表的分页与其它记录均不参与结果展示。
        runIo(() -> {
            try {
                JSONObject stored = recordId == null || recordId.trim().isEmpty() ? null : store.record(recordId);
                main.post(() -> {
                    if (destroyed || version != recordReadVersion) return;
                    currentRecord = stored;
                    loaded = true;
                    if (!foreground) return;
                    if (done != null) done.run();
                    else onDataLoaded();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || version != recordReadVersion) return;
                    notice("晨检记录读取失败：" + failure(error));
                });
            }
        });
    }

    @Override
    protected void onDataLoaded() {
        if (exitRequested) return;
        if (currentRecord == null) {
            notice("晨检记录不存在，请从历史记录重新查看");
            render();
            return;
        }
        render();
        announceResult();
        scheduleAutomaticNext();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureAppFontCurrent()) return;
        resumed = true;
        speech.resume();
        announceResult();
        scheduleAutomaticNext();
    }

    @Override
    protected void onPause() {
        resumed = false;
        speech.pause();
        cancelAutomaticNext();
        main.removeCallbacks(nextAfterSpeech);
        super.onPause();
    }

    @Override
    protected void onStop() {
        dismissExitDialog();
        recordReadVersion++;
        releaseEvidence();
        if (speech != null) speech.pause();
        cancelAutomaticNext();
        main.removeCallbacks(nextAfterSpeech);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        dismissExitDialog();
        cancelAutomaticNext();
        main.removeCallbacks(nextAfterSpeech);
        releaseEvidence();
        if (speech != null) speech.close();
        super.onDestroy();
    }

    @Override
    protected void beforeRender() {
        // 横竖屏重排保留验证窗口，取消后再恢复新视图上的语音和下一位预约。
        releaseEvidence();
    }

    private void announceResult() {
        // 按实际交给引擎的记录键去重；初始化前离开时，回来仍须补播未受理的结论。
        if (!foreground || currentRecord == null) return;
        speech.enterStage("result:" + currentRecord.optString("id"), CheckVoiceText.result(
                currentRecord.optInt("status") == 1));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putBoolean("paused", exitRequested ? pausedBeforeExit : paused);
        state.putString("voice_record_id", recordId);
        state.putBoolean("result_voice_announced", recordId != null && speech.hasAnnounced("result:" + recordId));
        super.onSaveInstanceState(state);
    }

    private void nextEmployee() {
        main.removeCallbacks(nextAfterSpeech);
        if (!foreground || !resumed || exitRequested || currentRecord == null || navigating || isFinishing()) return;
        if (!sdkReady()) {
            notice("暂时无法继续晨检，请检查设备连接");
            speech.clearStatus();
            return;
        }
        if (speech.awaitingOrSpeaking()) {
            main.postDelayed(nextAfterSpeech, 200);
            return;
        }
        cancelAutomaticNext();
        navigating = true;
        startActivity(new Intent(this, CheckActivity.class).putExtra("last_person", currentRecord.optLong("staff_id")));
        finish();
    }

    @Override
    protected void beforeNavigate(String target, Runnable action) {
        if (target.equals("check")) nextEmployee();
        // 其它页面只能经右下角的密码出口返回首页。
    }

    @Override
    public void onBackPressed() {
        // 连续晨检结果页不执行通用返回首页导航。
    }

    private void requestExit() {
        if (!foreground || destroyed || isFinishing() || exitRequested) return;
        exitRequested = true;
        pausedBeforeExit = paused;
        paused = true;
        cancelAutomaticNext();
        main.removeCallbacks(nextAfterSpeech);
        speech.pause();
        updateFlowState();
        exitDialog = SecurityPasswordDialog.showVerify(this, () -> {
            exitApproved = true;
            startActivity(new Intent(this, HomeActivity.class)
                    .putExtra(HomeActivity.EXTRA_SKIP_AUTO_CHECK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        }, () -> {
            exitDialog = null;
            exitRequested = false;
            paused = pausedBeforeExit;
            if (!foreground || !resumed || exitApproved || destroyed) return;
            speech.resume();
            if (currentRecord != null) {
                bindPage();
                announceResult();
            }
            updateFlowState();
            scheduleAutomaticNext();
        });
    }

    private void dismissExitDialog() {
        if (exitDialog != null) exitDialog.dismiss();
    }

    @Override
    protected void bindPage() {
        findViewById(R.id.header_home).setVisibility(View.GONE);
        findViewById(R.id.header_home).setOnClickListener(null);
        content.findViewById(R.id.result_exit).setOnClickListener(v -> requestExit());
        content.findViewById(R.id.result_body).setVisibility(currentRecord == null ? View.INVISIBLE : View.VISIBLE);
        // 记录异步读盘完成前隐藏结果内容，避免展示布局中的默认结果文案。
        if (currentRecord == null) {
            content.findViewById(R.id.result_stay).setVisibility(View.GONE);
            content.findViewById(R.id.result_next).setVisibility(View.GONE);
            return;
        }
        content.findViewById(R.id.result_stay).setVisibility(View.VISIBLE);
        content.findViewById(R.id.result_next).setVisibility(View.VISIBLE);
        JSONObject record = currentRecord;
        boolean ok = record.optInt("status") == 1;
        View view = content;
        int color = ContextCompat.getColor(this, ok ? R.color.mc_green : R.color.mc_orange);
        TextView heading = view.findViewById(R.id.result_title);
        heading.setText(ok ? "晨检正常" : "晨检异常");
        heading.setTextColor(color);
        ((ImageView) view.findViewById(R.id.result_status_icon)).setImageResource(
                ok ? R.drawable.mc_result_ok : R.drawable.mc_result_warning);
        ((TextView) view.findViewById(R.id.result_summary_hint)).setText(
                ok ? "本次已检项目无异常" : "请查看标注照片与异常明细");
        String department = record.optString("department");
        ((TextView) view.findViewById(R.id.result_person)).setText(record.optString("staff_name")
                + (record.optString("staff_no").isEmpty() ? "" : " · " + record.optString("staff_no"))
                + (department.isEmpty() ? "" : " · " + department));
        ((TextView) view.findViewById(R.id.result_date)).setText(date(record.optLong("created_at")));
        view.findViewById(R.id.result_test_identity).setVisibility(
                record.optBoolean("identity_skipped") ? View.VISIBLE : View.GONE);
        String temperature = record.optString("temperature");
        TextView temperatureView = view.findViewById(R.id.result_temperature);
        temperatureView.setText(temperature.isEmpty() || "未测".equals(temperature) ? "未测" : temperature + " °C");
        temperatureView.setTextColor(ContextCompat.getColor(this,
                record.optInt("abnormal_temperature") == 1 ? R.color.mc_orange : R.color.mc_ink));
        temperatureView.setContentDescription("体温 " + temperatureView.getText() + "，" + record.optString("temperature_source"));
        String healthExpiry = record.optString("health_expiry");
        TextView healthView = view.findViewById(R.id.result_health);
        healthView.setText(healthExpiry.isEmpty() ? "未登记" : healthExpiry);
        healthView.setTextColor(ContextCompat.getColor(this,
                record.optInt("abnormal_health_cert_exp") == 1 ? R.color.mc_orange : R.color.mc_ink));
        view.findViewById(R.id.result_summary).setOnLongClickListener(v -> {
            notice("健康证：" + healthView.getText() + "\n测温来源：" + record.optString("temperature_source", "未记录"));
            return true;
        });
        bindResultFlag(view, R.id.result_head, record, "abnormal_face");
        bindResultFlag(view, R.id.result_hand, record, "abnormal_hand");
        boolean headHandKnown = record.has("abnormal_face") && record.has("abnormal_hand");
        boolean headHandOk = headHandKnown && record.optInt("abnormal_face") == 0 && record.optInt("abnormal_hand") == 0;
        TextView headHandView = view.findViewById(R.id.result_head_hand);
        headHandView.setText(!headHandKnown ? "未记录" : headHandOk ? "正常" : "有异常");
        headHandView.setTextColor(ContextCompat.getColor(this, !headHandKnown ? R.color.mc_muted : headHandOk ? R.color.mc_green : R.color.mc_orange));
        TextView findingTitle = view.findViewById(R.id.result_findings_title);
        if (findingTitle != null) findingTitle.setText(ok ? "检查说明" : "异常明细");
        findingTitle.setVisibility(ok ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.result_findings_scroll).setVisibility(ok ? View.GONE : View.VISIBLE);
        JSONArray issues = record.optJSONArray("findings");
        StringBuilder description = new StringBuilder();
        if (issues != null) for (int i = 0; i < issues.length(); i++)
            description.append(i == 0 ? "" : "\n").append(issues.optString(i));
        TextView details = view.findViewById(R.id.result_findings);
        details.setText(description.length() == 0 ? getString(R.string.mc_no_findings) : description.toString());
        details.setTextColor(color);
        view.findViewById(R.id.result_stay).setOnClickListener(v -> {
            paused = true;
            cancelAutomaticNext();
            main.removeCallbacks(nextAfterSpeech);
            updateFlowState();
            toast("已暂停自动迎接下一位");
        });
        view.findViewById(R.id.result_next).setOnClickListener(v -> nextEmployee());
        updateFlowState();
        bindEvidence(record);
    }

    private void bindResultFlag(View view, int id, JSONObject record, String key) {
        int flag = record.optInt(key, -1);
        TextView value = view.findViewById(id);
        value.setText(flag == 0 ? "正常" : flag == 1 ? "异常" : "未记录");
        value.setTextColor(ContextCompat.getColor(this,
                flag == 0 ? R.color.mc_green : flag == 1 ? R.color.mc_orange : R.color.mc_muted));
    }

    @Override
    protected void onSdkStateChanged() {
        super.onSdkStateChanged();
        if (speech != null && sdkReady()) speech.clearStatus();
        updateFlowState();
    }

    private void updateFlowState() {
        if (content == null || currentRecord == null) return;
        long seconds = (Math.max(0, nextPersonAt - SystemClock.uptimeMillis()) + 999) / 1000;
        TextView state = content.findViewById(R.id.result_flow_state);
        if (state != null) {
            if (paused) state.setText("已停留 · 点击下一位继续");
            else if (!sdkReady()) state.setText("");
            else if (nextPersonAt != 0 && seconds > 0)
                state.setText(getString(R.string.mc_result_next_countdown, seconds));
            else if (nextPersonAt != 0 && speech.awaitingOrSpeaking())
                state.setText(R.string.mc_result_next_after_voice);
            else state.setText(R.string.mc_result_auto_next);
        }
        TextView stay = content.findViewById(R.id.result_stay);
        if (stay != null) stay.setText(paused ? "已停留" : getString(R.string.mc_result_stay_here));
    }

    private void scheduleAutomaticNext() {
        if (!foreground || !resumed || paused || exitRequested || currentRecord == null || nextScheduled) return;
        long delay = currentRecord.optInt("status") == 1 ? 5000 : 15000;
        // 刷新文案和自动跳转共用一个截止时间，重排视图不重新开始计时。
        nextPersonAt = SystemClock.uptimeMillis() + delay;
        nextScheduled = true;
        updateFlowState();
        main.postDelayed(nextPerson, Math.min(1000, delay));
    }

    private void cancelAutomaticNext() {
        main.removeCallbacks(nextPerson);
        nextScheduled = false;
        nextPersonAt = 0;
    }

    /**
     * 全部采集证据就地展示，列表位置与单图查看器使用同一份照片清单。
     */
    private void bindEvidence(JSONObject record) {
        RecyclerView grid = content.findViewById(R.id.result_evidence_grid);
        if (grid == null || !foreground) return;
        releaseEvidence();
        List<RecordPhotos.Photo> captured = RecordPhotos.read(record);
        int photoCount = 0;
        for (RecordPhotos.Photo photo : captured) if (!photo.file.isEmpty()) photoCount++;
        TextView caption = content.findViewById(R.id.result_evidence_caption);
        caption.setText("共 " + photoCount + " 张");
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            grid.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false) {
                @Override
                public void measureChildWithMargins(View child, int widthUsed, int heightUsed) {
                    int gap = getResources().getDimensionPixelSize(R.dimen.dp_6);
                    // 父布局按权重多轮测量，每轮都按当前视口分配三张卡片的宽度。
                    child.getLayoutParams().width = Math.max(1, (getWidth() - getPaddingLeft()
                            - getPaddingRight() - gap * 2) / 3);
                    super.measureChildWithMargins(child, widthUsed, heightUsed);
                }
            });
        } else {
            grid.setLayoutManager(new GridLayoutManager(this, 2));
        }
        if (grid.getItemDecorationCount() == 0) {
            int gap = getResources().getDimensionPixelSize(R.dimen.dp_6);
            grid.addItemDecoration(new RecyclerView.ItemDecoration() {
                @Override
                public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                    int position = parent.getChildAdapterPosition(view);
                    if (position == RecyclerView.NO_POSITION) return;
                    if (parent.getLayoutManager() instanceof GridLayoutManager) {
                        outRect.set(position % 2 == 0 ? 0 : gap - gap / 2, 0,
                                position % 2 == 0 ? gap / 2 : 0, gap);
                    } else if (position < state.getItemCount() - 1) {
                        outRect.right = gap;
                    }
                }
            });
        }
        evidencePhotos = new EvidencePhotos(record, captured, evidenceGeneration, content);
        grid.setAdapter(evidencePhotos);
    }

    private void releaseEvidence() {
        evidenceGeneration++;
        if (evidencePhotos != null) {
            RecyclerView grid = evidencePhotos.host.findViewById(R.id.result_evidence_grid);
            if (grid != null) grid.setAdapter(null);
            evidencePhotos.release();
            evidencePhotos = null;
        }
    }

    /**
     * 复用现有存储队列，只解码列表需要的缩略图；代次与槽位版本共同隔离迟到结果。
     */
    private final class EvidencePhotos extends RecyclerView.Adapter<EvidenceHolder> {
        final JSONObject record;
        final List<RecordPhotos.Photo> values;
        final int generation;
        final View host;
        final List<EvidenceHolder> holders = new ArrayList<>();

        EvidencePhotos(JSONObject record, List<RecordPhotos.Photo> values, int generation, View host) {
            this.record = record;
            this.values = values;
            this.generation = generation;
            this.host = host;
        }

        @Override
        public int getItemCount() {
            return values.size();
        }

        @Override
        public EvidenceHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View row = getLayoutInflater().inflate(R.layout.item_result_photo, parent, false);
            EvidenceHolder holder = new EvidenceHolder(row);
            holders.add(holder);
            return holder;
        }

        @Override
        public void onBindViewHolder(EvidenceHolder holder, int position) {
            if (holder.position == position) return;
            holder.clear();
            holder.position = position;
            RecordPhotos.Photo photo = values.get(position);
            holder.label.setText(photo.label);
            holder.image.setContentDescription(photo.label);
            holder.empty.setText(photo.file.isEmpty() ? R.string.mc_result_photo_absent : R.string.record_detail_photo_loading);
            holder.empty.setVisibility(View.VISIBLE);
            final EvidenceHolder expected = holder;
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
                } catch (Exception ignored) {
                    // 历史照片可能已清理，保留原位置和缺图提示。
                }
                final Bitmap result = decoded;
                main.post(() -> {
                    if (destroyed || !foreground || generation != evidenceGeneration || content != host
                            || expected.version != version) {
                        if (result != null) result.recycle();
                        return;
                    }
                    expected.bitmap = result;
                    if (result == null) expected.image.setImageDrawable(null);
                    else expected.image.setImageBitmap(result);
                    expected.empty.setText(R.string.record_detail_photo_unreadable);
                    expected.empty.setVisibility(result == null ? View.VISIBLE : View.GONE);
                    View.OnClickListener open = result == null ? null : v -> {
                        int currentPosition = expected.getBindingAdapterPosition();
                        if (currentPosition != RecyclerView.NO_POSITION) showPhoto(record, currentPosition);
                    };
                    expected.row.setOnClickListener(open);
                    expected.image.setOnClickListener(open);
                    expected.row.setClickable(open != null);
                    expected.image.setClickable(open != null);
                });
            });
        }

        void release() {
            for (EvidenceHolder holder : holders) holder.clear();
            holders.clear();
        }
    }

    private static final class EvidenceHolder extends RecyclerView.ViewHolder {
        final View row;
        final ImageView image;
        final TextView empty, label;
        int position = -1, version;
        Bitmap bitmap;

        EvidenceHolder(View row) {
            super(row);
            this.row = row;
            image = row.findViewById(R.id.result_photo_image);
            empty = row.findViewById(R.id.result_photo_empty);
            label = row.findViewById(R.id.result_photo_label);
        }

        void clear() {
            version++;
            position = -1;
            image.setImageDrawable(null);
            image.setOnClickListener(null);
            row.setOnClickListener(null);
            image.setClickable(false);
            row.setClickable(false);
            if (bitmap != null) bitmap.recycle();
            bitmap = null;
        }
    }
}
