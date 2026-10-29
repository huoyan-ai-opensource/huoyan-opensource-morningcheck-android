package cc.huoyan.opensource.morningcheck;

import static cc.huoyan.opensource.morningcheck.data.Jsons.obj;
import static cc.huoyan.opensource.morningcheck.data.Jsons.put;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcelable;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.domain.FaceDetectionResult;
import cc.huoyan.opensource.morningcheck.domain.FaceResultPolicy;
import cc.huoyan.opensource.morningcheck.domain.FaceSamplePresentation;
import cc.huoyan.opensource.morningcheck.hardware.LightConfig;
import cc.huoyan.opensource.morningcheck.hardware.LightController;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 同一个人脸学习页负责选择员工及采集；已发送的学习始终按捕获的员工编号收尾。
 */
public final class FaceEnrollmentActivity extends CameraMorningActivity {
    public static final String EXTRA_STAFF_ID = "staff_id";
    private static final long FRAME_MAX_AGE_NANOS = 5_000_000_000L;
    private static final long DETECTION_MAX_AGE_NANOS = 1_500_000_000L;
    private static final long DETECTION_RETRY_NANOS = 1_000_000_000L;

    private long staffId;
    private boolean standalone;
    private boolean selectionExpanded;
    private Parcelable employeeScrollState;
    private String employeeScrollQuery = "";
    private int employeeScrollPage = 1;
    private long employeeScrollId;
    private int employeeScrollPosition, employeeScrollTop;
    private int selectionVersion, listReadVersion, staffPage = 1;
    private String staffQuery = "", loadedQuery = "";
    private AppStore.DataPage staffData;
    private List<JSONObject> visibleStaff = new ArrayList<>();
    private boolean pageLoading, pageError;
    private boolean failedStaffAppend;
    private boolean notifyingStaffChange;
    private final Runnable staffChanged = () -> {
        if (!notifyingStaffChange) reload(false, null);
    };
    private final Runnable searchChanged = () -> reload(false, null);
    private JSONObject person;
    private int personReadVersion;
    private boolean personLoading;
    private String personReadError = "";
    private TextView staffName, previewStatus, capture, cameraSettings;
    private EnrollmentRows enrollmentRows;
    private List<JSONObject> submittedStaff, committedStaff;
    private int staffRowsVersion;
    private long highlightedStaffId;
    private LoadingDialog operationLoading;
    private boolean sending, previewReceivedFrame, resumed;
    private MorningVoice speech;
    private LightController.Scope lighting;
    private LightConfig requestedLightConfig;
    private String lightingConfigError = "";
    private int detectionScope, detectionRequest, detectedFaces = -1;
    private boolean detecting;
    private String observedDetectionScope = "", detectionMessage = "";
    private long nextDetectionAt;
    private CameraSource.Frame detectedFrame;
    private LearningWrite pendingPreflight;
    private CameraSource.Frame pendingPreflightFrame;
    private final Runnable expireDetection = () -> {
        if (!singleFaceCurrent()) {
            clearFaceDetection();
            updateUi();
        }
    };
    private String sendingLabel = "正在学习", sampleMessage = "";
    private static final long MAX_SAMPLE_IMAGE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_SAMPLE_BITMAP_BYTES = 16 * 1024 * 1024;
    private final OkHttpClient sampleHttp = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();
    private final Object sampleImageLock = new Object();
    private Call sampleImageCall;
    private final Map<Long, String> sampleUrls = new LinkedHashMap<>();
    private JSONArray sampleUids = new JSONArray();
    private int sampleReadVersion;
    private volatile int sampleDecodeVersion;
    private boolean queryingFaces, decodingFaces;
    private long verifiedGeneration = -1, verifiedVersion = -1, verifiedPersonId;
    private String sampleDevice = "", observedSampleScope = "";
    private boolean observedReady;
    private boolean needsSampleVerification;
    private final List<FaceImage> faceImages = new ArrayList<>();
    private final LruCache<Long, Bitmap> sampleBitmaps = new LruCache<Long, Bitmap>(MAX_SAMPLE_BITMAP_BYTES) {
        @Override
        protected int sizeOf(Long uid, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };
    private SampleImageBatch sampleImageBatch;
    private int sampleImageLoadingPosition = -1;
    private final Runnable loadVisibleSampleImages = this::loadNextVisibleSampleImage;
    private SampleRows sampleRows;
    private long sampleScrollStaffId, sampleScrollUid;
    private String sampleScrollDevice = "";
    private int sampleScrollPosition, sampleScrollTop;
    private boolean sampleScrollPending;
    private WriteFeedback writeFeedback;

    @Override
    protected String pageKey() {
        return "enroll";
    }

    @Override
    protected int activityLayout() {
        return R.layout.activity_face_design;
    }

    @Override
    protected void setup(Bundle state) {
        speech = new MorningVoice(this, main, () -> rule("voice", true));
        staffId = getIntent().getLongExtra(EXTRA_STAFF_ID, 0);
        standalone = staffId <= 0;
        selectionExpanded = staffId <= 0;
        if (state != null) {
            staffId = state.getLong("enrollment_staff_id", staffId);
            standalone = state.getBoolean("enrollment_standalone", standalone);
            staffQuery = state.getString("enrollment_query", "");
            staffPage = Math.max(1, state.getInt("enrollment_page", 1));
            selectionExpanded = state.getBoolean("enrollment_selection_expanded", staffId <= 0);
            employeeScrollState = state.getParcelable("enrollment_employee_scroll");
            employeeScrollQuery = state.getString("enrollment_employee_scroll_query", staffQuery);
            employeeScrollPage = state.getInt("enrollment_employee_scroll_page", staffPage);
            employeeScrollId = state.getLong("enrollment_employee_scroll_id");
            employeeScrollPosition = state.getInt("enrollment_employee_scroll_position");
            employeeScrollTop = state.getInt("enrollment_employee_scroll_top");
            sampleScrollStaffId = state.getLong("enrollment_sample_scroll_staff_id");
            sampleScrollUid = state.getLong("enrollment_sample_scroll_uid");
            sampleScrollDevice = state.getString("enrollment_sample_scroll_device", "");
            sampleScrollPosition = state.getInt("enrollment_sample_scroll_position");
            sampleScrollTop = state.getInt("enrollment_sample_scroll_top");
            sampleScrollPending = sampleScrollUid > 0;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        rememberEmployeeScroll();
        rememberSampleScroll();
        state.putLong("enrollment_staff_id", staffId);
        state.putBoolean("enrollment_standalone", standalone);
        state.putString("enrollment_query", staffQuery);
        state.putInt("enrollment_page", staffPage);
        state.putBoolean("enrollment_selection_expanded", selectionExpanded);
        state.putParcelable("enrollment_employee_scroll", employeeScrollState);
        state.putString("enrollment_employee_scroll_query", employeeScrollQuery);
        state.putInt("enrollment_employee_scroll_page", employeeScrollPage);
        state.putLong("enrollment_employee_scroll_id", employeeScrollId);
        state.putInt("enrollment_employee_scroll_position", employeeScrollPosition);
        state.putInt("enrollment_employee_scroll_top", employeeScrollTop);
        state.putLong("enrollment_sample_scroll_staff_id", sampleScrollStaffId);
        state.putLong("enrollment_sample_scroll_uid", sampleScrollUid);
        state.putString("enrollment_sample_scroll_device", sampleScrollDevice);
        state.putInt("enrollment_sample_scroll_position", sampleScrollPosition);
        state.putInt("enrollment_sample_scroll_top", sampleScrollTop);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void bindPage() {
        findViewById(R.id.header_home).setOnClickListener(view -> onBackPressed());
        ((TextView) findViewById(R.id.header_back_label)).setText("返回首页");
        findViewById(R.id.header_back_label).setContentDescription("返回首页");
        findViewById(R.id.header_home).setContentDescription("返回首页");
        updateWorkspaceVisibility();
        content.findViewById(R.id.enrollment_selection_close).setOnClickListener(view -> setSelectionExpanded(false));
        bindSelection();
        staffName = content.findViewById(R.id.enrollment_staff_name);
        content.findViewById(R.id.enrollment_staff_summary).setOnLongClickListener(view -> {
            if (person != null)
                notice(person.optString("name") + " · 工号 " + person.optString("staff_no", "未填写")
                        + "\n" + person.optString("department", "未分配部门")
                        + (person.optBoolean("face_registered") ? " · 已录入人脸" : " · 尚未录入人脸"));
            return true;
        });
        previewStatus = content.findViewById(R.id.enrollment_preview_status);
        capture = content.findViewById(R.id.enrollment_capture);
        cameraSettings = content.findViewById(R.id.enrollment_camera_settings);
        TextView chooseStaff = findViewById(R.id.enrollment_choose_staff);
        if (chooseStaff != null) {
            chooseStaff.setOnClickListener(view -> setSelectionExpanded(!selectionExpanded));
        }
        previewReceivedFrame = false;
        capture.setOnClickListener(view -> captureFace());
        cameraSettings.setOnClickListener(view -> {
            if (!writeInProgress()) navigate("settings", 4);
        });
        content.findViewById(R.id.enrollment_capture_panel).setOnLongClickListener(view -> {
            cameraSettings.performClick();
            return true;
        });
        bindSamples();
        preparePreview();
        updateUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ensureAppFontCurrent()) return;
        application.addStaffChangeListener(staffChanged);
        preparePreview();
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureAppFontCurrent()) return;
        resumed = true;
        speech.resume();
        observeDetectionScope();
        if (latestFrame != null) detectPreview(latestFrame);
        updateUi();
    }

    @Override
    protected void onPause() {
        // STARTED 仍未走到 onStop；此时已失去交互权，不能继续预检或补发学习。
        resumed = false;
        closeLighting();
        session++;
        invalidateFaceDetection();
        cancelPreflight("页面已暂停，人脸学习尚未发送，请重新采集");
        speech.pause();
        updateUi();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        preparePreview();
    }

    @Override
    public void onBackPressed() {
        if (selectionExpanded && staffId > 0) setSelectionExpanded(false);
        else finish();
    }

    private boolean choosingEmployee() {
        return selectionExpanded;
    }

    private void updateWorkspaceVisibility() {
        if (content == null) return;
        View selection = content.findViewById(R.id.enrollment_selection);
        selection.setVisibility(selectionExpanded ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.enrollment_selection_overlay).setVisibility(selectionExpanded ? View.VISIBLE : View.GONE);
        View work = content.findViewById(R.id.enrollment_work);
        if (work != null) work.setVisibility(View.VISIBLE);
    }

    private void setSelectionExpanded(boolean expanded) {
        if (writeInProgress() || selectionExpanded == expanded) return;
        if (expanded) {
            // 选择浮层打开时释放镜头，返回采集区重新取得新帧。
            session++;
            stopCamera();
            previewReceivedFrame = false;
        } else {
            rememberEmployeeScroll();
            clearEmployeeSearchFocus();
        }
        selectionExpanded = expanded;
        updateWorkspaceVisibility();
        if (expanded) restoreEmployeeScroll();
        else {
            preparePreview();
            scheduleVisibleSampleImages();
        }
        updateUi();
    }

    private void clearEmployeeSearchFocus() {
        EditText search = content.findViewById(R.id.enrollment_search);
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    private void rememberEmployeeScroll() {
        if (content == null) return;
        RecyclerView employees = content.findViewById(R.id.enrollment_staff_list);
        if (employees == null || !employees.isShown() || enrollmentRows == null
                || enrollmentRows.getItemCount() == 0 || committedStaff != visibleStaff
                || !loadedQuery.equals(staffQuery)) return;
        GridLayoutManager layout = (GridLayoutManager) employees.getLayoutManager();
        if (layout == null) return;
        int position = layout.findFirstVisibleItemPosition();
        View first = layout.findViewByPosition(position);
        if (first == null || position < 0 || position >= enrollmentRows.getItemCount()) return;
        employeeScrollState = layout.onSaveInstanceState();
        employeeScrollQuery = staffQuery;
        employeeScrollPage = staffPage;
        employeeScrollId = enrollmentRows.item(position).optLong("id");
        employeeScrollPosition = position;
        employeeScrollTop = layout.getDecoratedTop(first) - employees.getPaddingTop();
    }

    private void restoreEmployeeScroll() {
        if (content == null || employeeScrollState == null || pageLoading || staffData == null
                || committedStaff != visibleStaff)
            return;
        if (!employeeScrollQuery.equals(staffQuery)) {
            employeeScrollState = null;
            return;
        }
        RecyclerView employees = content.findViewById(R.id.enrollment_staff_list);
        if (!employees.isShown() || enrollmentRows == null || enrollmentRows.getItemCount() == 0) return;
        GridLayoutManager layout = (GridLayoutManager) employees.getLayoutManager();
        if (layout == null) return;
        int position = Math.min(employeeScrollPosition, enrollmentRows.getItemCount() - 1);
        for (int index = 0; index < enrollmentRows.getItemCount(); index++) {
            if (enrollmentRows.item(index).optLong("id") == employeeScrollId) {
                position = index;
                break;
            }
        }
        // 筛选结果增删或网格列数变化后仍按员工身份定位，不能沿用旧行号。
        layout.scrollToPositionWithOffset(position, employeeScrollTop);
        employeeScrollState = null;
        highlightSelectedStaff();
    }

    private boolean writeInProgress() {
        return sending || (staffId > 0 && application.faceOperations.isWriting(staffId));
    }

    private void selectStaff(long selectedId) {
        // 隐藏的选择浮层不能绕过入口切人，提交中的人脸操作仍沿用原业务锁。
        if (!foreground || pageLoading || committedStaff != visibleStaff || selectedId <= 0 || writeInProgress()
                || !selectionExpanded) {
            highlightSelectedStaff();
            return;
        }
        if (selectedId == staffId && person != null) {
            setSelectionExpanded(false);
            return;
        }
        rememberEmployeeScroll();
        selectionExpanded = false;
        clearEmployeeSearchFocus();
        updateWorkspaceVisibility();
        session++;
        stopCamera();
        FrameLayout host = content.findViewById(R.id.enrollment_preview);
        host.removeAllViews();
        staffId = selectedId;
        selectionVersion++;
        personReadVersion++;
        person = null;
        personLoading = true;
        personReadError = "";
        invalidateSamples(true);
        previewReceivedFrame = false;
        cameraError = "";
        highlightSelectedStaff();
        updateUi();
        reloadPerson(null);
    }

    private void preparePreview() {
        if (destroyed || !foreground || choosingEmployee() || staffId <= 0 || person == null || personLoading)
            return;
        if (camera == null) {
            FrameLayout host = content.findViewById(R.id.enrollment_preview);
            host.removeAllViews();
            FrameLayout preview = previewFrame();
            preview.findViewById(R.id.camera_preview_caption).setVisibility(View.GONE);
            overlay.setGuideVisible(false);
            host.addView(preview);
            cameraError = "";
            ensureCamera(cameraForStage());
        }
    }

    @Override
    protected void ensureCamera(String id) {
        if (choosingEmployee() || staffId <= 0 || person == null || personLoading) return;
        super.ensureCamera(id);
        updateLighting();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] permissions, @NonNull int[] grants) {
        if (code == 20 && (choosingEmployee() || staffId <= 0 || person == null || personLoading))
            return;
        super.onRequestPermissionsResult(code, permissions, grants);
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> reload(recover, done));
            return;
        }
        if (destroyed) return;
        reloadStaffPage(done);
        if (staffId <= 0) {
            person = null;
            personLoading = false;
            updateUi();
            return;
        }
        reloadPerson(null);
    }

    private void reloadPerson(Runnable done) {
        int version = ++personReadVersion;
        final long targetStaffId = staffId;
        personLoading = true;
        personReadError = "";
        updateUi();
        // 员工列表只保留当前分页，学习页必须独立按编号读取自己的档案。
        runIo(() -> {
            try {
                JSONObject stored = targetStaffId > 0 ? store.staff(targetStaffId) : null;
                main.post(() -> {
                    if (destroyed || targetStaffId != staffId || version != personReadVersion)
                        return;
                    person = stored;
                    personLoading = false;
                    loaded = true;
                    if (stored == null) {
                        session++;
                        stopCamera();
                        if (!writeInProgress()) staffId = 0;
                        selectionExpanded = true;
                        updateWorkspaceVisibility();
                        content.<FrameLayout>findViewById(R.id.enrollment_preview).removeAllViews();
                        highlightSelectedStaff();
                        updateUi();
                        if (foreground && done != null) done.run();
                        return;
                    }
                    if (!foreground) return;
                    preparePreview();
                    observeSampleScope();
                    if (done != null) done.run();
                    else onDataLoaded();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || targetStaffId != staffId || version != personReadVersion)
                        return;
                    person = null;
                    personLoading = false;
                    personReadError = "员工档案读取失败：" + failure(error);
                    session++;
                    stopCamera();
                    updateUi();
                    notice(personReadError);
                });
            }
        });
    }

    private void bindSelection() {
        EditText search = content.findViewById(R.id.enrollment_search);
        content.findViewById(R.id.enrollment_search_clear).setOnClickListener(view -> search.setText(""));
        search.setText(staffQuery);
        search.setOnEditorActionListener((view, actionId, event) -> {
            clearEmployeeSearchFocus();
            return true;
        });
        search.addTextChangedListener(watcher(() -> {
            String query = search.getText().toString().trim();
            if (query.equals(staffQuery)) return;
            if (writeInProgress()) {
                search.setText(staffQuery);
                search.setSelection(search.length());
                return;
            }
            staffQuery = query;
            staffPage = 1;
            employeeScrollState = null;
            staffData = null;
            visibleStaff = new ArrayList<>();
            failedStaffAppend = false;
            listReadVersion++;
            pageLoading = true;
            showStaffPage();
            main.removeCallbacks(searchChanged);
            main.postDelayed(searchChanged, 250);
        }));
        content.findViewById(R.id.enrollment_previous).setOnClickListener(view -> {
            if (pageLoading || staffPage <= 1 || writeInProgress()) return;
            staffPage--;
            reloadStaffPage(null);
        });
        content.findViewById(R.id.enrollment_next).setOnClickListener(view -> {
            loadMoreStaff();
        });
        content.findViewById(R.id.enrollment_page_info).setOnClickListener(view -> {
            if (pageError && !writeInProgress()) reloadStaffPage(null);
        });
        RecyclerView list = content.findViewById(R.id.enrollment_staff_list);
        list.setLayoutManager(new GridLayoutManager(this, getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE ? 3 : 2));
        list.setItemAnimator(null);
        int spacing = getResources().getDimensionPixelSize(R.dimen.dp_4);
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                if (position == RecyclerView.NO_POSITION) return;
                int columns = ((GridLayoutManager) parent.getLayoutManager()).getSpanCount();
                int column = position % columns;
                out.left = column * spacing / columns;
                out.right = spacing - (column + 1) * spacing / columns;
                out.bottom = spacing;
            }
        });
        if (enrollmentRows == null) enrollmentRows = new EnrollmentRows();
        list.setAdapter(enrollmentRows);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                GridLayoutManager layout = (GridLayoutManager) view.getLayoutManager();
                if (dy > 0 && layout != null && !pageError
                        && layout.findLastVisibleItemPosition() >= enrollmentRows.getItemCount() - 6)
                    loadMoreStaff();
            }
        });
        showStaffPage();
    }

    private void loadMoreStaff() {
        if (!foreground || !selectionExpanded || pageLoading || committedStaff != visibleStaff
                || writeInProgress()) return;
        if (pageError && !failedStaffAppend) reloadStaffPage(null);
        else if (staffData != null && staffPage < staffData.totalPages)
            readStaffPage(true, null);
    }

    private void reloadStaffPage(Runnable done) {
        rememberEmployeeScroll();
        readStaffPage(false, done);
    }

    private void readStaffPage(boolean append, Runnable done) {
        main.removeCallbacks(searchChanged);
        int version = ++listReadVersion;
        String query = staffQuery;
        int page = append ? staffPage + 1 : staffPage;
        if (!loadedQuery.equals(query)) {
            staffData = null;
            visibleStaff = new ArrayList<>();
        }
        pageError = false;
        pageLoading = true;
        showStaffPage();
        runIo(() -> {
            try {
                AppStore.DataPage data = append ? store.staffPage(query, page, 24)
                        : store.staffWindow(query, page, 24);
                main.post(() -> {
                    if (destroyed || version != listReadVersion) return;
                    LinkedHashMap<Long, JSONObject> rows = new LinkedHashMap<>();
                    if (append) {
                        for (JSONObject row : visibleStaff) rows.put(row.optLong("id"), row);
                    }
                    for (JSONObject row : data.rows) rows.put(row.optLong("id"), row);
                    staffData = data;
                    visibleStaff = new ArrayList<>(rows.values());
                    loadedQuery = query;
                    staffPage = data.page;
                    pageLoading = false;
                    failedStaffAppend = false;
                    loaded = true;
                    if (!foreground) return;
                    showStaffPage();
                    if (done != null) done.run();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || version != listReadVersion) return;
                    pageLoading = false;
                    pageError = true;
                    failedStaffAppend = append;
                    showStaffPage();
                    if (foreground) notice("员工读取失败：" + failure(error));
                });
            }
        });
    }

    private void showStaffPage() {
        if (content == null) return;
        updateOperationLoading();
        RecyclerView list = content.findViewById(R.id.enrollment_staff_list);
        if (list == null) return;
        List<JSONObject> values = visibleStaff;
        if (enrollmentRows != null && submittedStaff != values) {
            submittedStaff = values;
            int version = ++staffRowsVersion;
            enrollmentRows.submitRows(values, () -> {
                if (!destroyed && version == staffRowsVersion
                        && submittedStaff == values && content.findViewById(R.id.enrollment_staff_list) == list) {
                    committedStaff = values;
                    showStaffPage();
                    highlightSelectedStaff();
                }
            });
        } else restoreEmployeeScroll();
        list.setEnabled(!pageLoading && committedStaff == values && !writeInProgress());
        content.findViewById(R.id.enrollment_search).setEnabled(!writeInProgress());
        TextView empty = content.findViewById(R.id.enrollment_empty);
        empty.setText(staffData == null || staffData.totalRows == 0
                ? R.string.face_enrollment_staff_empty : R.string.business_staff_no_match);
        empty.setVisibility(!pageLoading && staffData != null && values.isEmpty() ? View.VISIBLE : View.GONE);
        TextView info = content.findViewById(R.id.enrollment_page_info);
        info.setText(pageError ? "点击重新读取"
                : pageLoading || staffData == null ? ""
                  : getString(R.string.business_page_info, staffData.page, staffData.totalPages, staffData.total));
        View previous = content.findViewById(R.id.enrollment_previous);
        previous.setEnabled(!pageLoading && !writeInProgress() && staffPage > 1);
        previous.setAlpha(previous.isEnabled() ? 1f : .45f);
        View next = content.findViewById(R.id.enrollment_next);
        ((TextView) next).setText(pageError ? "点击重新读取" : pageLoading ? ""
                                                               : staffData != null && staffPage >= staffData.totalPages ? "已显示全部员工" : "上拉加载更多员工");
        next.setEnabled(!pageLoading && !writeInProgress()
                && (pageError || staffData != null && staffPage < staffData.totalPages));
        next.setAlpha(next.isEnabled() ? 1f : .45f);
    }

    private void highlightSelectedStaff() {
        if (enrollmentRows == null || content == null) return;
        RecyclerView list = content.findViewById(R.id.enrollment_staff_list);
        if (highlightedStaffId != staffId) {
            long previous = highlightedStaffId;
            highlightedStaffId = staffId;
            for (int index = 0; index < enrollmentRows.getItemCount(); index++) {
                long id = enrollmentRows.item(index).optLong("id");
                if (id == previous || id == staffId) enrollmentRows.notifyItemChanged(index);
            }
        }
        for (int index = 0; index < list.getChildCount(); index++) {
            int position = list.getChildAdapterPosition(list.getChildAt(index));
            if (position >= 0 && position < enrollmentRows.getItemCount())
                enrollmentRows.bindSelection(list.getChildAt(index), enrollmentRows.item(position));
        }
    }

    private final class EnrollmentRows extends JsonRowsAdapter<EnrollmentHolder> {
        EnrollmentRows() {
            super("id");
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return position >= 0 && position < getItemCount() ? item(position).optLong("id")
                    : RecyclerView.NO_ID;
        }

        @Override
        @NonNull
        public EnrollmentHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new EnrollmentHolder(getLayoutInflater().inflate(R.layout.item_face_employee, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull EnrollmentHolder holder, int position) {
            JSONObject employee = item(position);
            holder.name.setText(employee.optString("name"));
            holder.meta.setText("工号 " + employee.optString("staff_no", "未填写")
                    + (employee.optString("department").isEmpty() ? "" : " · " + employee.optString("department")));
            holder.state.setText(employee.optBoolean("face_registered") ? "已录人脸" : "待录人脸");
            bindSelection(holder.itemView, employee);
        }

        void bindSelection(View row, JSONObject employee) {
            boolean selected = employee.optLong("id") == staffId && staffId > 0;
            row.setActivated(selected);
            row.setBackgroundResource(selected ? R.drawable.mc_face_staff_selected : R.drawable.mc_field);
        }
    }

    private final class EnrollmentHolder extends RecyclerView.ViewHolder {
        final TextView name, meta, state;

        EnrollmentHolder(View row) {
            super(row);
            name = row.findViewById(R.id.business_row_name);
            meta = row.findViewById(R.id.business_row_meta);
            state = row.findViewById(R.id.business_row_state);
            row.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION)
                    selectStaff(enrollmentRows.item(position).optLong("id"));
            });
        }
    }

    @Override
    protected void beforeRender() {
        rememberEmployeeScroll();
        staffRowsVersion++;
        submittedStaff = null;
        committedStaff = null;
        if (content != null) {
            RecyclerView list = content.findViewById(R.id.enrollment_staff_list);
            if (list != null) list.setAdapter(null);
        }
        invalidateSamples(false);
        observedSampleScope = "";
        super.beforeRender();
    }

    private void bindSamples() {
        GridView grid = content.findViewById(R.id.enrollment_faces);
        grid.setNumColumns(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 3 : 4);
        sampleRows = new SampleRows();
        grid.setAdapter(sampleRows);
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                scheduleVisibleSampleImages();
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                scheduleVisibleSampleImages();
            }
        });
        content.findViewById(R.id.enrollment_faces_refresh).setOnClickListener(view -> verifySamples(true));
        content.findViewById(R.id.enrollment_faces_info).setOnLongClickListener(view -> {
            content.findViewById(R.id.enrollment_faces_refresh).performClick();
            return true;
        });
        content.findViewById(R.id.enrollment_faces_clear).setOnClickListener(view -> requestDeleteSample(0));
        updateSampleControls();
    }

    private void rememberSampleScroll() {
        if (sampleScrollPending || content == null || sampleImageBatch == null || faceImages.isEmpty()) return;
        GridView grid = content.findViewById(R.id.enrollment_faces);
        if (grid == null || grid.getChildCount() == 0) return;
        int position = grid.getFirstVisiblePosition();
        if (position < 0 || position >= faceImages.size()) return;
        sampleScrollStaffId = sampleImageBatch.employee;
        sampleScrollDevice = sampleImageBatch.device;
        sampleScrollUid = faceImages.get(position).uid;
        sampleScrollPosition = position;
        sampleScrollTop = grid.getChildAt(0).getTop() - grid.getPaddingTop();
        sampleScrollPending = true;
    }

    private void restoreSampleScroll() {
        if (!sampleScrollPending || faceImages.isEmpty()) return;
        if (sampleScrollStaffId != staffId || !sampleScrollDevice.equals(sampleDevice)) {
            sampleScrollPending = false;
            sampleScrollUid = 0;
            return;
        }
        GridView grid = content.findViewById(R.id.enrollment_faces);
        if (grid == null) return;
        int position = Math.min(sampleScrollPosition, faceImages.size() - 1);
        for (int index = 0; index < faceImages.size(); index++) {
            if (faceImages.get(index).uid == sampleScrollUid) {
                position = index;
                break;
            }
        }
        // GridView 在触摸模式下不能使用继承的 setSelectionFromTop，需先定位再补行内偏移。
        final int restorePosition = position, restoreTop = sampleScrollTop;
        grid.setSelection(restorePosition);
        SampleImageBatch batch = sampleImageBatch;
        ViewTreeObserver observer = grid.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                boolean current = !destroyed && sampleImageBatch == batch && batch != null
                        && batch.version == sampleDecodeVersion
                        && content.findViewById(R.id.enrollment_faces) == grid;
                if (current) {
                    View anchor = grid.getChildAt(restorePosition - grid.getFirstVisiblePosition());
                    if (anchor == null) {
                        // 系统恢复的空列表状态可能覆盖首次定位；等新一轮布局完成再绘制。
                        grid.setSelection(restorePosition);
                        return false;
                    }
                    grid.scrollListBy(anchor.getTop() - grid.getPaddingTop() - restoreTop);
                    sampleScrollPending = false;
                }
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                else grid.getViewTreeObserver().removeOnPreDrawListener(this);
                return true;
            }
        });
    }

    private String currentDevice() {
        return sdkSession.status().optString("device_id");
    }

    private long currentPersonId() {
        return person == null ? 0 : person.optLong("person_id");
    }

    private boolean sampleScope(long employee, long personId, int selection, long generation, String device) {
        return !destroyed && staffId == employee && selectionVersion == selection
                && personId > 0 && currentPersonId() == personId
                && sdkGeneration() == generation && device.equals(currentDevice());
    }

    private boolean samplesVerified() {
        return staffId > 0 && currentPersonId() > 0 && verifiedPersonId == currentPersonId()
                && person != null && !personLoading && sdk.isConnected()
                && !sampleDevice.isEmpty() && sampleDevice.equals(currentDevice())
                && verifiedGeneration == sdkGeneration()
                && application.faceOperations.acceptsQuery(staffId, verifiedVersion)
                && !person.optBoolean("face_write_unknown");
    }

    private void observeSampleScope() {
        if (destroyed || !foreground || personLoading || person == null || staffId <= 0 || writeInProgress())
            return;
        String device = currentDevice();
        String scope = currentSampleScope();
        boolean ready = sdkReady();
        boolean changed = !scope.equals(observedSampleScope);
        boolean becameReady = ready && !observedReady;
        observedReady = ready;
        if (changed) {
            boolean retainUnknownSamples = person.optBoolean("face_write_unknown") && device.equals(sampleDevice);
            // 未知删除不能把原设备已知样本显示成空；保留图和 UID，核对完成前仍禁用写入。
            invalidateSamples(!retainUnknownSamples);
            observedSampleScope = scope;
            if (retainUnknownSamples) loadSampleImages();
        }
        if ((changed || becameReady && !samplesVerified() || needsSampleVerification) && sdk.isConnected() && !device.isEmpty()
                && !person.optBoolean("face_write_unknown")) verifySamples(false);
    }

    private String currentSampleScope() {
        return staffId + ":" + currentPersonId() + ":" + selectionVersion + ":" + sdkGeneration() + ":" + currentDevice();
    }

    private void verifySamples(boolean manual) {
        if (!foreground || destroyed || staffId <= 0 || currentPersonId() <= 0 || person == null || personLoading
                || queryingFaces || writeInProgress() || !sdk.isConnected() || currentDevice().isEmpty())
            return;
        final long employee = staffId, personId = currentPersonId(), generation = sdkGeneration();
        final int selection = selectionVersion, version = ++sampleReadVersion;
        final long faceVersion = application.faceOperations.version(employee);
        final String device = currentDevice(), attempt = person.optString("face_write_attempt");
        queryingFaces = true;
        needsSampleVerification = false;
        sampleMessage = "正在核对当前设备的人脸样本";
        updateUi();
        HuoyanClient.Callback callback = (code, message, data) -> {
            if (!sampleScope(employee, personId, selection, generation, device) || version != sampleReadVersion)
                return;
            if (!application.faceOperations.acceptsQuery(employee, faceVersion)) {
                discardSampleReadAfterWrite();
                return;
            }
            final JSONArray uids;
            final FaceResultPolicy.VerifiedSamples verified;
            try {
                verified = FaceResultPolicy.verifiedSamples(code, data);
                uids = verified.uids;
            } catch (IllegalArgumentException invalid) {
                queryingFaces = false;
                verifiedGeneration = -1;
                sampleMessage = "暂时无法核对样本，请稍后刷新";
                updateUi();
                if (manual) notice(code == 0 ? invalid.getMessage() : message);
                return;
            }
            runIo(() -> {
                try {
                    JSONObject current = store.staff(employee);
                    if (current == null || current.optLong("person_id") != personId
                            || sdkGeneration() != generation || !device.equals(currentDevice())
                            || !attempt.equals(current.optString("face_write_attempt"))
                            || (current.optBoolean("face_write_unknown") && current.optLong("face_write_person_id", personId) != personId)
                            || (current.optBoolean("face_write_unknown")
                            && !current.optString("face_write_device").isEmpty()
                            && !device.equals(current.optString("face_write_device")))) {
                        main.post(() -> finishSampleRead(version));
                        return;
                    }
                    JSONObject updated = store.patchStaff(employee, obj("face_registered", uids.length() > 0,
                            "face_uids", uids, "face_uid", uids.length() == 0 ? 0 : uids.optLong(uids.length() - 1),
                            "face_write_unknown", false, "face_write_attempt", "", "face_write_device", "", "face_write_person_id", 0));
                    main.post(() -> {
                        if (!sampleScope(employee, personId, selection, generation, device) || version != sampleReadVersion)
                            return;
                        if (!application.faceOperations.acceptsQuery(employee, faceVersion)) {
                            discardSampleReadAfterWrite();
                            return;
                        }
                        queryingFaces = false;
                        person = updated;
                        sampleUids = uids;
                        sampleUrls.clear();
                        sampleUrls.putAll(verified.urls);
                        sampleDevice = device;
                        verifiedGeneration = generation;
                        verifiedVersion = faceVersion;
                        verifiedPersonId = personId;
                        sampleMessage = "";
                        loadSampleImages();
                        updateUi();
                        if (manual) toast("人脸图片已刷新");
                    });
                } catch (Exception error) {
                    main.post(() -> {
                        if (!sampleScope(employee, personId, selection, generation, device) || version != sampleReadVersion)
                            return;
                        queryingFaces = false;
                        verifiedGeneration = -1;
                        sampleMessage = "样本核对未能保存，请刷新重试";
                        updateUi();
                        if (manual) notice(failure(error));
                    });
                }
            });
        };
        try {
            sdk.userDetail(personId, callback);
        } catch (RuntimeException error) {
            queryingFaces = false;
            verifiedGeneration = -1;
            updateUi();
            if (manual) notice("人脸图片读取失败：" + failure(error));
        }
    }

    private void discardSampleReadAfterWrite() {
        // 另一个页面可在查询途中写入；旧查询不能覆盖结果，也不能一直保留查询占用。
        queryingFaces = false;
        verifiedGeneration = -1;
        needsSampleVerification = true;
        observeSampleScope();
        updateUi();
    }

    private void finishSampleRead(int version) {
        if (version != sampleReadVersion || destroyed) return;
        queryingFaces = false;
        verifiedGeneration = -1;
        sampleMessage = "员工或设备状态已变化，请刷新核对";
        updateUi();
    }

    private void invalidateSampleVerification() {
        sampleReadVersion++;
        queryingFaces = false;
        verifiedGeneration = -1;
        verifiedVersion = -1;
        verifiedPersonId = 0;
        sampleMessage = "";
    }

    private void invalidateSamples(boolean clearList) {
        invalidateSampleVerification();
        clearFaceImages();
        if (clearList) {
            sampleUids = new JSONArray();
            sampleUrls.clear();
            sampleDevice = "";
            observedSampleScope = "";
        }
        String device = currentDevice();
        if (sampleScrollStaffId != staffId || !device.isEmpty() && !sampleScrollDevice.equals(device)) {
            sampleScrollPending = false;
            sampleScrollUid = 0;
        }
    }

    private void clearFaceImages() {
        rememberSampleScroll();
        cancelSampleImageLoading();
        if (content != null) {
            GridView grid = content.findViewById(R.id.enrollment_faces);
            if (grid != null) {
                for (int index = 0; index < grid.getChildCount(); index++) {
                    ImageView image = grid.getChildAt(index).findViewById(R.id.face_sample_image);
                    if (image != null) image.setImageDrawable(null);
                }
            }
        }
        faceImages.clear();
        sampleBitmaps.evictAll();
        if (sampleRows != null) sampleRows.notifyDataSetChanged();
        // 已显示的位图由 ImageView/渲染队列持有，交给 GC 回收；未绑定的过时解码结果才可主动 recycle。
    }

    private void cancelSampleImageLoading() {
        main.removeCallbacks(loadVisibleSampleImages);
        synchronized (sampleImageLock) {
            // 发布请求和作废代次共用锁，换人/停页不能漏掉刚准备入队的旧请求。
            sampleDecodeVersion++;
            if (sampleImageCall != null) sampleImageCall.cancel();
            sampleImageCall = null;
        }
        decodingFaces = false;
        sampleImageBatch = null;
        sampleImageLoadingPosition = -1;
    }

    private void loadSampleImages() {
        if (!foreground || destroyed || staffId <= 0 || sampleDevice.isEmpty()) {
            clearFaceImages();
            updateSampleControls();
            return;
        }
        SampleImageBatch previous = sampleImageBatch;
        boolean sameScope = previous != null && sampleScope(previous.employee, previous.personId,
                previous.selection, previous.generation, previous.device) && sampleDevice.equals(previous.device);
        rememberSampleScroll();
        cancelSampleImageLoading();
        // 同一员工、设备上的写回执只改变对应 UID，保留其余缩略图，避免整列闪烁和重复下载。
        for (long uid : sampleBitmaps.snapshot().keySet()) {
            String url = sampleUrls.get(uid);
            if (!sameScope || !containsUid(sampleUids, uid) || url == null || !url.equals(previous.urls.get(uid)))
                sampleBitmaps.remove(uid);
        }
        faceImages.clear();
        final int version = sampleDecodeVersion, selection = selectionVersion;
        final long employee = staffId, generation = sdkGeneration();
        final String device = sampleDevice;
        final List<Long> uids = new ArrayList<>();
        final Map<Long, String> urls = new LinkedHashMap<>(sampleUrls);
        for (int index = 0; index < sampleUids.length(); index++)
            uids.add(sampleUids.optLong(index));
        sampleImageBatch = new SampleImageBatch(employee, currentPersonId(), selection, generation, device,
                version, uids, urls);
        for (long uid : uids) faceImages.add(new FaceImage(uid));
        if (sampleRows != null) sampleRows.notifyDataSetChanged();
        restoreSampleScroll();
        updateSampleControls();
        scheduleVisibleSampleImages();
    }

    private boolean sampleBatchCurrent(SampleImageBatch batch) {
        return foreground && sampleScope(batch.employee, batch.personId, batch.selection, batch.generation, batch.device)
                && batch.version == sampleDecodeVersion;
    }

    private void scheduleVisibleSampleImages() {
        main.removeCallbacks(loadVisibleSampleImages);
        if (!destroyed && foreground && sampleImageBatch != null)
            main.post(loadVisibleSampleImages);
    }

    private void loadNextVisibleSampleImage() {
        SampleImageBatch batch = sampleImageBatch;
        if (batch == null || !sampleBatchCurrent(batch) || sampleImageLoadingPosition >= 0) return;
        GridView grid = content.findViewById(R.id.enrollment_faces);
        if (grid == null || !grid.isShown() || grid.getChildCount() == 0) return;
        int first = Math.max(0, grid.getFirstVisiblePosition());
        int last = Math.min(faceImages.size() - 1, grid.getLastVisiblePosition());
        // 列表包含全部 UID，但只下载可见缩略图；缓存按字节上限淘汰，长列表不持有全部原图。
        for (int index = first; index <= last; index++) {
            FaceImage image = faceImages.get(index);
            if (!image.unavailable && sampleBitmaps.get(image.uid) == null) {
                sampleImageLoadingPosition = index;
                decodingFaces = true;
                enqueueSampleImage(batch, index);
                return;
            }
        }
        if (decodingFaces) {
            decodingFaces = false;
            updateSampleControls();
        }
    }

    private void enqueueSampleImage(SampleImageBatch batch, int index) {
        if (!sampleBatchCurrent(batch) || index >= batch.uids.size()) return;
        String imageUrl = batch.urls.get(batch.uids.get(index));
        HttpUrl url = imageUrl == null || imageUrl.length() > 4096 ? null : HttpUrl.parse(imageUrl);
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()) {
            completeSampleImage(batch, index, null);
            return;
        }
        Call request = sampleHttp.newCall(new Request.Builder().url(url).get().build());
        synchronized (sampleImageLock) {
            if (batch.version != sampleDecodeVersion) {
                request.cancel();
                return;
            }
            sampleImageCall = request;
            try {
                // 图片使用 OkHttp 自身异步队列，一次仅一张，不占业务查询/上传的串行 networkIo。
                request.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException error) {
                        retireSampleImageCall(call);
                        completeSampleImage(batch, index, null);
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        Bitmap bitmap = null;
                        try (Response owned = response) {
                            bitmap = decodeSampleImage(owned, batch.version);
                        } catch (Exception ignored) {
                            // 保留 SDK 的真实样本，界面仍按列表序号展示；缺图不能冒充没有样本。
                        } finally {
                            retireSampleImageCall(call);
                        }
                        completeSampleImage(batch, index, bitmap);
                    }
                });
            } catch (RuntimeException enqueueFailure) {
                sampleImageCall = null;
                request.cancel();
                completeSampleImage(batch, index, null);
            }
        }
    }

    private void retireSampleImageCall(Call call) {
        synchronized (sampleImageLock) {
            if (sampleImageCall == call) sampleImageCall = null;
        }
    }

    private void completeSampleImage(SampleImageBatch batch, int index, Bitmap bitmap) {
        main.post(() -> {
            if (!sampleBatchCurrent(batch) || index >= faceImages.size()) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                return;
            }
            FaceImage sample = faceImages.get(index);
            sample.unavailable = bitmap == null;
            if (bitmap != null) sampleBitmaps.put(sample.uid, bitmap);
            sampleImageLoadingPosition = -1;
            updateSampleControls();
            scheduleVisibleSampleImages();
        });
    }

    private Bitmap decodeSampleImage(Response response, int version) throws IOException {
        if (!response.isSuccessful() || version != sampleDecodeVersion) return null;
        ResponseBody body = response.body();
        if (body == null || body.contentLength() > MAX_SAMPLE_IMAGE_BYTES) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream source = body.byteStream()) {
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (version != sampleDecodeVersion || (long) bytes.size() + count > MAX_SAMPLE_IMAGE_BYTES)
                    return null;
                bytes.write(buffer, 0, count);
            }
        }
        byte[] encoded = bytes.toByteArray();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth / options.inSampleSize, bounds.outHeight / options.inSampleSize) > 480)
            options.inSampleSize *= 2;
        return version == sampleDecodeVersion ? BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options) : null;
    }

    private void updateSampleControls() {
        if (content == null || destroyed) return;
        View refresh = content.findViewById(R.id.enrollment_faces_refresh);
        if (refresh == null) return;
        boolean writable = !writeInProgress() && !queryingFaces;
        refresh.setEnabled(writable && person != null && !personLoading && sdk.isConnected());
        content.findViewById(R.id.enrollment_faces_clear).setEnabled(writable && samplesVerified() && sampleUids.length() > 0);
        TextView sampleTitle = content.findViewById(R.id.enrollment_faces_info);
        sampleTitle.setText("已录入图片");
        sampleTitle.setContentDescription(queryingFaces ? getString(R.string.face_samples_verifying)
                : getString(R.string.face_samples_count, sampleUids.length()));
        TextView empty = content.findViewById(R.id.enrollment_faces_empty);
        empty.setVisibility(faceImages.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(person == null ? "请先选择员工"
                : sampleUids.length() == 0 && samplesVerified()
                  ? "该员工暂无人脸样本" : "连接当前设备后刷新核对样本");
        GridView grid = content.findViewById(R.id.enrollment_faces);
        if (grid != null && sampleRows != null) {
            // 状态和缩略图变化不改变 UID 列表；整表通知会用旧视口覆盖尚未布局的滚动位置。
            for (int index = 0; index < grid.getChildCount(); index++) {
                int position = grid.getFirstVisiblePosition() + index;
                if (position >= 0 && position < faceImages.size())
                    sampleRows.bindTile(grid.getChildAt(index), faceImages.get(position));
            }
        }
    }

    private static final class FaceImage {
        final long uid;
        boolean unavailable;

        FaceImage(long uid) {
            this.uid = uid;
        }
    }

    private static final class SampleImageBatch {
        final long employee, personId, generation;
        final int selection, version;
        final String device;
        final List<Long> uids;
        final Map<Long, String> urls;

        SampleImageBatch(long employee, long personId, int selection, long generation, String device,
                         int version, List<Long> uids, Map<Long, String> urls) {
            this.employee = employee;
            this.selection = selection;
            this.generation = generation;
            this.personId = personId;
            this.device = device;
            this.version = version;
            this.uids = uids;
            this.urls = urls;
        }
    }

    private final class SampleRows extends BaseAdapter {
        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public int getCount() {
            return faceImages.size();
        }

        @Override
        public Object getItem(int position) {
            return faceImages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position >= 0 && position < faceImages.size() ? faceImages.get(position).uid
                    : android.widget.AdapterView.INVALID_ROW_ID;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View tile = convert == null ? getLayoutInflater().inflate(R.layout.item_face_sample, parent, false) : convert;
            bindTile(tile, faceImages.get(position));
            return tile;
        }

        void bindTile(View tile, FaceImage sample) {
            Bitmap bitmap = sampleBitmaps.get(sample.uid);
            ImageView image = tile.findViewById(R.id.face_sample_image);
            // 加载时保留占位，图片就绪后按原始比例展开，避免方形区域产生上下黑边。
            image.setMinimumHeight(bitmap == null ? getResources().getDimensionPixelSize(R.dimen.dp_60) : 0);
            if (bitmap == null) image.setImageDrawable(null);
            else image.setImageBitmap(bitmap);
            TextView missing = tile.findViewById(R.id.face_sample_missing);
            missing.setVisibility(View.GONE);
            if (bitmap == null && !sample.unavailable) scheduleVisibleSampleImages();
            ((TextView) tile.findViewById(R.id.face_sample_uid)).setText("人脸" + FaceSamplePresentation.ordinal(sampleUids, sample.uid));
            View delete = tile.findViewById(R.id.face_sample_delete);
            delete.setEnabled(samplesVerified() && !writeInProgress() && !queryingFaces);
            final long uid = sample.uid;
            final long employee = staffId, personId = currentPersonId(), generation = sdkGeneration();
            final int selection = selectionVersion;
            final String device = sampleDevice;
            delete.setOnClickListener(view -> {
                if (sampleScope(employee, personId, selection, generation, device)) requestDeleteSample(uid);
            });
        }
    }

    private void requestDeleteSample(long uid) {
        if (!foreground || choosingEmployee() || writeInProgress() || queryingFaces || !samplesVerified()
                || (uid > 0 && !containsUid(sampleUids, uid)) || sampleUids.length() == 0) return;
        deleteSample(staffId, uid, sampleDevice, sdkGeneration(), selectionVersion);
    }

    private static boolean containsUid(JSONArray uids, long uid) {
        for (int index = 0; index < uids.length(); index++)
            if (uids.optLong(index) == uid) return true;
        return false;
    }

    private void deleteSample(long employee, long uid, String device, long generation, int selection) {
        boolean verified = samplesVerified();
        if (!application.faceOperations.beginWrite(employee)) return;
        sending = true;
        sendingLabel = uid > 0 ? "正在删除样本" : "正在清空样本";
        personReadVersion++;
        invalidateSampleVerification();
        updateUi();
        final LearningWrite write = new LearningWrite(employee, currentPersonId(), selection, session, generation,
                device, UUID.randomUUID().toString(), verified);
        runIo(() -> {
            try {
                JSONObject current = store.staff(employee);
                if (current == null || current.optLong("person_id") != write.personId || current.optBoolean("face_write_unknown"))
                    throw new IllegalStateException("员工资料或上次操作状态已变化，请先核对");
                JSONObject prepared = store.patchStaff(employee,
                        obj("face_write_unknown", true, "face_write_attempt", write.attempt, "face_write_device", write.device,
                                "face_write_person_id", write.personId));
                main.post(() -> {
                    if (matchesSelection(write) && matchesDevice(write)) person = prepared;
                    if (!matchesSelection(write) || !matchesDevice(write) || !foreground || !sdk.isConnected()) {
                        completeFailure(write, true, "页面或连接已变化，删除尚未发送，请重新核对");
                        return;
                    }
                    HuoyanClient.Callback callback = (code, message, data) -> {
                        if (!matchesDevice(write)) {
                            completeFailure(write, false, "连接已变化，删除结果未知，请先核对原设备样本");
                        } else if (FaceResultPolicy.isAbsentOrDeleted(code)) {
                            completeSampleDeletion(write, uid);
                        } else completeFailure(write, code != HuoyanClient.ERROR_RESULT_UNKNOWN
                                        && code != HuoyanClient.ERROR_CANCELLED,
                                code == HuoyanClient.ERROR_RESULT_UNKNOWN || code == HuoyanClient.ERROR_CANCELLED
                                        ? "删除结果未知，请刷新核对样本后再操作" : "样本未删除：" + message);
                    };
                    try {
                        if (uid > 0) sdk.forgetByUid(uid, callback);
                        else sdk.forget(write.personId, callback);
                    } catch (RuntimeException error) {
                        completeFailure(write, false, "删除结果待核对：" + failure(error));
                    }
                });
            } catch (Exception error) {
                main.post(() -> completeFailure(write, true, "尚未发送删除：" + failure(error)));
            }
        });
    }

    private void completeSampleDeletion(LearningWrite write, long deletedUid) {
        runIo(() -> {
            try {
                JSONObject current = store.staff(write.staffId);
                if (current == null || current.optLong("person_id") != write.personId || !matchesDevice(write)
                        || !write.attempt.equals(current.optString("face_write_attempt"))) {
                    main.post(() -> releaseWrite(write, null, "员工或设备已变化，请刷新核对删除结果", false));
                    return;
                }
                JSONArray retained = new JSONArray(), old = current.optJSONArray("face_uids");
                if (deletedUid > 0 && old != null)
                    for (int index = 0; index < old.length(); index++)
                        if (old.optLong(index) != deletedUid) retained.put(old.optLong(index));
                JSONObject updated = store.patchStaff(write.staffId, obj("face_uids", retained,
                        "face_registered", retained.length() > 0,
                        "face_uid", retained.length() == 0 ? 0 : retained.optLong(retained.length() - 1),
                        "face_write_unknown", false, "face_write_attempt", "", "face_write_device", "", "face_write_person_id", 0));
                String message = deletedUid > 0 ? "人脸样本已删除" : "该员工的人脸样本已清空";
                main.post(() -> {
                    if (matchesSelection(write) && matchesDevice(write)) {
                        sampleUids = retained;
                        if (deletedUid > 0) sampleUrls.remove(deletedUid);
                        else sampleUrls.clear();
                    }
                    releaseWrite(write, updated, message, true);
                });
            } catch (Exception error) {
                main.post(() -> releaseWrite(write, null, "删除回执未能保存，请刷新核对：" + failure(error), false));
            }
        });
    }

    @Override
    protected void onStop() {
        rememberEmployeeScroll();
        closeLighting();
        if (operationLoading != null) operationLoading.dismiss();
        writeFeedback = null;
        speech.pause();
        application.removeStaffChangeListener(staffChanged);
        main.removeCallbacks(searchChanged);
        personReadVersion++;
        listReadVersion++;
        invalidateSamples(false);
        observedSampleScope = "";
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        closeLighting();
        speech.close();
        main.removeCallbacks(searchChanged);
        invalidateSamples(false);
        super.onDestroy();
    }

    @Override
    protected void onDataLoaded() {
        preparePreview();
        updateUi();
    }

    @Override
    protected void onSdkStateChanged() {
        observeDetectionScope();
        updateUi();
        observeSampleScope();
    }

    @Override
    protected void onCameraFrame(CameraSource.Frame frame) {
        if (staffId <= 0 || person == null) return;
        FaceEnrollmentPreviewLayout preview = content.findViewById(R.id.enrollment_preview);
        preview.setFrameSize(frame.width, frame.height);
        boolean needsUpdate = !cameraError.isEmpty() || !previewReceivedFrame;
        previewReceivedFrame = true;
        cameraError = "";
        observeDetectionScope();
        detectPreview(frame);
        if (needsUpdate) updateUi();
    }

    @Override
    protected void onCameraError() {
        notice(cameraError);
        previewReceivedFrame = false;
        invalidateFaceDetection();
        cancelPreflight("摄像头已中断，人脸学习尚未发送，请重新采集");
        updateUi();
    }

    private boolean fresh(CameraSource.Frame frame) {
        if (frame == null) return false;
        long age = System.nanoTime() - frame.receivedAtNanos;
        return age >= 0 && age <= FRAME_MAX_AGE_NANOS;
    }

    @Override
    protected void stopCamera() {
        invalidateFaceDetection();
        cancelPreflight("页面或摄像头已变化，人脸学习尚未发送，请重新采集");
        previewReceivedFrame = false;
        super.stopCamera();
        closeLighting();
    }

    private void updateLighting() {
        boolean on = resumed && foreground && !destroyed && !choosingEmployee()
                && staffId > 0 && person != null && currentPersonId() > 0 && camera != null
                && sdkReady() && cameraError.isEmpty()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!on) {
            closeLighting();
            return;
        }
        LightConfig config;
        try {
            config = LightConfig.read(prefs);
        } catch (IllegalArgumentException invalid) {
            closeLighting();
            String message = "灯光配置无效：" + invalid.getMessage();
            if (!message.equals(lightingConfigError)) notice(message);
            lightingConfigError = message;
            return;
        }
        lightingConfigError = "";
        if (lighting == null) lighting = LightController.getInstance().acquire((enabled, success, message) -> {
            if (resumed && foreground && !destroyed && !success) notice(message);
        });
        if (config.equals(requestedLightConfig)) return;
        // 预览开始就补光，不等待首帧或检测到人脸；学习回执和列表更新不重复开关灯。
        requestedLightConfig = config;
        lighting.set(config, true);
    }

    private void closeLighting() {
        if (lighting != null) lighting.close();
        lighting = null;
        requestedLightConfig = null;
    }

    private void observeDetectionScope() {
        String scope = staffId + ":" + currentPersonId() + ":" + selectionVersion + ":" + session + ":" + cameraGeneration
                + ":" + sdkGeneration() + ":" + currentDevice() + ":" + sdkReady();
        if (scope.equals(observedDetectionScope)) return;
        observedDetectionScope = scope;
        invalidateFaceDetection();
        cancelPreflight("页面或连接已变化，人脸学习尚未发送，请重新采集");
    }

    private void clearFaceDetection() {
        main.removeCallbacks(expireDetection);
        detectedFaces = -1;
        detectedFrame = null;
        if (overlay != null) overlay.show(null, 640, 480, false, false);
        if (speech != null) speech.clearStatus();
    }

    private void invalidateFaceDetection() {
        detectionScope++;
        clearFaceDetection();
        detectionMessage = "";
        nextDetectionAt = 0;
        // 旧请求仍占单在途位置，等其回调释放；切页/重连不能把它当作已执行完。
    }

    private boolean singleFaceCurrent() {
        if (detectedFaces != 1 || detectedFrame == null) return false;
        long age = System.nanoTime() - detectedFrame.receivedAtNanos;
        return age >= 0 && age <= DETECTION_MAX_AGE_NANOS;
    }

    private void detectPreview(CameraSource.Frame frame) {
        if (!resumed || !foreground || destroyed || choosingEmployee() || personLoading || person == null
                || writeInProgress() || person.optBoolean("face_write_unknown") || !sdkReady()
                || !fresh(frame) || !cameraError.isEmpty() || detecting || System.nanoTime() < nextDetectionAt)
            return;
        final int expectedScope = detectionScope, request = ++detectionRequest;
        // 预览与送检 RGB 已由 CameraImageOptions 统一镜像，叠加层不再翻转。
        final boolean mirror = false;
        detecting = true;
        updateUi();
        try {
            sdk.detectFace(frame.rgb, frame.width, frame.height, FaceDetectionResult.MAX_FACES, (code, message, data) -> {
                if (request != detectionRequest) return;
                detecting = false;
                if (!resumed || !foreground || destroyed || expectedScope != detectionScope
                        || (writeInProgress() && pendingPreflightFrame == null) || !sdkReady()) {
                    if (pendingPreflightFrame != null)
                        cancelPreflight("页面或连接已变化，人脸学习尚未发送，请重新采集");
                    return;
                }
                if (code != 0) {
                    failFaceDetection("人脸检测未完成：" + message);
                    return;
                }
                try {
                    FaceDetectionResult result = FaceDetectionResult.parse(data);
                    long age = System.nanoTime() - frame.receivedAtNanos;
                    if (age < 0 || age > DETECTION_MAX_AGE_NANOS) {
                        failFaceDetection("人脸检测画面已过期，请正视镜头后重试");
                        return;
                    }
                    if (pendingPreflightFrame != null) {
                        if (result.isSingle()) {
                            startPreflight();
                            return;
                        }
                        cancelPreflight(result.count > 1 ? getString(R.string.face_enrollment_multiple)
                                : getString(R.string.face_enrollment_no_face));
                    }
                    detectedFrame = frame;
                    detectedFaces = result.count;
                    detectionMessage = "";
                    if (overlay != null)
                        overlay.show(result.boxes, frame.width, frame.height, mirror, result.count > 1);
                    updateDetectionVoice(result.count);
                    main.removeCallbacks(expireDetection);
                    main.postDelayed(expireDetection, Math.max(1, (DETECTION_MAX_AGE_NANOS - age) / 1_000_000L + 1));
                    updateUi();
                } catch (IllegalArgumentException error) {
                    failFaceDetection("人脸检测结果无效，请重新采集");
                }
            });
        } catch (RuntimeException error) {
            detecting = false;
            if (expectedScope == detectionScope)
                failFaceDetection("人脸检测未完成：" + failure(error));
        }
    }

    private void updateDetectionVoice(int count) {
        if (count > 1) speech.update("enrollment-multiple", "检测到多人入镜", 0, 5000);
        else speech.clearStatus();
    }

    private void failFaceDetection(String message) {
        cancelPreflight(message);
        clearFaceDetection();
        reportDetectionFailure(message);
        nextDetectionAt = System.nanoTime() + DETECTION_RETRY_NANOS;
        updateUi();
    }

    private void reportDetectionFailure(String message) {
        if (resumed && foreground && !destroyed && !message.equals(detectionMessage))
            notice(message);
        detectionMessage = message;
    }

    private void updateUi() {
        updateLighting();
        if (destroyed || !foreground || previewStatus == null) return;
        staffName.setText(person == null ? "未选择员工" : person.optString("staff_no", "未填写") + "-" + person.optString("name"));
        boolean writing = application.faceOperations.isWriting(staffId);
        boolean unknown = person != null && person.optBoolean("face_write_unknown");
        boolean enabled = resumed && !choosingEmployee() && !personLoading && person != null && staffId > 0 && currentPersonId() > 0 && !sending && !writing && !unknown
                && sdkReady() && fresh(latestFrame) && cameraError.isEmpty() && singleFaceCurrent();
        capture.setEnabled(enabled);
        capture.setAlpha(enabled ? 1f : .5f);
        capture.setText(R.string.face_enrollment_capture);
        cameraSettings.setEnabled(!sending && !writing);
        cameraSettings.setAlpha(cameraSettings.isEnabled() ? 1f : .5f);
        TextView chooseStaff = findViewById(R.id.enrollment_choose_staff);
        if (chooseStaff != null) {
            chooseStaff.setEnabled(!sending && !writing);
            chooseStaff.setAlpha(chooseStaff.isEnabled() ? 1f : .45f);
            chooseStaff.setText(selectionExpanded ? "关闭选择" : "切换人员");
        }
        content.findViewById(R.id.enrollment_staff_list).setEnabled(!pageLoading
                && committedStaff == visibleStaff && !sending && !writing);
        content.findViewById(R.id.enrollment_search).setEnabled(!sending && !writing);
        View previous = content.findViewById(R.id.enrollment_previous);
        previous.setEnabled(!pageLoading && !sending && !writing && staffPage > 1);
        previous.setAlpha(previous.isEnabled() ? 1f : .45f);
        View next = content.findViewById(R.id.enrollment_next);
        next.setEnabled(!pageLoading && !sending && !writing
                && (pageError || staffData != null && staffPage < staffData.totalPages));
        next.setAlpha(next.isEnabled() ? 1f : .45f);
        TextView placeholder = content.findViewById(R.id.enrollment_preview_placeholder);
        placeholder.setVisibility(person == null || !previewReceivedFrame ? View.VISIBLE : View.GONE);
        placeholder.setText(person == null
                ? "请先从员工列表选择员工" : "等待摄像头画面");
        updateSampleControls();
        updatePreviewStatus(enabled, writing);
        updateOperationLoading();
    }

    private void updateOperationLoading() {
        if (operationLoading == null) operationLoading = new LoadingDialog(this);
        boolean writing = staffId > 0 && application.faceOperations.isWriting(staffId);
        if (foreground && !destroyed && (sending || writing || personLoading || queryingFaces)) {
            operationLoading.show(sending || writing ? sendingLabel : queryingFaces ? "正在读取人脸样本" : "正在读取员工资料");
            return;
        }
        if (foreground && !destroyed && pageLoading && choosingEmployee()) {
            operationLoading.showReadOnly("正在读取员工列表");
            return;
        }
        operationLoading.dismiss();
        WriteFeedback feedback = writeFeedback;
        writeFeedback = null;
        if (feedback != null && foreground && !isFinishing()
                && matchesSelection(feedback.write) && matchesDevice(feedback.write)) {
            if (feedback.success) toast(feedback.message);
            else notice(feedback.message);
        }
    }

    private void updatePreviewStatus(boolean canEnroll, boolean writing) {
        int text = R.string.face_enrollment_status_unavailable;
        int color = R.color.mc_enrollment_status_idle;
        if (sending || writing) {
            text = R.string.face_enrollment_status_processing;
            color = R.color.mc_enrollment_status_busy;
        } else if (person == null || choosingEmployee()) {
            text = R.string.face_enrollment_status_select_employee;
        } else if (!previewReceivedFrame || !fresh(latestFrame) || !cameraError.isEmpty()) {
            text = R.string.face_enrollment_status_waiting;
        } else if (detectedFaces > 1) {
            text = R.string.face_enrollment_status_multiple;
            color = R.color.mc_enrollment_status_danger;
        } else if (detectedFaces == 0) {
            text = R.string.face_enrollment_status_no_face;
            color = R.color.mc_enrollment_status_warning;
        } else if (canEnroll) {
            text = R.string.face_enrollment_status_ready;
            color = R.color.mc_enrollment_status_ready;
        } else if (detecting) {
            text = R.string.face_enrollment_status_detecting;
            color = R.color.mc_enrollment_status_busy;
        }
        previewStatus.setText(text);
        previewStatus.setBackgroundTintList(ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(this, color)));
    }

    private void captureFace() {
        CameraSource.Frame frame = latestFrame;
        if (!resumed || !foreground || destroyed || choosingEmployee() || sending || personLoading || person == null || staffId <= 0 || currentPersonId() <= 0
                || !sdkReady() || !fresh(frame) || !cameraError.isEmpty() || person.optBoolean("face_write_unknown")
                || !singleFaceCurrent()) {
            updateUi();
            return;
        }
        final long targetStaffId = staffId;
        boolean verified = samplesVerified();
        if (!application.faceOperations.beginWrite(targetStaffId)) {
            updateUi();
            return;
        }
        personReadVersion++;
        sending = true;
        sendingLabel = "正在确认人脸";
        final LearningWrite write = new LearningWrite(targetStaffId, currentPersonId(), selectionVersion, session,
                sdkGeneration(), sdkSession.status().optString("device_id"), UUID.randomUUID().toString(), verified);
        pendingPreflight = write;
        pendingPreflightFrame = frame;
        // 预览检测不反复禁用按钮；点击时冻结画面，等已有请求返回后再复检，保持单图在途。
        clearFaceDetection();
        updateUi();
        if (!detecting) startPreflight();
    }

    private void startPreflight() {
        final LearningWrite write = pendingPreflight;
        final CameraSource.Frame frame = pendingPreflightFrame;
        if (write == null || frame == null || detecting) return;
        if (!resumed || !foreground || destroyed || !matchesSelection(write) || session != write.session
                || !matchesDevice(write) || !sdkReady() || !cameraError.isEmpty()) {
            cancelPreflight("页面或连接已变化，人脸学习尚未发送，请重新采集");
            return;
        }
        if (!fresh(frame)) {
            cancelPreflight("采集画面已过期，人脸学习尚未发送，请重新采集");
            return;
        }
        pendingPreflightFrame = null;
        final int expectedScope = detectionScope, request = ++detectionRequest;
        // 预览与送检 RGB 已由 CameraImageOptions 统一镜像，叠加层不再翻转。
        final boolean mirror = false;
        detecting = true;
        clearFaceDetection();
        updateUi();
        try {
            // 点击后固定这张完整 RGB 帧，复检和 register 必须使用同一张图，不能改读 latestFrame。
            sdk.detectFace(frame.rgb, frame.width, frame.height, FaceDetectionResult.MAX_FACES, (code, message, data) -> {
                if (request != detectionRequest) return;
                detecting = false;
                if (pendingPreflight != write) return;
                if (expectedScope != detectionScope || !matchesSelection(write) || !resumed || !foreground
                        || session != write.session || !matchesDevice(write) || !sdkReady()) {
                    cancelPreflight("页面或连接已变化，人脸学习尚未发送，请重新采集");
                    return;
                }
                if (!fresh(frame)) {
                    cancelPreflight("采集画面已过期，人脸学习尚未发送，请重新采集");
                    return;
                }
                if (code != 0) {
                    cancelPreflight("人脸检测未完成：" + message);
                    failFaceDetection("人脸检测未完成：" + message);
                    return;
                }
                try {
                    FaceDetectionResult result = FaceDetectionResult.parse(data);
                    if (!result.isSingle()) {
                        cancelPreflight(result.count > 1 ? getString(R.string.face_enrollment_multiple)
                                : getString(R.string.face_enrollment_no_face));
                        if (overlay != null)
                            overlay.show(result.boxes, frame.width, frame.height, mirror, result.count > 1);
                        updateDetectionVoice(result.count);
                        detectedFaces = result.count;
                        detectedFrame = frame;
                        main.postDelayed(expireDetection, 1500);
                        updateUi();
                        return;
                    }
                    pendingPreflight = null;
                    sendingLabel = "正在学习";
                    invalidateSampleVerification();
                    updateUi();
                    submitLearning(write, frame);
                } catch (IllegalArgumentException error) {
                    cancelPreflight("人脸检测结果无效，人脸学习尚未发送，请重新采集");
                    failFaceDetection("人脸检测结果无效，请重新采集");
                }
            });
        } catch (RuntimeException error) {
            detecting = false;
            cancelPreflight("人脸检测未完成：" + failure(error));
            failFaceDetection("人脸检测未完成：" + failure(error));
        }
    }

    private void cancelPreflight(String message) {
        LearningWrite write = pendingPreflight;
        if (write == null) return;
        pendingPreflight = null;
        pendingPreflightFrame = null;
        application.faceOperations.finishWrite(write.staffId);
        sending = false;
        clearFaceDetection();
        reportDetectionFailure(message);
        updateUi();
    }

    private void submitLearning(LearningWrite write, CameraSource.Frame frame) {
        final long targetStaffId = write.staffId;
        // 提交前留下批次标记；进程中断或换连接时，不能把未知写入自动当成失败重试。
        // 同时冻结本地主键与 SDK 人员标识，档案落盘和 SDK 调用各使用自己的编号。
        runIo(() -> {
            try {
                JSONObject current = store.staff(targetStaffId);
                if (current == null || current.optLong("person_id") != write.personId)
                    throw new IllegalStateException("员工档案已删除或身份已变化，请刷新后操作");
                if (current.optBoolean("face_write_unknown"))
                    throw new IllegalStateException("存在待核对的人脸学习，请先核对 SDK 人脸状态");
                JSONObject prepared = store.patchStaff(targetStaffId,
                        obj("face_write_unknown", true, "face_write_attempt", write.attempt, "face_write_device", write.device,
                                "face_write_person_id", write.personId));
                main.post(() -> {
                    if (matchesSelection(write) && matchesDevice(write)) person = prepared;
                    if (!matchesSelection(write) || !resumed || !foreground || session != write.session
                            || !matchesDevice(write) || !sdkReady()) {
                        completeFailure(write, true, "页面或连接已变化，人脸学习尚未发送，请重新采集");
                        return;
                    }
                    if (!fresh(frame)) {
                        completeFailure(write, true, "采集画面已过期，人脸学习尚未发送，请重新采集");
                        return;
                    }
                    try {
                        sdk.register(frame.rgb, frame.width, frame.height, write.personId, (code, message, data) -> {
                            FaceResultPolicy.Registration outcome = matchesDevice(write)
                                    ? FaceResultPolicy.registration(code, data) : FaceResultPolicy.Registration.UNKNOWN;
                            if (outcome == FaceResultPolicy.Registration.SAVED
                                    || outcome == FaceResultPolicy.Registration.NO_FACE)
                                completeLearning(write, data.optLong("uid"), data.optString("image_url"));
                            else
                                completeFailure(write, outcome == FaceResultPolicy.Registration.REJECTED,
                                        outcome == FaceResultPolicy.Registration.UNKNOWN
                                                ? "学习结果未知，请先核对 SDK 人脸状态，避免重复学习"
                                                : "学习未完成：" + message + "。可重新采集");
                        });
                    } catch (RuntimeException error) {
                        // 客户端同步异常无法证明底层尚未发送，保留标记等待 SDK 核对。
                        completeFailure(write, false, "学习结果待核对：" + failure(error));
                    }
                });
            } catch (Exception error) {
                main.post(() -> completeFailure(write, true, "未发送人脸学习：" + failure(error)));
            }
        });
    }

    private boolean matchesSelection(LearningWrite write) {
        return !destroyed && staffId == write.staffId && selectionVersion == write.selection
                && currentPersonId() == write.personId;
    }

    private boolean matchesDevice(LearningWrite write) {
        return sdkGeneration() == write.generation && write.device.equals(sdkSession.status().optString("device_id"));
    }

    private void completeLearning(LearningWrite write, long uid, String imageUrl) {
        runIo(() -> {
            try {
                JSONObject current = store.staff(write.staffId);
                // 回执可能在存储队列中等待；保存时再次核对连接，不能把旧设备样本记入新设备。
                if (!matchesDevice(write)) {
                    main.post(() -> releaseWrite(write, null, "连接已改变，请先核对原 SDK 的人脸学习结果", false));
                    return;
                }
                // 晚到的回执只处理自己的批次，不能覆盖后来发起的人脸操作。
                if (current == null || current.optLong("person_id") != write.personId
                        || !write.attempt.equals(current.optString("face_write_attempt"))) {
                    main.post(() -> releaseWrite(write, null, "员工状态已变化，请重新核对 SDK 人脸状态", false));
                    return;
                }
                JSONObject changes = obj("face_write_unknown", false, "face_write_attempt", "", "face_write_device", "", "face_write_person_id", 0);
                if (uid > 0) {
                    put(changes, "face_registered", true);
                    put(changes, "face_uid", uid);
                    JSONArray uids = current.optJSONArray("face_uids");
                    if (uids == null) uids = new JSONArray();
                    uids.put(uid);
                    put(changes, "face_uids", uids);
                }
                JSONObject updated = store.patchStaff(write.staffId, changes);
                main.post(() -> {
                    if (uid > 0 && matchesSelection(write) && matchesDevice(write)) {
                        if (!write.device.equals(sampleDevice)) {
                            sampleUids = new JSONArray();
                            sampleUrls.clear();
                        }
                        if (!containsUid(sampleUids, uid)) sampleUids.put(uid);
                        sampleDevice = write.device;
                        sampleUrls.put(uid, imageUrl);
                    }
                    releaseWrite(write, updated, uid > 0 ? "人脸学习已保存"
                            : "未检测到可学习的人脸，请正视镜头后重新采集", uid > 0);
                });
            } catch (Exception error) {
                main.post(() -> releaseWrite(write, null,
                        "学习回执未能保存，请先核对 SDK 人脸状态：" + failure(error), false));
            }
        });
    }

    private void completeFailure(LearningWrite write, boolean knownFailure, String message) {
        runIo(() -> {
            try {
                JSONObject current = store.staff(write.staffId);
                if (knownFailure && current != null && current.optLong("person_id") == write.personId
                        && write.attempt.equals(current.optString("face_write_attempt")))
                    current = store.patchStaff(write.staffId, obj("face_write_unknown", false, "face_write_attempt", "", "face_write_device", "", "face_write_person_id", 0));
                JSONObject updated = current;
                main.post(() -> releaseWrite(write, updated, message, false));
            } catch (Exception error) {
                main.post(() -> releaseWrite(write, null,
                        "学习状态未能保存，请先核对 SDK 人脸状态：" + failure(error), false));
            }
        });
    }

    private void releaseWrite(LearningWrite write, JSONObject updated, String message, boolean success) {
        application.faceOperations.finishWrite(write.staffId);
        if (!destroyed && staffId == write.staffId && selectionVersion == write.selection) {
            // 档案读取失败或已删除时也释放页面占用，但不把旧身份的回执重新挂到当前资料。
            sending = false;
            if (matchesSelection(write) && matchesDevice(write)) {
                if (updated != null) person = updated;
                boolean known = updated != null && !updated.optBoolean("face_write_unknown");
                boolean completeSnapshot = known && write.verifiedSamples && write.device.equals(sampleDevice);
                if (completeSnapshot) {
                    verifiedGeneration = write.generation;
                    verifiedPersonId = write.personId;
                    verifiedVersion = application.faceOperations.version(write.staffId);
                }
                // 已核对的快照按明确回执增删；缺少完整快照或新图地址时只补查一次。
                needsSampleVerification = known && (!completeSnapshot || success && hasMissingSampleUrls());
                // 后台已清理图片视图，保留未观察状态，回前台后重新恢复样本。
                if (foreground) {
                    observedSampleScope = currentSampleScope();
                    observedReady = sdkReady();
                }
                if (success) {
                    loadSampleImages();
                    setResult(RESULT_OK);
                }
                if (foreground) writeFeedback = new WriteFeedback(write, message, success);
                if (updated != null) {
                    visibleStaff = new ArrayList<>(visibleStaff);
                    for (int index = 0; index < visibleStaff.size(); index++)
                        if (visibleStaff.get(index).optLong("id") == write.staffId) visibleStaff.set(index, updated);
                    refreshVisibleEmployees();
                }
                observeSampleScope();
                updateUi();
            } else {
                // 切设备后也立即解除当前页禁用；原设备未知批次仍留存，不能在新设备上误清。
                updateUi();
                observeSampleScope();
            }
        }
        // 旧页面继续收尾存储及共享锁；只有当前员工和设备的回执可更新当前界面。
        // 本页已应用回执，不再接自己的广播重读档案；其他页面与重建后的新页面仍正常刷新。
        notifyingStaffChange = true;
        try {
            application.notifyStaffChanged();
        } finally {
            notifyingStaffChange = false;
        }
    }

    private boolean hasMissingSampleUrls() {
        for (int index = 0; index < sampleUids.length(); index++)
            if (HttpUrl.parse(sampleUrls.getOrDefault(sampleUids.optLong(index), "")) == null) return true;
        return false;
    }

    private void refreshVisibleEmployees() {
        if (content == null || enrollmentRows == null) return;
        showStaffPage();
    }

    private static final class WriteFeedback {
        final LearningWrite write;
        final String message;
        final boolean success;

        WriteFeedback(LearningWrite write, String message, boolean success) {
            this.write = write;
            this.message = message;
            this.success = success;
        }
    }

    private static final class LearningWrite {
        final long staffId, personId, generation;
        final int selection, session;
        final String device, attempt;
        final boolean verifiedSamples;

        LearningWrite(long staffId, long personId, int selection, int session, long generation, String device, String attempt, boolean verifiedSamples) {
            this.staffId = staffId;
            this.selection = selection;
            this.session = session;
            this.personId = personId;
            this.generation = generation;
            this.device = device;
            this.attempt = attempt;
            this.verifiedSamples = verifiedSamples;
        }
    }
}
