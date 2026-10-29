package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 点选回传打开时的列表位置，取消及重复选择不触发写入。
 */
final class DesignChoiceDialog {
    private DesignChoiceDialog() {
    }

    static AlertDialog create(Activity activity, String title, String subtitle,
                              List<String> labels, int selected, IntConsumer action) {
        return create(activity, title, subtitle, labels, selected, false, action);
    }

    /**
     * 模型切换需要消费当前项点击来结束会话，是否写入仍由选模会话判断。
     */
    static AlertDialog create(Activity activity, String title, String subtitle,
                              List<String> labels, int selected, boolean notifySelected, IntConsumer action) {
        List<String> snapshot = new ArrayList<>(labels);
        View body = activity.getLayoutInflater().inflate(R.layout.dialog_design_choice, null);
        ((TextView) body.findViewById(R.id.department_filter_title)).setText(title);
        ((TextView) body.findViewById(R.id.choice_subtitle)).setText(subtitle);
        AlertDialog dialog = new AlertDialog(activity) {
            @Override
            protected void onStart() {
                super.onStart();
                // 与零售 SelectOptionDialog 一致，在首帧前完成窗口和列表测量。
                resizeToHost(activity, this, body);
            }
        };
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setDimAmount(.4f);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setView(body, 0, 0, 0, 0);
        ListView list = body.findViewById(R.id.department_filter_options);
        list.setEmptyView(body.findViewById(R.id.department_filter_empty));
        list.setAdapter(new BaseAdapter() {
            public int getCount() {
                return snapshot.size();
            }

            public String getItem(int position) {
                return snapshot.get(position);
            }

            public long getItemId(int position) {
                return position;
            }

            public View getView(int position, View recycled, ViewGroup parent) {
                View row = recycled == null ? activity.getLayoutInflater()
                        .inflate(R.layout.item_design_choice, parent, false) : recycled;
                bindRow(row, getItem(position), position == selected);
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            if (notifySelected || position != selected) action.accept(position);
        });
        if (selected >= 0) list.setSelection(selected);
        body.findViewById(R.id.department_filter_close).setOnClickListener(view -> dialog.cancel());
        return dialog;
    }

    private static void resizeToHost(Activity activity, AlertDialog dialog, View body) {
        View host = activity.getWindow().getDecorView();
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int hostWidth = host.getWidth() > 0 ? host.getWidth() : metrics.widthPixels;
        int hostHeight = host.getHeight() > 0 ? host.getHeight() : metrics.heightPixels;
        int margin = activity.getResources().getDimensionPixelSize(R.dimen.mc_choice_window_margin);
        int maxWidth = activity.getResources().getDimensionPixelSize(R.dimen.mc_choice_window_max_width);
        int width = Math.max(1, Math.min(hostWidth - margin * 2, maxWidth));
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        ListView list = body.findViewById(R.id.department_filter_options);
        list.getLayoutParams().height = 0;
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int fixedHeight = body.getMeasuredHeight();
        int available = Math.max(0, Math.round(hostHeight * .8f) - fixedHeight);
        int fullAvailable = Math.max(0, hostHeight - margin * 2 - fixedHeight);
        int total = 0;
        int firstRowHeight = 0;
        int rowWidth = Math.max(1, width - body.getPaddingLeft() - body.getPaddingRight());
        View row = null;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            row = list.getAdapter().getView(i, row, list);
            row.measure(View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            if (i == 0) firstRowHeight = row.getMeasuredHeight();
            total += row.getMeasuredHeight() + (i == 0 ? 0 : list.getDividerHeight());
            // 超出窗口后只需确认列表要滚动，后面的部门留给 ListView 按可见范围创建。
            if (total > fullAvailable) break;
        }
        if (total <= fullAvailable) {
            // 三项等短列表能在安全边距内完整展示时，不因高度比例额外制造滚动。
            available = total;
        } else if (available < firstRowHeight) {
            // 小高度窗口优先保留完整一行和取消操作，不用固定最小列表高度撑出屏幕。
            available = fullAvailable;
        }
        list.getLayoutParams().height = Math.min(total, available);
        list.requestLayout();
    }

    static void bindRow(View row, String label, boolean selected) {
        row.setSelected(selected);
        row.setBackgroundResource(selected ? R.drawable.mc_choice_selected : R.drawable.mc_field);
        int color = androidx.core.content.ContextCompat.getColor(row.getContext(),
                selected ? R.color.mc_blue : R.color.mc_ink);
        TextView text = row.findViewById(R.id.department_filter_option_name);
        text.setText(label);
        text.setTextColor(color);
        TextView radio = row.findViewById(R.id.department_filter_option_selected);
        radio.setText(selected ? "•" : "");
        radio.setTextColor(android.graphics.Color.WHITE);
        radio.setBackgroundResource(selected ? R.drawable.mc_radio_selected : R.drawable.mc_radio_unselected);
        row.setContentDescription(label + (selected ? "，已选中" : ""));
    }
}
