package cc.huoyan.opensource.morningcheck;

import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门目录展示完整树，读取和树排序在后台完成，刷新时按部门 ID 保留滚动位置。
 */
public final class DepartmentsActivity extends BaseMorningActivity {

    private final Runnable departmentsChanged = () -> reload(false, null);
    private final Runnable searchChanged = () -> reload(false, null);
    private final ArrayList<Bundle> ancestors = new ArrayList<>();
    private String query = "", loadedQuery = "", parentPath = "";
    private long parentId, loadedParentId = -1;
    private int page = 1, readVersion;
    private boolean pageLoading, pageError, rowsLoaded;
    private List<JSONObject> displayed = new ArrayList<>();
    private final DepartmentRows rows = new DepartmentRows();
    private RecyclerView list;
    private long anchorId;
    private int anchorPosition, anchorOffset, submissionVersion;
    private boolean anchorPending;
    private TextView endView;
    private LoadingDialog readingDialog;

    @Override
    protected String pageKey() {
        return "departments";
    }

    @Override
    protected int activityLayout() {
        return R.layout.view_departments_design;
    }

    @Override
    protected void setup(Bundle state) {
        if (state != null) {
            query = state.getString("department_query", "");
            page = Math.max(1, state.getInt("department_page", 1));
            parentId = state.getLong("department_parent", 0);
            parentPath = state.getString("department_parent_path", "");
            anchorId = state.getLong("department_anchor_id");
            anchorPosition = state.getInt("department_anchor_position");
            anchorOffset = state.getInt("department_anchor_offset");
            anchorPending = true;
            ArrayList<Bundle> restored = state.getParcelableArrayList("department_ancestors");
            if (restored != null) ancestors.addAll(restored);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        captureScrollAnchor();
        state.putString("department_query", query);
        state.putInt("department_page", page);
        state.putLong("department_parent", parentId);
        state.putString("department_parent_path", parentPath);
        state.putParcelableArrayList("department_ancestors", ancestors);
        state.putLong("department_anchor_id", anchorId);
        state.putInt("department_anchor_position", anchorPosition);
        state.putInt("department_anchor_offset", anchorOffset);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ensureAppFontCurrent()) return;
        application.addDepartmentChangeListener(departmentsChanged);
    }

    @Override
    protected void onStop() {
        captureScrollAnchor();
        application.removeDepartmentChangeListener(departmentsChanged);
        main.removeCallbacks(searchChanged);
        if (readingDialog != null) readingDialog.dismiss();
        if (deletingDialog != null) deletingDialog.dismiss();
        if (deleteConfirmation != null) deleteConfirmation.dismiss();
        super.onStop();
    }

    @Override
    protected void bindPage() {
        endView = content.findViewById(R.id.department_end);
        list = content.findViewById(R.id.department_list);
        BusinessListUi.bind(list);
        list.setAdapter(rows);
        ListFilterUi.bindTable(content, R.id.department_table_content, R.id.department_table_header,
                R.id.department_empty, 0);
        ((TextView) content.findViewById(R.id.department_title_actions)).setText(R.string.department_table_actions);
        ((TextView) findViewById(R.id.header_page_title)).setText(R.string.department_page_title);
        ((TextView) findViewById(R.id.header_back_label)).setText(R.string.department_back);
        findViewById(R.id.header_home).setContentDescription(getString(R.string.department_back));
        findViewById(R.id.header_home).setOnClickListener(v -> goBack());
        EditText search = content.findViewById(R.id.department_search);
        search.setText(query);
        search.addTextChangedListener(watcher(() -> {
            String next = search.getText().toString().trim();
            if (next.equals(query)) return;
            query = next;
            page = 1;
            ++readVersion;
            rowsLoaded = false;
            displayed = new ArrayList<>();
            resetScrollAnchor();
            submitDisplayed();
            pageLoading = true;
            showPage();
            main.removeCallbacks(searchChanged);
            main.postDelayed(searchChanged, 250);
        }));
        View clear = content.findViewById(R.id.department_clear);
        clear.setVisibility(View.GONE);
        View add = content.findViewById(R.id.department_add);
        add.setVisibility(View.VISIBLE);
        add.setOnClickListener(v -> openEditor(0));
        content.findViewById(R.id.department_up).setOnClickListener(v -> goBack());
        showPage();
        submitDisplayed();
    }

