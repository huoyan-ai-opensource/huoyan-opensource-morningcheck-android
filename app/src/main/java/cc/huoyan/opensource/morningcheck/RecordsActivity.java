package cc.huoyan.opensource.morningcheck;

import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.domain.RecordDateRange;

/**
 * 晨检记录列表保留筛选与分页，单条详情由独立页面负责。
 */
public final class RecordsActivity extends RecordSupportActivity {
    public static final String EXTRA_DEPARTMENT_ID = "filter_department_id";
    public static final String EXTRA_TODAY_ABNORMAL = "filter_today_abnormal";

    private final Runnable recordsChanged = () -> reload(false, null);
    private final Runnable searchChanged = () -> reload(false, null);
    private final Runnable departmentsChanged = () -> {
        refreshDepartmentOptions();
        reload(false, null);
    };
    private int recordPage = 1, recordFilter, readVersion, departmentReadVersion;
    private String recordQuery = "", loadedQuery = "";
    private long departmentId, loadedDepartmentId;
    private boolean pageLoading;
    private boolean pageError;
    private int loadedFilter;
    private AppStore.DataPage recordData;
    private List<JSONObject> departmentOptions;
    private ListFilterUi.DepartmentFilter departmentFilter;
    private RecordDateFilterUi dateFilter;
    private RecordDateRange dateRange = RecordDateRange.all();
    private RecordDateRange.Bounds loadedDates;
    private Bundle dateFilterState;
    private PaginationUi pagination;
    private final RecordRows rowsAdapter = new RecordRows();
    private RecyclerView recordList;
    private List<JSONObject> submittedRows;
    private boolean submittingRows, appendError, restoreScroll;
    private String scrollId = "";
    private int scrollPosition, scrollOffset, rowsVersion;

    @Override
    protected void setup(Bundle state) {
        records = new ArrayList<>();
        departmentId = Math.max(-1, getIntent().getLongExtra(EXTRA_DEPARTMENT_ID, 0));
        if (getIntent().getBooleanExtra(EXTRA_TODAY_ABNORMAL, false)) {
            recordFilter = 2;
            dateRange = RecordDateRange.today();
        }
        if (state != null) {
            recordPage = Math.max(1, state.getInt("record_page", 1));
            recordFilter = Math.max(0, Math.min(2, state.getInt("record_filter", 0)));
            recordQuery = state.getString("record_query", "");
            departmentId = Math.max(-1, state.getLong("record_department_id", departmentId));
            // 首页入口只提供首次默认值；旋转和重建后保留用户已经修改的筛选。
            dateRange = RecordDateRange.deserialize(state.getString("record_date_range", dateRange.serialize()));
            dateFilterState = state.getBundle("record_date_filter_ui");
            scrollId = state.getString("record_scroll_id", "");
            scrollPosition = state.getInt("record_scroll_position", 0);
            scrollOffset = state.getInt("record_scroll_offset", 0);
            restoreScroll = true;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ensureAppFontCurrent()) return;
        application.addRecordChangeListener(recordsChanged);
        application.addDepartmentChangeListener(departmentsChanged);
        refreshDepartmentOptions();
    }

    @Override
    protected void onStop() {
        saveScroll();
        if (departmentFilter != null) departmentFilter.dismiss();
        if (dateFilter != null) {
            // Android 9 起停止早于保存状态；只在系统重建时保留未确认草稿，普通离页关闭窗口。
            dateFilterState = isChangingConfigurations() ? dateFilter.saveState() : null;
            dateFilter.dismiss();
        }
        application.removeRecordChangeListener(recordsChanged);
        application.removeDepartmentChangeListener(departmentsChanged);
        departmentReadVersion++;
        readVersion++;
        pageLoading = false;
        main.removeCallbacks(searchChanged);
        if (listLoading != null) listLoading.dismiss();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        saveScroll();
        state.putInt("record_page", recordPage);
        state.putInt("record_filter", recordFilter);
        state.putString("record_query", recordQuery);
        state.putLong("record_department_id", departmentId);
        state.putString("record_date_range", dateRange.serialize());
        state.putString("record_scroll_id", scrollId);
        state.putInt("record_scroll_position", scrollPosition);
        state.putInt("record_scroll_offset", scrollOffset);
        state.putBundle("record_date_filter_ui", dateFilterState != null ? dateFilterState
                : dateFilter == null ? null : dateFilter.saveState());
        super.onSaveInstanceState(state);
    }

