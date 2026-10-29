package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.function.IntConsumer;

/**
 * 字号草稿仅在弹窗内预览，应用后才交给页面保存。
 */
final class AppFontSizeDialog extends AlertDialog {
    private final Activity activity;
    private final View body;
    private final String[] descriptions;
    private final ListView options;
    private int selected;

    AppFontSizeDialog(Activity activity, int saved, IntConsumer apply) {
        super(activity);
        this.activity = activity;
        selected = saved;
        descriptions = activity.getResources().getStringArray(R.array.app_font_size_descriptions);
        body = activity.getLayoutInflater().inflate(R.layout.dialog_app_font_size, null);
        options = body.findViewById(R.id.settings_font_options);
        options.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return descriptions.length;
            }

            @Override
            public String getItem(int position) {
                return descriptions[position];
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                View row = recycled == null ? activity.getLayoutInflater()
                        .inflate(R.layout.item_design_choice, parent, false) : recycled;
                DesignChoiceDialog.bindRow(row, getItem(position), position == selected);
                return row;
            }
        });
        options.setOnItemClickListener((parent, view, position, id) -> {
            selected = position;
            ((BaseAdapter) options.getAdapter()).notifyDataSetChanged();
            updatePreview();
        });
        body.findViewById(R.id.settings_font_cancel).setOnClickListener(view -> cancel());
        body.findViewById(R.id.settings_font_apply).setOnClickListener(view -> {
            dismiss();
            apply.accept(selected);
        });
        setView(body, 0, 0, 0, 0);
        setCanceledOnTouchOutside(true);
        updatePreview();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().setDimAmount(.4f);
        resizeToHost();
    }

    private void updatePreview() {
        options.setItemChecked(selected, true);
        ((TextView) body.findViewById(R.id.settings_font_current)).setText(
                activity.getString(R.string.app_font_size_preview_label, descriptions[selected]));
        TextView preview = body.findViewById(R.id.settings_font_preview);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_PX, SystemSettingsActivity.previewTextSize(activity, selected));
        if (isShowing()) resizeToHost();
    }

    private void resizeToHost() {
        View host = activity.getWindow().getDecorView();
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int hostWidth = host.getWidth() > 0 ? host.getWidth() : metrics.widthPixels;
        int hostHeight = host.getHeight() > 0 ? host.getHeight() : metrics.heightPixels;
        int margin = activity.getResources().getDimensionPixelSize(R.dimen.mc_choice_window_margin);
        int width = Math.max(1, Math.min(hostWidth - margin * 2,
                activity.getResources().getDimensionPixelSize(R.dimen.mc_font_window_max_width)));
        getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        options.getLayoutParams().height = 0;
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int rowWidth = Math.max(1, options.getMeasuredWidth());
        int rowsHeight = 0;
        for (int index = 0; index < options.getCount(); index++) {
            View row = options.getAdapter().getView(index, null, options);
            row.measure(View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            rowsHeight += row.getMeasuredHeight() + (index == 0 ? 0 : options.getDividerHeight());
        }
        // 矮屏只压缩选项列表，预览和取消、应用操作保持可见。
        int heightLimit = Math.min(Math.round(hostHeight * .9f), hostHeight - margin * 2);
        View columns = body.findViewById(R.id.settings_font_content);
        int fixedHeight = body.getMeasuredHeight() - (columns == null ? 0 : columns.getMeasuredHeight());
        options.getLayoutParams().height = Math.min(rowsHeight, Math.max(0, heightLimit - fixedHeight));
        options.requestLayout();
        options.setSelection(selected);
    }
}