    @Override
    protected void beforeRender() {
        captureScrollAnchor();
        if (rows.getItemCount() > 0) anchorPending = true;
        if (list != null) list.setAdapter(null);
        list = null;
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> reload(recover, done));
            return;
        }
        if (destroyed || !foreground) return;
        main.removeCallbacks(searchChanged);
        int version = ++readVersion;
        String requestedQuery = query;
        long requestedParent = parentId;
        if (!loadedQuery.equals(query) || loadedParentId != parentId) {
            rowsLoaded = false;
            displayed = new ArrayList<>();
            submitDisplayed();
        }
        pageLoading = true;
        pageError = false;
        showPage();
        runIo(() -> {
            try {
                JSONObject parent = requestedParent == 0 ? null : store.department(requestedParent);
                if (requestedParent > 0 && parent == null) {
                    main.post(() -> {
                        if (destroyed || version != readVersion) return;
                        parentId = 0;
                        parentPath = "";
                        page = 1;
                        ancestors.clear();
                        reload(false, done);
                    });
                    return;
                }
                List<JSONObject> ordered = orderTree(store.departmentTreeRows(requestedQuery));
                main.post(() -> {
                    if (destroyed || version != readVersion) return;
                    captureScrollAnchor();
                    anchorPending = true;
                    displayed = ordered;
                    rowsLoaded = true;
                    loadedQuery = requestedQuery;
                    loadedParentId = requestedParent;
                    parentPath = parent == null ? "" : parent.optString("path", parent.optString("name"));
                    pageLoading = false;
                    loaded = true;
                    if (!foreground) return;
                    showPage();
                    submitDisplayed(done);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || version != readVersion) return;
                    pageError = true;
                    pageLoading = false;
                    showPage();
                    notice("部门读取失败：" + failure(error));
                });
            }
        });
    }

    private void showPage() {
        if (content == null) return;
        ((TextView) content.findViewById(R.id.department_path)).setText(!query.isEmpty()
                ? getString(R.string.department_search_scope)
                : parentId == 0 ? getString(R.string.department_root) : parentPath);
        content.findViewById(R.id.department_up).setVisibility(ancestors.isEmpty() ? View.GONE : View.VISIBLE);
        List<JSONObject> values = displayed;
        list.setEnabled(!pageLoading);
        TextView empty = content.findViewById(R.id.department_empty);
        empty.setText(!query.isEmpty() ? R.string.department_no_match
                : parentId > 0 ? R.string.department_children_empty : R.string.department_empty);
        empty.setVisibility(!pageLoading && rowsLoaded && values.isEmpty() ? View.VISIBLE : View.GONE);
        if (readingDialog == null) readingDialog = new LoadingDialog(this);
        if (pageLoading && foreground) readingDialog.showReadOnly("正在读取部门");
        else readingDialog.dismiss();
        endView.setText(pageError ? "点击重新读取" : "已显示全部 " + values.size() + " 个部门");
        endView.setVisibility(!pageLoading && (pageError || !values.isEmpty()) ? View.VISIBLE : View.GONE);
        endView.setOnClickListener(v -> {
            if (pageError) reload(false, null);
        });
    }

    private void submitDisplayed() {
        submitDisplayed(null);
    }

    private void submitDisplayed(Runnable done) {
        int version = ++submissionVersion;
        rows.submitRows(displayed, () -> {
            if (destroyed || version != submissionVersion) return;
            restoreScrollAnchor();
            if (done != null && foreground) done.run();
        });
    }

    private void resetScrollAnchor() {
        anchorId = 0;
        anchorPosition = 0;
        anchorOffset = 0;
        anchorPending = true;
    }

    private void captureScrollAnchor() {
        if (anchorPending || list == null || list.isLayoutRequested()) return;
        LinearLayoutManager manager = (LinearLayoutManager) list.getLayoutManager();
        if (manager == null) return;
        int position = manager.findFirstVisibleItemPosition();
        View first = manager.findViewByPosition(position);
        if (position < 0 || position >= rows.getItemCount() || first == null) return;
        anchorId = rows.item(position).optLong("id");
        anchorPosition = position;
        anchorOffset = manager.getDecoratedTop(first) - list.getPaddingTop();
    }

    private void restoreScrollAnchor() {
        if (!anchorPending || list == null || rows.getItemCount() == 0) return;
        RecyclerView target = list;
        target.post(() -> {
            if (destroyed || target != list || !anchorPending || rows.getItemCount() == 0) return;
            int position = Math.min(anchorPosition, rows.getItemCount() - 1);
            for (int i = 0; i < rows.getItemCount(); i++) {
                if (rows.item(i).optLong("id") == anchorId) {
                    position = i;
                    break;
                }
            }
            LinearLayoutManager manager = (LinearLayoutManager) target.getLayoutManager();
            if (manager == null) return;
            // 改名、调层级会改变索引；优先定位业务 ID，删除锚点时才退回原索引附近。
            manager.scrollToPositionWithOffset(position, anchorOffset);
            anchorPending = false;
        });
    }

    private static List<JSONObject> orderTree(List<JSONObject> rows) throws Exception {
        java.util.Map<Long, List<JSONObject>> children = new java.util.LinkedHashMap<>();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (JSONObject row : rows) ids.add(row.optLong("id"));
        for (JSONObject row : rows) {
            long parent = row.optLong("parent_id");
            children.computeIfAbsent(ids.contains(parent) ? parent : 0L, key -> new ArrayList<>()).add(row);
        }
        List<JSONObject> ordered = new ArrayList<>();
        appendChildren(0, 0, children, ordered, new java.util.HashSet<>());
        return ordered;
    }

    private static void appendChildren(long parent, int depth,
                                       java.util.Map<Long, List<JSONObject>> children,
                                       List<JSONObject> ordered, java.util.Set<Long> visited) throws Exception {
        List<JSONObject> rows = children.get(parent);
        if (rows == null) return;
        for (JSONObject row : rows) {
            if (!visited.add(row.optLong("id"))) continue;
            int rowDepth = parent == 0 && row.optLong("parent_id") > 0 ? 1 : depth;
            row.put("_tree_depth", Math.min(rowDepth, 6));
            ordered.add(row);
            appendChildren(row.optLong("id"), rowDepth + 1, children, ordered, visited);
        }
    }

    private void openDepartment(JSONObject item) {
        openEditor(item.optLong("id"));
    }

    private void openEditor(long id) {
        if (!foreground || navigating || isFinishing() || pageLoading) return;
        captureScrollAnchor();
        DepartmentEditorDialog.open(getSupportFragmentManager(), id, parentId);
    }

    private void goBack() {
        if (ancestors.isEmpty()) {
            finish();
            return;
        }
        Bundle previous = ancestors.remove(ancestors.size() - 1);
        parentId = previous.getLong("parent");
        page = previous.getInt("page", 1);
        query = previous.getString("query", "");
        parentPath = previous.getString("path", "");
        ((EditText) content.findViewById(R.id.department_search)).setText(query);
        reload(false, null);
    }

    private void viewMembers(long id, boolean records) {
        if (!foreground || navigating || isFinishing() || pageLoading) return;
        captureScrollAnchor();
        navigating = true;
        startActivity(new Intent(this, records ? RecordsActivity.class : StaffActivity.class)
                .putExtra("filter_department_id", id));
    }


    @Override
    public void onBackPressed() {
        goBack();
    }

    private androidx.appcompat.app.AlertDialog deleteConfirmation;
    private boolean deletingDepartment;
    private LoadingDialog deletingDialog;

    private void confirmDeleteDepartment(JSONObject item) {
        if (deletingDepartment || deleteConfirmation != null && deleteConfirmation.isShowing())
            return;
        long id = item.optLong("id");
        String name = item.optString("name");
        long parent = item.optLong("parent_id");
        deleteConfirmation = ImmersiveUi.show(DesignConfirmationDialog.create(this, "删除部门？",
                "确认删除“" + name + "”？含员工或下级部门时不能删除。此操作不可撤销。", "确认删除", (dialog, which) -> {
                    if (deletingDepartment) return;
                    deletingDepartment = true;
                    deletingDialog = new LoadingDialog(this);
                    deletingDialog.show("正在删除部门");
                    runIo(() -> {
                        try {
                            store.removeDepartment(id, name, parent);
                            main.post(() -> {
                                finishDepartmentDelete();
                                application.notifyDepartmentsChanged();
                                toast("部门已删除");
                            });
                        } catch (Exception error) {
                            main.post(() -> {
                                finishDepartmentDelete();
                                notice(failure(error));
                            });
                        }
                    });
                }));
    }

    private void finishDepartmentDelete() {
        deletingDepartment = false;
        if (deletingDialog != null) deletingDialog.dismiss();
    }

    private final class DepartmentRows extends JsonRowsAdapter<DepartmentHolder> {
        DepartmentRows() {
            super("id");
        }

        @Override
        public DepartmentHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new DepartmentHolder(getLayoutInflater().inflate(R.layout.item_department_design, parent, false));
        }

        @Override
        public void onBindViewHolder(DepartmentHolder holder, int position) {
            holder.bind(item(position));
        }
    }

    private final class DepartmentHolder extends RecyclerView.ViewHolder {
        private final TextView name, staff;
        private final View delete;

        DepartmentHolder(View row) {
            super(row);
            name = row.findViewById(R.id.business_row_name);
            staff = row.findViewById(R.id.department_row_staff);
            TextView state = row.findViewById(R.id.business_row_state);
            state.setText(R.string.design_edit);
            state.setTextColor(androidx.core.content.ContextCompat.getColor(DepartmentsActivity.this, R.color.mc_blue));
            row.setOnClickListener(v -> {
                JSONObject item = currentItem();
                if (item != null) openDepartment(item);
            });
            state.setOnClickListener(v -> {
                JSONObject item = currentItem();
                if (item != null) openDepartment(item);
            });
            staff.setOnClickListener(v -> {
                JSONObject item = currentItem();
                if (item != null) viewMembers(item.optLong("id"), false);
            });
            row.setOnLongClickListener(v -> {
                JSONObject item = currentItem();
                if (item == null || !foreground || navigating || isFinishing() || pageLoading) return true;
                long id = item.optLong("id");
                ImmersiveUi.show(new ProjectDialog.Builder(DepartmentsActivity.this)
                        .setTitle(item.optString("name"))
                        .setItems(new String[]{"查看员工", "查看晨检记录", "新增下级部门"}, (dialog, which) -> {
                            if (which < 2) viewMembers(id, which == 1);
                            else DepartmentEditorDialog.open(getSupportFragmentManager(), 0, id);
                        }).create());
                return true;
            });
            delete = row.findViewById(R.id.department_row_delete);
            delete.setVisibility(View.VISIBLE);
            delete.setOnClickListener(v -> {
                JSONObject item = currentItem();
                if (item != null && foreground && !navigating && !isFinishing() && !pageLoading)
                    confirmDeleteDepartment(item);
            });
        }

        private JSONObject currentItem() {
            int position = getBindingAdapterPosition();
            return position == RecyclerView.NO_POSITION ? null : rows.item(position);
        }

        void bind(JSONObject item) {
            name.setText(item.optString("name"));
            staff.setText(String.valueOf(item.optLong("member_count")));
            delete.setContentDescription("删除部门：" + item.optString("name"));
            boolean child = item.optLong("parent_id") > 0;
            name.setCompoundDrawablesRelativeWithIntrinsicBounds(child ? R.drawable.mc_department_branch : R.drawable.mc_folder, 0, 0, 0);
            int inset = getResources().getDimensionPixelSize(R.dimen.dp_8);
            name.setCompoundDrawablePadding(inset);
            name.setPaddingRelative(item.optInt("_tree_depth") * inset, 0, inset, 0);
        }
    }
}
