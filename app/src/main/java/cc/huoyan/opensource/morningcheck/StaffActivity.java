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

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.domain.FaceResultPolicy;

/**
 * 员工分页列表；查看、编辑和人脸管理由独立页面负责。
 */
public final class StaffActivity extends BaseMorningActivity {
    public static final String EXTRA_DEPARTMENT_ID = "filter_department_id";

    private final Runnable staffChanged = () -> reload(false, null);
    private final Runnable searchChanged = () -> reload(false, null);
    private final Runnable departmentsChanged = () -> {
        refreshDepartmentOptions();
        reload(false, null);
    };
    private String staffQuery = "";
    private int staffPage = 1, enabledFilter, readVersion, departmentReadVersion;
    private long departmentId, loadedDepartmentId;
    private androidx.appcompat.app.AlertDialog deleteConfirmation;
    private boolean deletingStaff;
    private LoadingDialog deletingDialog;
    private boolean pageLoading;
    private boolean pageError;
    private String loadedQuery = "";
    private int loadedEnabledFilter;
    private AppStore.DataPage staffData;
    private List<JSONObject> departmentOptions;
    private ListFilterUi.DepartmentFilter departmentFilter;
    private PaginationUi pagination;
    private final StaffRows rowsAdapter = new StaffRows();
    private RecyclerView staffList;
    private List<JSONObject> submittedRows;
    private boolean submittingRows, appendError, restoreScroll;
    private String scrollId = "";
    private int scrollPosition, scrollOffset, rowsVersion;

    @Override
    protected void setup(Bundle state) {
        staff = new ArrayList<>();
        departmentId = Math.max(-1, getIntent().getLongExtra(EXTRA_DEPARTMENT_ID, 0));
        if (state != null) {
            staffQuery = state.getString("staff_query", "");
            staffPage = Math.max(1, state.getInt("staff_page", 1));
            enabledFilter = Math.max(0, Math.min(2, state.getInt("staff_enabled_filter", 0)));
            departmentId = Math.max(-1, state.getLong("staff_department_id", departmentId));
            scrollId = state.getString("staff_scroll_id", "");
            scrollPosition = state.getInt("staff_scroll_position", 0);
            scrollOffset = state.getInt("staff_scroll_offset", 0);
            restoreScroll = true;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        saveScroll();
        state.putString("staff_query", staffQuery);
        state.putInt("staff_page", staffPage);
        state.putInt("staff_enabled_filter", enabledFilter);
        state.putLong("staff_department_id", departmentId);
        state.putString("staff_scroll_id", scrollId);
        state.putInt("staff_scroll_position", scrollPosition);
        state.putInt("staff_scroll_offset", scrollOffset);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ensureAppFontCurrent()) return;
        application.addStaffChangeListener(staffChanged);
        application.addDepartmentChangeListener(departmentsChanged);
        refreshDepartmentOptions();
    }

    @Override
    protected void onStop() {
        saveScroll();
        if (departmentFilter != null) departmentFilter.dismiss();
        application.removeStaffChangeListener(staffChanged);
        application.removeDepartmentChangeListener(departmentsChanged);
        departmentReadVersion++;
        readVersion++;
        pageLoading = false;
        main.removeCallbacks(searchChanged);
        if (listLoading != null) listLoading.dismiss();
        if (deletingDialog != null) deletingDialog.dismiss();
        if (deleteConfirmation != null) deleteConfirmation.dismiss();
        super.onStop();
    }

    @Override
    protected String pageKey() {
        return "staff";
    }

    @Override
    protected int activityLayout() {
        return R.layout.activity_staff;
    }

    @Override
    protected void beforeRender() {
        saveScroll();
        if (staffList != null) staffList.setAdapter(null);
        staffList = null;
        submittedRows = null;
        rowsVersion++;
    }

