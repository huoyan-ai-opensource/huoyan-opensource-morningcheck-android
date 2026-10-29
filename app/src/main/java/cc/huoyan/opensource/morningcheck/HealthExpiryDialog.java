package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import cc.huoyan.opensource.morningcheck.domain.HealthDateSelection;

/**
 * 自定义 XML 日历，竖窗单列、横窗左右分区；只在确认时修改员工表单。
 */
final class HealthExpiryDialog extends Dialog {
    private final HealthDateSelection selection;
    private final Consumer<String> confirmed;
    private final Days days = new Days();
    private final List<String> years = new ArrayList<>();
    private final int firstYear;
    private final Activity host;
    private final boolean sideBySide;
    private final Context uiContext;
    private final Spinner yearPicker;
    private final Spinner monthPicker;
    private boolean syncing;

    HealthExpiryDialog(Context context, HealthDateSelection selection, Consumer<String> confirmed) {
        super(context);
        this.selection = selection;
        this.confirmed = confirmed;
        host = activityOf(context);
        if (host != null) setOwnerActivity(host);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Rect bounds = availableWindowBounds();
        Configuration layoutConfiguration = new Configuration(getContext().getResources().getConfiguration());
        // 弹窗使用原始尺寸表，避免页面的 sw 等比放大撑满窗口；只覆盖局部资源，不改系统密度和字体。
        layoutConfiguration.smallestScreenWidthDp = 1;
        sideBySide = bounds.width() > bounds.height();
        layoutConfiguration.orientation = sideBySide ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        // 主题也使用局部尺寸表，不能复制宿主平板主题中已选好的字号引用。
        android.view.ContextThemeWrapper layoutContext = new android.view.ContextThemeWrapper(
                getContext().createConfigurationContext(layoutConfiguration), R.style.AppTheme);
        layoutContext.getTheme().applyStyle(R.style.McHealthDateDialogTheme, true);
        uiContext = layoutContext;
        setContentView(LayoutInflater.from(getContext()).cloneInContext(layoutContext).inflate(R.layout.dialog_health_expiry, null));
        findViewById(R.id.health_date_panel).addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> fitActionRow());
        firstYear = Math.min(1900, selection.selected().getYear());
        int lastYear = Math.max(LocalDate.now().getYear() + 100, selection.selected().getYear());
        for (int year = firstYear; year <= lastYear; year++) years.add(year + "年");
        yearPicker = findViewById(R.id.health_date_year);
        monthPicker = findViewById(R.id.health_date_month);
        yearPicker.setPopupBackgroundResource(R.drawable.mc_panel);
        monthPicker.setPopupBackgroundResource(R.drawable.mc_panel);
        List<String> months = new ArrayList<>();
        for (int month = 1; month <= 12; month++) months.add(month + "月");
        yearPicker.setAdapter(options(years));
        monthPicker.setAdapter(options(months));
        AdapterView.OnItemSelectedListener changed = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (syncing || yearPicker.getSelectedItemPosition() < 0 || monthPicker.getSelectedItemPosition() < 0)
                    return;
                selection.browse(firstYear + yearPicker.getSelectedItemPosition(), monthPicker.getSelectedItemPosition() + 1);
                updateCalendar();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        yearPicker.setOnItemSelectedListener(changed);
        monthPicker.setOnItemSelectedListener(changed);
        GridView calendar = findViewById(R.id.health_date_days);
        calendar.setAdapter(days);
        calendar.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop || right - left != oldRight - oldLeft)
                days.notifyDataSetChanged();
        });
        calendar.setOnItemClickListener((parent, view, position, id) -> {
            int day = selection.dayAt(position);
            if (day > 0) {
                selection.choose(day);
                updateCalendar();
            }
        });
        findViewById(R.id.health_date_previous).setOnClickListener(view -> step(-1));
        findViewById(R.id.health_date_next).setOnClickListener(view -> step(1));
        findViewById(R.id.health_date_clear).setOnClickListener(view -> {
            confirmed.accept("");
            dismiss();
        });
        findViewById(R.id.health_date_cancel).setOnClickListener(view -> dismiss());
        findViewById(R.id.health_date_confirm).setOnClickListener(view -> {
            confirmed.accept(selection.selected().toString());
            dismiss();
        });
        syncSelectors();
        updateCalendar();
    }

    private ArrayAdapter<String> options(List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(uiContext, R.layout.item_health_date_option, values);
        adapter.setDropDownViewResource(R.layout.item_health_date_option);
        return adapter;
    }

    private void step(int offset) {
        int proposedYear = selection.month().plusMonths(offset).getYear();
        if (proposedYear < firstYear || proposedYear >= firstYear + years.size()) return;
        selection.step(offset);
        syncSelectors();
        updateCalendar();
    }

    private void syncSelectors() {
        syncing = true;
        yearPicker.setSelection(selection.month().getYear() - firstYear);
        monthPicker.setSelection(selection.month().getMonthValue() - 1);
        syncing = false;
    }

    private void updateCalendar() {
        ((TextView) findViewById(R.id.health_date_selected)).setText(selection.selected().toString());
        ((TextView) findViewById(R.id.health_date_weekday)).setText("星期" + new String[]{"一", "二", "三", "四", "五", "六", "日"}[selection.selected().getDayOfWeek().getValue() - 1]);
        days.notifyDataSetChanged();
    }

    @Override
    public void show() {
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        super.show();
        if (window != null) {
            Rect bounds = availableWindowBounds();
            int[] panelSize = panelSize(bounds.width(), bounds.height());
            window.setGravity(Gravity.CENTER);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.35f);
            window.setLayout(panelSize[0], panelSize[1]);
            ImmersiveUi.enable(window);
        }
    }

    int[] panelSize(int width, int height) {
        int margin = size(R.dimen.dp_12) * 2;
        boolean spaciousLandscape = sideBySide
                && width >= size(R.dimen.dp_400, R.dimen.dp_400, R.dimen.dp_40)
                && height >= size(R.dimen.dp_472, R.dimen.dp_48);
        // 大横窗留出完整六行日历和宽裕触控区；矮横窗按可用高度收缩，保留全部日期与操作。
        int preferredWidth = spaciousLandscape ? size(R.dimen.dp_400, R.dimen.dp_400, R.dimen.dp_100, R.dimen.dp_20)
                : sideBySide ? size(R.dimen.dp_400, R.dimen.dp_260, R.dimen.dp_44)
                  : size(R.dimen.dp_400, R.dimen.dp_40);
        int preferredHeight = sideBySide && !spaciousLandscape
                ? size(R.dimen.dp_400, R.dimen.dp_28, R.dimen.dp_4)
                : size(R.dimen.dp_400, R.dimen.dp_100, R.dimen.dp_60);
        return new int[]{Math.max(1, Math.min(width - margin, preferredWidth)),
                Math.max(1, Math.min(height - margin, preferredHeight))};
    }

    private int size(int... dimensions) {
        int result = 0;
        for (int dimension : dimensions)
            result += uiContext.getResources().getDimensionPixelSize(dimension);
        return result;
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context wrapped = ((ContextWrapper) context).getBaseContext();
            if (wrapped == context) break;
            context = wrapped;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private Rect availableWindowBounds() {
        if (host != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowMetrics metrics = host.getWindowManager().getCurrentWindowMetrics();
                Rect bounds = new Rect(metrics.getBounds());
                android.graphics.Insets fixedInsets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.captionBar());
                bounds.left += fixedInsets.left;
                bounds.top += fixedInsets.top;
                bounds.right -= fixedInsets.right;
                bounds.bottom -= fixedInsets.bottom;
                return bounds;
            }
            View decor = host.getWindow().getDecorView();
            if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                Rect bounds = new Rect(0, 0, decor.getWidth(), decor.getHeight());
                WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(decor);
                if (windowInsets != null) {
                    Insets fixedInsets = windowInsets.getInsetsIgnoringVisibility(
                            WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.captionBar());
                    bounds.left += fixedInsets.left;
                    bounds.top += fixedInsets.top;
                    bounds.right -= fixedInsets.right;
                    bounds.bottom -= fixedInsets.bottom;
                }
                return bounds;
            }
        }
        Configuration configuration = getContext().getResources().getConfiguration();
        float density = getContext().getResources().getDisplayMetrics().density;
        return new Rect(0, 0, Math.max(1, Math.round(configuration.screenWidthDp * density)),
                Math.max(1, Math.round(configuration.screenHeightDp * density)));
    }

    private void fitActionRow() {
        if (!sideBySide) return;
        LinearLayout actions = findViewById(R.id.health_date_actions);
        LinearLayout panel = findViewById(R.id.health_date_panel);
        if (actions.getParent() == panel || actions.getWidth() <= 0) return;
        float requiredWidth = 0;
        for (int i = 0; i < actions.getChildCount(); i++) {
            TextView button = (TextView) actions.getChildAt(i);
            LinearLayout.LayoutParams parameters = (LinearLayout.LayoutParams) button.getLayoutParams();
            requiredWidth += Math.ceil(button.getPaint().measureText(button.getText().toString()))
                    + button.getCompoundPaddingLeft() + button.getCompoundPaddingRight()
                    + parameters.leftMargin + parameters.rightMargin;
        }
        if (actions.getWidth() >= requiredWidth) return;
        // 窄横窗把三项操作移到两栏下方，保留文字尺寸和全部日历，不压缩按钮文字。
        ((ViewGroup) actions.getParent()).removeView(actions);
        LinearLayout.LayoutParams moved = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        moved.topMargin = size(R.dimen.dp_4);
        panel.addView(actions, moved);
    }

    private final class Days extends BaseAdapter {
        @Override
        public int getCount() {
            return 42;
        }

        @Override
        public Object getItem(int position) {
            return selection.dayAt(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return selection.dayAt(position) > 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView cell = convertView instanceof TextView ? (TextView) convertView
                    : (TextView) LayoutInflater.from(uiContext).inflate(R.layout.item_health_date_day, parent, false);
            int day = selection.dayAt(position);
            int rowHeight = Math.max(1, parent.getHeight() / 6);
            cell.setMinimumHeight(size(R.dimen.dp_0));
            cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
            float nominalSize = cell.getTag() instanceof Float ? (Float) cell.getTag() : cell.getTextSize();
            cell.setTag(nominalSize);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_PX, nominalSize);
            android.graphics.Paint.FontMetrics font = cell.getPaint().getFontMetrics();
            float width = Math.max(1, parent.getWidth() / 7f - cell.getCompoundPaddingLeft() - cell.getCompoundPaddingRight());
            float height = Math.max(1, rowHeight - cell.getCompoundPaddingTop() - cell.getCompoundPaddingBottom());
            float fit = Math.min(1f, Math.min(width / cell.getPaint().measureText("31"), height / (font.descent - font.ascent)));
            cell.setTextSize(TypedValue.COMPLEX_UNIT_PX, nominalSize * fit);
            boolean chosen = day > 0 && selection.month().atDay(day).equals(selection.selected());
            cell.setText(day == 0 ? "" : String.valueOf(day));
            cell.setEnabled(day > 0);
            cell.setBackgroundResource(chosen ? R.drawable.mc_button_primary : android.R.color.transparent);
            cell.setTextColor(ContextCompat.getColor(getContext(), chosen ? R.color.mc_white : R.color.mc_ink));
            cell.setAlpha(day > 0 ? 1f : 0f);
            cell.setContentDescription(day > 0 ? selection.month().atDay(day).toString() + (chosen ? "，已选中" : "") : "");
            return cell;
        }
    }
}