    @Override
    protected String pageKey() {
        return "records";
    }

    @Override
    protected int activityLayout() {
        return R.layout.activity_records;
    }

    @Override
    protected void beforeRender() {
        saveScroll();
        if (recordList != null) recordList.setAdapter(null);
        recordList = null;
        submittedRows = null;
        rowsVersion++;
    }

    @Override
    protected void bindPage() {
        if (departmentFilter != null) departmentFilter.dismiss();
        if (dateFilter != null) {
            dateFilterState = dateFilter.saveState();
            dateFilter.dismiss();
        }
        View body = content;
        ListFilterUi.bindTable(body, R.id.records_table_content, R.id.records_table_header, R.id.records_empty, 0);
        recordList = body.findViewById(R.id.records_list);
        BusinessListUi.bind(recordList);
        recordList.setAdapter(rowsAdapter);
        EditText search = body.findViewById(R.id.records_search);
        search.setText(recordQuery);
        search.addTextChangedListener(watcher(() -> {
            String query = search.getText().toString().trim();
            if (recordQuery.equals(query)) return;
            recordQuery = query;
            recordPage = 1;
            readVersion++;
            recordData = null;
            records = new ArrayList<>();
            resetScroll();
            pageLoading = true;
            showRecordPage();
            main.removeCallbacks(searchChanged);
            main.postDelayed(searchChanged, 250);
        }));
        Spinner filter = body.findViewById(R.id.records_filter);
        filter.setAdapter(Ui.filterAdapter(this, java.util.Arrays.asList(getResources().getStringArray(R.array.list_filter_record_results)), "结果"));
        filter.setSelection(recordFilter, false);
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> a) {
            }

            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (recordFilter == pos) return;
                recordFilter = pos;
                resetFilters();
            }
        });
        departmentFilter = new ListFilterUi.DepartmentFilter(body.findViewById(R.id.records_department_filter),
                departmentId, id -> {
            if (id == departmentId) return;
            departmentId = id;
            resetFilters();
        });
        if (departmentOptions != null) departmentFilter.replace(departmentOptions);
        dateFilter = new RecordDateFilterUi(this, body.findViewById(R.id.records_date_filter), dateRange, range -> {
            if (dateRange.equals(range)) return;
            dateRange = range;
            dateFilter.replace(range);
            resetFilters();
        });
        dateFilter.restoreState(dateFilterState);
        dateFilterState = null;
        if (fromDepartment()) {
            ((TextView) findViewById(R.id.header_back_label)).setText(R.string.list_filter_back_department);
            findViewById(R.id.header_home).setContentDescription(getString(R.string.list_filter_back_department));
            findViewById(R.id.header_home).setOnClickListener(v -> finish());
        }
        pagination = new PaginationUi(this, body, R.id.records_first, R.id.records_previous,
                R.id.records_page_info, R.id.records_page_input, R.id.records_page_jump, R.id.records_next, R.id.records_last,
                page -> {
                    recordPage = page;
                    reload(false, null);
                }, this::retryPage);
        body.findViewById(R.id.records_load_more).setOnClickListener(v -> loadMore());
        recordList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView view, int dx, int dy) {
                LinearLayoutManager manager = (LinearLayoutManager) view.getLayoutManager();
                if (dy > 0 && manager != null && manager.findLastVisibleItemPosition() >= rowsAdapter.getItemCount() - 5)
                    loadMore();
            }
        });
        showRecordPage();
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> reload(recover, done));
            return;
        }
        main.removeCallbacks(searchChanged);
        readRecordPage(false, recordPage, done);
    }

    private void readRecordPage(boolean append, int requestedPage, Runnable done) {
        int version = ++readVersion, filter = recordFilter;
        String query = recordQuery;
        long department = departmentId;
        final RecordDateRange.Bounds dates;
        try {
            dates = dateRange.resolvedAt(System.currentTimeMillis(), TimeZone.getDefault());
        } catch (IllegalArgumentException invalid) {
            readVersion++;
            recordData = null;
            records = new ArrayList<>();
            pageLoading = false;
            pageError = true;
            showRecordPage();
            if (foreground) notice(invalid.getMessage());
            return;
        }
        if (dateFilter != null) dateFilter.refreshLabel();
        if (loadedFilter != filter || !loadedQuery.equals(query) || loadedDepartmentId != department
                || !dates.equals(loadedDates)) {
            recordData = null;
            records = new ArrayList<>();
            append = false;
        }
        final boolean adding = append;
        if (!adding) saveScroll();
        pageError = false;
        appendError = false;
        pageLoading = true;
        showRecordPage();
        runIo(() -> {
            try {
                AppStore.DataPage data = adding
                        ? store.recordsPage(query, filter, department, dates, requestedPage, 24)
                        : store.recordsWindow(query, filter, department, dates, requestedPage, 24);
                main.post(() -> {
                    if (destroyed || version != readVersion) return;
                    recordData = data;
                    loadedFilter = filter;
                    loadedQuery = query;
                    loadedDepartmentId = department;
                    loadedDates = dates;
                    // 追加只读新页并按 ID 合并；页码随成功回执推进，失败重试仍读取同一边界。
                    Map<String, JSONObject> merged = new LinkedHashMap<>();
                    if (adding) for (JSONObject item : records) merged.put(item.optString("id"), item);
                    for (JSONObject item : data.rows) merged.put(item.optString("id"), item);
                    records = new ArrayList<>(merged.values());
                    recordPage = data.page;
                    pageLoading = false;
                    loaded = true;
                    if (!foreground) return;
                    showRecordPage();
                    if (done != null) done.run();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || version != readVersion) return;
                    pageLoading = false;
                    pageError = true;
                    appendError = adding;
                    showRecordPage();
                    notice("记录读取失败：" + failure(error));
                });
            }
        });
    }

    private void resetFilters() {
        recordPage = 1;
        recordData = null;
        records = new ArrayList<>();
        resetScroll();
        reload(false, null);
    }

    private void loadMore() {
        if (!foreground || pageLoading || submittingRows) return;
        if ((pageError && !appendError) || recordData == null) {
            if (pageError) reload(false, null);
            return;
        }
        if (recordData.page >= recordData.totalPages) return;
        readRecordPage(true, recordData.page + 1, null);
    }

    private void retryPage() {
        if (appendError) loadMore();
        else reload(false, null);
    }

    private void refreshDepartmentOptions() {
        int version = ++departmentReadVersion;
        runIo(() -> {
            try {
                List<JSONObject> options = store.departmentOptions(0);
                main.post(() -> {
                    if (destroyed || version != departmentReadVersion) return;
                    departmentOptions = options;
                    if (departmentFilter != null) departmentFilter.replace(options);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!destroyed && foreground && version == departmentReadVersion)
                        notice("部门筛选读取失败：" + failure(error));
                });
            }
        });
    }

    private boolean fromDepartment() {
        return getIntent().getLongExtra(EXTRA_DEPARTMENT_ID, 0) > 0;
    }

    @Override
    public void onBackPressed() {
        if (!fromDepartment()) {
            super.onBackPressed();
            return;
        }
        finish();
    }

    private LoadingDialog listLoading;

    private void showRecordPage() {
        if (listLoading == null) listLoading = new LoadingDialog(this);
        if (pageLoading && foreground) listLoading.showReadOnly("正在读取列表");
        else listLoading.dismiss();
        if (content == null) return;
        List<JSONObject> values = records;
        if (submittedRows != values) {
            submittedRows = values;
            submittingRows = true;
            int version = ++rowsVersion;
            rowsAdapter.submitRows(values, () -> {
                if (destroyed || version != rowsVersion || recordList == null) return;
                submittingRows = false;
                restoreScroll();
            });
        }
        recordList.setEnabled(!values.isEmpty() || !pageLoading);
        ((TextView) content.findViewById(R.id.records_load_more)).setText(pageLoading ? ""
                : pageError ? "点击重新读取" : recordData == null || recordData.page >= recordData.totalPages
                                               ? "没有更多了" : "上拉加载更多");
        TextView empty = content.findViewById(R.id.records_empty);
        empty.setText(recordData == null || recordData.totalRows == 0
                ? R.string.business_records_empty : R.string.business_records_no_match);
        empty.setVisibility(!pageLoading && recordData != null && values.isEmpty() ? View.VISIBLE : View.GONE);
        if (pagination != null) pagination.update(recordData, pageLoading, pageError);
    }

    private void saveScroll() {
        if (restoreScroll || recordList == null || rowsAdapter.getItemCount() == 0) return;
        LinearLayoutManager manager = (LinearLayoutManager) recordList.getLayoutManager();
        int first = manager == null ? RecyclerView.NO_POSITION : manager.findFirstVisibleItemPosition();
        View row = first == RecyclerView.NO_POSITION ? null : manager.findViewByPosition(first);
        if (row == null || first >= rowsAdapter.getItemCount()) return;
        scrollId = rowsAdapter.item(first).optString("id");
        scrollPosition = first;
        scrollOffset = manager.getDecoratedTop(row) - recordList.getPaddingTop();
        restoreScroll = true;
    }

    private void resetScroll() {
        scrollId = "";
        scrollPosition = scrollOffset = 0;
        restoreScroll = true;
    }

    private void restoreScroll() {
        if (!restoreScroll || pageLoading || recordData == null || recordList == null) return;
        int count = rowsAdapter.getItemCount();
        int position = Math.min(scrollPosition, Math.max(0, count - 1));
        for (int index = 0; index < count; index++) {
            if (scrollId.equals(rowsAdapter.item(index).optString("id"))) {
                position = index;
                break;
            }
        }
        ((LinearLayoutManager) recordList.getLayoutManager()).scrollToPositionWithOffset(position, scrollOffset);
        restoreScroll = false;
    }

    private void openRecord(JSONObject record) {
        if (!foreground || navigating || isFinishing()) return;
        navigating = true;
        // 仅传记录 ID；列表留在返回栈，避免返回后丢失筛选、页码或传递大图。
        startActivity(new Intent(this, RecordDetailActivity.class)
                .putExtra(RecordDetailActivity.EXTRA_RECORD_ID, record.optString("id")));
    }

    private final class RecordRows extends JsonRowsAdapter<RecordRow> {
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

        RecordRows() {
            super("id");
        }

        @Override
        public RecordRow onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RecordRow(getLayoutInflater().inflate(R.layout.item_record_row, parent, false));
        }

        @Override
        public void onBindViewHolder(RecordRow holder, int position) {
            JSONObject record = item(position);
            holder.name.setText(value(record, "staff_name"));
            holder.number.setText(value(record, "staff_no"));
            holder.department.setText(value(record, "department"));
            int status = record.optInt("status", -1);
            holder.result.setText(status == 1 ? R.string.record_row_result_normal
                    : status == 0 ? R.string.record_row_result_abnormal : R.string.record_row_missing);
            holder.result.setTextColor(ContextCompat.getColor(RecordsActivity.this, status == 1 ? R.color.mc_green
                    : status == 0 ? R.color.mc_orange : R.color.mc_muted));
            long time = record.optLong("created_at");
            holder.date.setText(time > 0
                    ? dateFormat.format(new Date(time)) : getString(R.string.record_row_missing));
            holder.details.setContentDescription("查看晨检记录：" + value(record, "staff_name"));
            holder.details.setOnClickListener(v -> {
                if (!pageLoading) openRecord(record);
            });
            holder.itemView.setOnClickListener(v -> {
                if (!pageLoading) openRecord(record);
            });
        }

        private String value(JSONObject record, String key) {
            String text = record.isNull(key) ? "" : record.optString(key).trim();
            return text.isEmpty() ? getString(R.string.record_row_missing) : text;
        }
    }

    private static final class RecordRow extends RecyclerView.ViewHolder {
        final TextView name, number, department, result, date;
        final View details;

        RecordRow(View row) {
            super(row);
            name = row.findViewById(R.id.record_row_name);
            number = row.findViewById(R.id.record_row_number);
            department = row.findViewById(R.id.record_row_department);
            result = row.findViewById(R.id.record_row_result);
            date = row.findViewById(R.id.record_row_date);
            details = row.findViewById(R.id.record_row_view);
        }
    }

}