    @Override
    protected void bindPage() {
        if (departmentFilter != null) departmentFilter.dismiss();
        View body = content;
        EditText search = body.findViewById(R.id.staff_search);
        search.setText(staffQuery);
        ListFilterUi.bindTable(body, R.id.staff_table_content, R.id.staff_table_header, R.id.staff_empty, 0);
        staffList = body.findViewById(R.id.staff_list);
        BusinessListUi.bind(staffList);
        staffList.setAdapter(rowsAdapter);
        Spinner enabled = body.findViewById(R.id.staff_enabled_filter);
        enabled.setAdapter(Ui.filterAdapter(this, java.util.Arrays.asList(getResources().getStringArray(R.array.list_filter_staff_statuses)), "启用状态"));
        enabled.setSelection(enabledFilter, false);
        enabled.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == enabledFilter) return;
                enabledFilter = position;
                resetFilters();
            }
        });
        departmentFilter = new ListFilterUi.DepartmentFilter(body.findViewById(R.id.staff_department_filter),
                departmentId, id -> {
            if (id == departmentId) return;
            departmentId = id;
            resetFilters();
        });
        if (departmentOptions != null) departmentFilter.replace(departmentOptions);
        if (fromDepartment()) {
            ((TextView) findViewById(R.id.header_back_label)).setText(R.string.list_filter_back_department);
            findViewById(R.id.header_home).setContentDescription(getString(R.string.list_filter_back_department));
            findViewById(R.id.header_home).setOnClickListener(v -> finish());
        }
        body.findViewById(R.id.staff_add).setOnClickListener(v -> openStaffPage(new Intent(this, StaffEditActivity.class)
                .putExtra(StaffEditActivity.EXTRA_DIALOG, true)));
        body.findViewById(R.id.staff_enroll).setOnClickListener(v -> openStaffPage(new Intent(this, FaceEnrollmentActivity.class)));
        body.findViewById(R.id.staff_departments).setOnClickListener(v -> openStaffPage(new Intent(this, DepartmentsActivity.class)));
        body.findViewById(R.id.staff_load_more).setOnClickListener(v -> loadMore());
        staffList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView view, int dx, int dy) {
                LinearLayoutManager manager = (LinearLayoutManager) view.getLayoutManager();
                if (dy > 0 && manager != null && manager.findLastVisibleItemPosition() >= rowsAdapter.getItemCount() - 5)
                    loadMore();
            }
        });
        search.addTextChangedListener(watcher(() -> {
            String q = search.getText().toString().trim();
            if (q.equals(staffQuery)) return;
            staffQuery = q;
            staffPage = 1;
            readVersion++;
            staffData = null;
            staff = new ArrayList<>();
            resetScroll();
            pageLoading = true;
            showStaffPage();
            main.removeCallbacks(searchChanged);
            main.postDelayed(searchChanged, 250);
        }));
        pagination = new PaginationUi(this, body, R.id.staff_first, R.id.staff_previous,
                R.id.staff_page_info, R.id.staff_page_input, R.id.staff_page_jump, R.id.staff_next, R.id.staff_last,
                page -> {
                    staffPage = page;
                    reload(false, null);
                }, this::retryPage);
        showStaffPage();
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> reload(recover, done));
            return;
        }
        main.removeCallbacks(searchChanged);
        if (!loadedQuery.equals(staffQuery) || loadedEnabledFilter != enabledFilter || loadedDepartmentId != departmentId) {
            staffData = null;
            staff = new ArrayList<>();
        }
        saveScroll();
        readStaffPage(false, staffPage, done);
    }

    private void readStaffPage(boolean append, int requestedPage, Runnable done) {
        int version = ++readVersion;
        String query = staffQuery;
        int enabled = enabledFilter;
        long department = departmentId;
        pageError = false;
        appendError = false;
        pageLoading = true;
        showStaffPage();
        runIo(() -> {
            try {
                AppStore.DataPage data = append
                        ? store.staffPage(query, enabled, department, requestedPage, 24)
                        : store.staffWindow(query, enabled, department, requestedPage, 24);
                main.post(() -> {
                    if (destroyed || version != readVersion) return;
                    staffData = data;
                    loadedQuery = query;
                    loadedEnabledFilter = enabled;
                    loadedDepartmentId = department;
                    // 只有读页成功才推进边界，重试失败页不会漏页；偏移分页重叠的 ID 只保留一份。
                    Map<String, JSONObject> merged = new LinkedHashMap<>();
                    if (append) for (JSONObject item : staff) merged.put(item.optString("id"), item);
                    for (JSONObject item : data.rows) merged.put(item.optString("id"), item);
                    staff = new ArrayList<>(merged.values());
                    staffPage = data.page;
                    pageLoading = false;
                    loaded = true;
                    if (!foreground) return;
                    showStaffPage();
                    if (done != null) done.run();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || version != readVersion) return;
                    pageLoading = false;
                    pageError = true;
                    appendError = append;
                    showStaffPage();
                    notice("员工读取失败：" + failure(error));
                });
            }
        });
    }

    private void resetFilters() {
        staffPage = 1;
        staffData = null;
        staff = new ArrayList<>();
        resetScroll();
        reload(false, null);
    }

    private void loadMore() {
        if (!foreground || pageLoading || submittingRows) return;
        if ((pageError && !appendError) || staffData == null) {
            if (pageError) reload(false, null);
            return;
        }
        if (staffData.page >= staffData.totalPages) return;
        readStaffPage(true, staffData.page + 1, null);
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

    private void showStaffPage() {
        if (listLoading == null) listLoading = new LoadingDialog(this);
        if (pageLoading && foreground) listLoading.showReadOnly("正在读取列表");
        else listLoading.dismiss();
        if (content == null) return;
        List<JSONObject> values = staff;
        if (submittedRows != values) {
            submittedRows = values;
            submittingRows = true;
            int version = ++rowsVersion;
            rowsAdapter.submitRows(values, () -> {
                if (destroyed || version != rowsVersion || staffList == null) return;
                submittingRows = false;
                restoreScroll();
            });
        }
        staffList.setEnabled(!values.isEmpty() || !pageLoading);
        TextView more = content.findViewById(R.id.staff_load_more);
        more.setText(pageLoading ? "" : pageError ? "点击重新读取"
                                        : staffData == null || staffData.page >= staffData.totalPages ? "没有更多了" : "上拉加载更多");
        TextView empty = content.findViewById(R.id.staff_empty);
        empty.setText(staffData == null || staffData.totalRows == 0
                ? R.string.business_staff_empty : R.string.business_staff_no_match);
        empty.setVisibility(!pageLoading && staffData != null && values.isEmpty() ? View.VISIBLE : View.GONE);
        if (pagination != null) pagination.update(staffData, pageLoading, pageError);
    }

    private void saveScroll() {
        if (restoreScroll || staffList == null || rowsAdapter.getItemCount() == 0) return;
        LinearLayoutManager manager = (LinearLayoutManager) staffList.getLayoutManager();
        int first = manager == null ? RecyclerView.NO_POSITION : manager.findFirstVisibleItemPosition();
        View row = first == RecyclerView.NO_POSITION ? null : manager.findViewByPosition(first);
        if (row == null || first >= rowsAdapter.getItemCount()) return;
        scrollId = rowsAdapter.item(first).optString("id");
        scrollPosition = first;
        scrollOffset = manager.getDecoratedTop(row) - staffList.getPaddingTop();
        restoreScroll = true;
    }

    private void resetScroll() {
        scrollId = "";
        scrollPosition = scrollOffset = 0;
        restoreScroll = true;
    }

    private void restoreScroll() {
        if (!restoreScroll || pageLoading || staffData == null || staffList == null) return;
        int count = rowsAdapter.getItemCount();
        int position = Math.min(scrollPosition, Math.max(0, count - 1));
        for (int index = 0; index < count; index++) {
            if (scrollId.equals(rowsAdapter.item(index).optString("id"))) {
                position = index;
                break;
            }
        }
        // 返回和资料变更以业务 ID 为锚点；锚点已删除时保留相邻位置，不跳回列表开头。
        ((LinearLayoutManager) staffList.getLayoutManager()).scrollToPositionWithOffset(position, scrollOffset);
        restoreScroll = false;
    }

    private void openStaffPage(Intent intent) {
        if (!foreground || navigating || isFinishing()) return;
        navigating = true;
        startActivity(intent);
    }

    private final class StaffRows extends JsonRowsAdapter<StaffRow> {
        StaffRows() {
            super("id");
        }

        @Override
        public StaffRow onCreateViewHolder(ViewGroup parent, int viewType) {
            return new StaffRow(getLayoutInflater().inflate(R.layout.item_staff_row, parent, false));
        }

        @Override
        public void onBindViewHolder(StaffRow holder, int position) {
            JSONObject item = item(position);
            String name = item.isNull("name") ? "" : item.optString("name").trim();
            holder.name.setText(name.isEmpty() ? "无" : name);
            holder.face.setTextColor(Ui.BLUE);
            StaffInfoUi.bindValues(item, holder.number, holder.enabled, holder.department, holder.health);
            holder.enabled.setText(item.optBoolean("enabled", true) ? "启用" : "停用");
            holder.department.setTextColor(Ui.INK);
            holder.face.setText(item.optBoolean("face_registered") ? "已录入" : "未录入");
            long staffId = item.optLong("id");
            holder.enroll.setContentDescription("录入人脸：" + name);
            holder.edit.setContentDescription("编辑员工：" + name);
            holder.delete.setContentDescription("删除员工：" + name);
            holder.enroll.setOnClickListener(v -> {
                if (!pageLoading) openStaffPage(new Intent(StaffActivity.this,
                        FaceEnrollmentActivity.class).putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, staffId));
            });
            holder.edit.setOnClickListener(v -> {
                if (!pageLoading) openStaffPage(new Intent(StaffActivity.this,
                        StaffEditActivity.class).putExtra(StaffEditActivity.EXTRA_STAFF_ID, staffId)
                        .putExtra(StaffEditActivity.EXTRA_DIALOG, true));
            });
            holder.delete.setOnClickListener(v -> {
                if (!pageLoading) confirmDeleteStaff(item);
            });
            holder.itemView.setOnClickListener(v -> {
                if (!pageLoading) openStaffPage(new Intent(StaffActivity.this, StaffEditActivity.class)
                        .putExtra(StaffEditActivity.EXTRA_STAFF_ID, staffId)
                        .putExtra(StaffEditActivity.EXTRA_DIALOG, true));
            });
        }
    }

    private static final class StaffRow extends RecyclerView.ViewHolder {
        final TextView name, face, number, enabled, department, health;
        final View enroll, edit, delete;

        StaffRow(View row) {
            super(row);
            name = row.findViewById(R.id.staff_row_name);
            face = row.findViewById(R.id.staff_row_face);
            number = row.findViewById(R.id.staff_row_number);
            enabled = row.findViewById(R.id.staff_row_enabled);
            department = row.findViewById(R.id.staff_row_department);
            health = row.findViewById(R.id.staff_row_health);
            enroll = row.findViewById(R.id.staff_row_enroll);
            edit = row.findViewById(R.id.staff_row_edit);
            delete = row.findViewById(R.id.staff_row_delete);
        }
    }

    private void confirmDeleteStaff(JSONObject person) {
        if (deletingStaff || !foreground || deleteConfirmation != null && deleteConfirmation.isShowing())
            return;
        long staffId = person.optLong("id");
        deleteConfirmation = ImmersiveUi.show(DesignConfirmationDialog.create(this, "删除员工？",
                "将清除“" + person.optString("name") + "”的 SDK 人脸，并从员工名单移除。历史晨检记录保留，云端员工资料不受影响。",
                "确认删除", (dialog, which) -> {
                    if (deletingStaff) return;
                    if (!sdk.isConnected()) {
                        notice("请先连接 SDK，确认人脸删除后才能删除员工档案");
                        return;
                    }
                    if (!application.faceOperations.beginWrite(staffId)) {
                        notice("该员工的人脸操作仍在执行，请稍候重试");
                        return;
                    }
                    long generation = sdkGeneration();
                    deletingStaff = true;
                    deletingDialog = new LoadingDialog(this);
                    deletingDialog.show("正在删除员工");
                    runIo(() -> {
                        try {
                            JSONObject current = store.staff(staffId);
                            if (current == null || current.optLong("person_id") <= 0)
                                throw new IllegalStateException("员工档案已变化，请刷新后操作");
                            long personId = current.optLong("person_id");
                            main.post(() -> deleteStaffFaces(staffId, personId, generation));
                        } catch (Exception error) {
                            main.post(() -> {
                                finishStaffDelete(staffId);
                                notice(failure(error));
                            });
                        }
                    });
                }));
    }

    private void deleteStaffFaces(long staffId, long personId, long generation) {
        if (sdkGeneration() != generation || !sdk.isConnected()) {
            finishStaffDelete(staffId);
            notice("连接已改变，员工档案已保留，请重新操作");
            return;
        }
        try {
            sdk.forget(personId, (code, message, data) -> {
                if (sdkGeneration() != generation || !FaceResultPolicy.isAbsentOrDeleted(code)) {
                    finishStaffDelete(staffId);
                    notice(sdkGeneration() != generation ? "连接已改变，删除结果待确认，员工档案已保留" : message);
                    return;
                }
                runIo(() -> {
                    try {
                        JSONObject current = store.staff(staffId);
                        if (current != null && current.optLong("person_id") != personId)
                            throw new IllegalStateException("员工身份已变化，档案已保留，请刷新后操作");
                        // SDK 人脸确认清除后，仅软删除本地档案，永久保留 person_id 占用。
                        store.removeStaff(staffId);
                        main.post(() -> {
                            finishStaffDelete(staffId);
                            application.notifyStaffChanged();
                            toast("员工已删除");
                        });
                    } catch (Exception error) {
                        main.post(() -> {
                            finishStaffDelete(staffId);
                            notice(failure(error));
                        });
                    }
                });
            });
        } catch (RuntimeException error) {
            finishStaffDelete(staffId);
            notice("删除结果待确认，员工档案已保留：" + failure(error));
        }
    }

    private void finishStaffDelete(long staffId) {
        application.faceOperations.finishWrite(staffId);
        deletingStaff = false;
        if (deletingDialog != null) deletingDialog.dismiss();
    }

    private void staffWriteSucceeded(Runnable feedback) {
        main.post(() -> {
            // 旧页面发起的网络请求可能在新员工页打开后才落盘；通知当前订阅者，不能只刷新旧实例。
            application.notifyStaffChanged();
            if (!destroyed && foreground && feedback != null) feedback.run();
        });
    }

    private void importStaff(TextView button) {
        final String base = setting("cloud_url", ""), token = setting("cloud_token", "");
        if (base.isEmpty() || token.isEmpty()) {
            notice("请先在设备设置中填写晨检业务云地址和令牌。");
            return;
        }
        button.setEnabled(false);
        button.setText("导入中");
        toast("正在导入员工名册，人脸需要在新 SDK 中登记");
        application.networkIo.execute(() -> {
            try {
                Map<Long, JSONObject> imported = new LinkedHashMap<>();
                int pages = 1;
                for (int number = 1; number <= pages; number++) {
                    JSONObject response = cloud.listStaff(base, token, number, 100);
                    JSONArray rows = cc.huoyan.opensource.morningcheck.data.StaffPolicy.pageRecords(response);
                    pages = cc.huoyan.opensource.morningcheck.data.StaffPolicy.pageCount(response);
                    JSONObject data = response.optJSONObject("data");
                    if (data.has("current") && data.optInt("current") != number)
                        throw new IllegalStateException("云端返回了不匹配的员工页码，未导入");
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject remote = rows.optJSONObject(i);
                        long id = remote == null ? 0 : remote.optLong("id");
                        // 网络阶段只校验远端资料；合并时回到存储线程读取当时最新的人脸与启停状态。
                        cc.huoyan.opensource.morningcheck.data.StaffPolicy.fromCloud(null, remote, id, System.currentTimeMillis());
                        imported.put(id, remote);
                    }
                }
                runIo(() -> {
                    try {
                        List<JSONObject> merged = new ArrayList<>();
                        for (Map.Entry<Long, JSONObject> entry : imported.entrySet())
                            merged.add(cc.huoyan.opensource.morningcheck.data.StaffPolicy.fromCloud(
                                    store.staff(entry.getKey()), entry.getValue(), entry.getKey(), System.currentTimeMillis()));
                        store.saveStaffBatch(merged);
                        staffWriteSucceeded(() -> toast("已导入 " + merged.size()
                                + " 位员工。新员工尚未登记人脸，请在员工列表点击录入。"));
                    } catch (Exception error) {
                        main.post(() -> notice("名册未导入：" + failure(error)));
                    } finally {
                        main.post(() -> {
                            button.setEnabled(true);
                            button.setText("云端导入");
                        });
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    notice("名册未导入：" + failure(error));
                    button.setEnabled(true);
                    button.setText("云端导入");
                });
            }
        });
    }

}
