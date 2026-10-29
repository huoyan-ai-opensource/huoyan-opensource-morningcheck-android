package cc.huoyan.opensource.morningcheck;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 员工和记录共用部门筛选，始终以部门 ID 保存选择，不依赖重名或改名后的路径。
 */
final class ListFilterUi {
    private ListFilterUi() {
    }

    interface DepartmentSelected {
        void selected(long id);
    }

    static void bindTable(View root, int tableId, int headerId, int emptyId, int minimumWidthDp) {
        if (minimumWidthDp > 0) root.findViewById(tableId).setMinimumWidth(
                Math.round(minimumWidthDp * root.getResources().getDisplayMetrics().density));
        View empty = root.findViewById(emptyId);
        root.findViewById(headerId).addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    // 空态固定在视口里，只避开表头；横向查看最后几列时仍能看见提示。
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) empty.getLayoutParams();
                    if (margins.topMargin != bottom - top) {
                        margins.topMargin = bottom - top;
                        empty.setLayoutParams(margins);
                    }
                });
    }

    static final class DepartmentFilter {
        private final DepartmentFilterSpinner spinner;
        private final DepartmentSelected listener;
        private final List<Long> ids = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private DepartmentFilterDialog dialog;
        private long selectedId;

        DepartmentFilter(DepartmentFilterSpinner spinner, long selectedId, DepartmentSelected listener) {
            this.spinner = spinner;
            this.selectedId = selectedId;
            this.listener = listener;
            // 深链进入某个部门时先保留 ID，不能在异步目录读完前退回「全部」。
            replace(null);
        }

        void replace(List<JSONObject> departments) {
            dismiss();
            names.clear();
            ids.clear();
            Set<Long> departmentIds = new HashSet<>();
            add(names, 0, spinner.getContext().getString(R.string.list_filter_department_all));
            add(names, -1, spinner.getContext().getString(R.string.list_filter_department_none));
            if (departments == null) {
                if (selectedId > 0) add(names, selectedId,
                        spinner.getContext().getString(R.string.list_filter_department_loading));
            } else {
                for (JSONObject department : departments) {
                    long id = department.optLong("id");
                    if (id <= 0 || !departmentIds.add(id)) continue;
                    String path = department.optString("path", "").trim();
                    add(names, id, path.isEmpty() ? department.optString("name") : path);
                }
            }
            long previous = selectedId;
            int position = ids.indexOf(selectedId);
            if (position < 0) {
                selectedId = 0;
                position = 0;
            }
            spinner.setOnItemSelectedListener(null);
            spinner.setAdapter(Ui.filterAdapter(spinner.getContext(), names, "部门"));
            spinner.setSelection(position, false);
            spinner.setDepartmentChooser(this::openChooser, this::dismiss);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }

                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long rowId) {
                    if (position < 0 || position >= ids.size()) return;
                    long next = ids.get(position);
                    if (next == DepartmentFilter.this.selectedId) return;
                    DepartmentFilter.this.selectedId = next;
                    listener.selected(next);
                }
            });
            // 部门已删除时恢复全部并重新查询；仅改名或调整层级时保留当前选择。
            if (previous != selectedId) listener.selected(selectedId);
        }

        private void openChooser() {
            if (dialog != null && dialog.isShowing()) return;
            dialog = DepartmentFilterDialog.show(spinner.getContext(), ids, names, selectedId, id -> {
                int position = ids.indexOf(id);
                if (position < 0 || selectedId == id) return;
                selectedId = id;
                spinner.setSelection(position, false);
                listener.selected(id);
            });
        }

        void dismiss() {
            if (dialog != null) {
                dialog.dismiss();
                dialog = null;
            }
        }

        private void add(List<String> names, long id, String name) {
            ids.add(id);
            names.add(name);
        }
    }
}
