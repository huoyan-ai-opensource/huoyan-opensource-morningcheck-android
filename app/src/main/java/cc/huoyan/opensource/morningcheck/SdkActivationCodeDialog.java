package cc.huoyan.opensource.morningcheck;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import cc.huoyan.opensource.morningcheck.domain.ActivationCodeInput;

/**
 * 激活码键区嵌入授权弹窗，实时编辑原字段；宿主负责连接、授权和扫码。
 */
public final class SdkActivationCodeDialog extends DialogFragment {
    public static final String TAG = "sdk_activation_code";
    public static final String RESULT_KEY = "sdk_activation_code_result";
    private static final int REGULAR = 0;
    private static final int EXPANDED = 1;
    private String draft = "", error = "";
    private int selectionStart, selectionEnd;
    private int rejectedSelectionStart = -1, rejectedSelectionEnd = -1;
    private boolean submitted, binding, rendering;
    private View panel, backspace;
    private SdkActivationCodeEditText editor;
    private TextView[] segments;
    private LinearLayout keyboard;
    private View hostDecor;
    private final View.OnLayoutChangeListener hostLayout = (view, left, top, right, bottom,
                                                            oldLeft, oldTop, oldRight, oldBottom) -> {
        if (right > left && bottom > top
                && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop))
            resizeWindow();
    };
    private final Runnable repeatDelete = new Runnable() {
        @Override
        public void run() {
            if (backspace == null || !backspace.isPressed() || editor == null || submitted) return;
            deletePrevious();
            backspace.postDelayed(this, 80);
        }
    };

    /**
     * 同步登记弹窗，避免快速点击生成多个输入层。
     */
    public static void open(FragmentManager manager) {
        open(manager, "");
    }

    public static void open(FragmentManager manager, String initialCode) {
        open(manager, initialCode, false);
    }

    public static void open(FragmentManager manager, String initialCode, boolean switching) {
        if (manager.isStateSaved() || manager.findFragmentByTag(TAG) != null) return;
        SdkActivationCodeDialog dialog = new SdkActivationCodeDialog();
        Bundle args = new Bundle();
        args.putString("initialCode", initialCode);
        args.putBoolean("switching", switching);
        dialog.setArguments(args);
        dialog.showNow(manager, TAG);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null && getArguments() != null) {
            draft = ActivationCodeInput.restore(getArguments().getString("initialCode", ""));
            selectionStart = selectionEnd = draft.length();
        }
        if (state != null) {
            draft = ActivationCodeInput.restore(state.getString("draft", ""));
            selectionStart = Math.max(0, Math.min(draft.length(), state.getInt("selectionStart", draft.length())));
            selectionEnd = Math.max(0, Math.min(draft.length(), state.getInt("selectionEnd", selectionStart)));
            error = state.getString("error", "");
            submitted = state.getBoolean("submitted");
        }
        setCancelable(!submitted);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle state) {
        AppCompatDialog dialog = new AppCompatDialog(requireContext());
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        Configuration configuration = new Configuration(getResources().getConfiguration());
        configuration.smallestScreenWidthDp = 1;
        Context inputContext = new androidx.appcompat.view.ContextThemeWrapper(
                requireContext().createConfigurationContext(configuration), R.style.AppTheme);
        panel = LayoutInflater.from(inputContext).inflate(R.layout.dialog_sdk_activation_code, null);
        dialog.setContentView(panel);
        editor = panel.findViewById(R.id.activation_code_editor);
        keyboard = panel.findViewById(R.id.activation_code_keyboard);
        segments = new TextView[]{panel.findViewById(R.id.activation_code_segment_1),
                panel.findViewById(R.id.activation_code_segment_2), panel.findViewById(R.id.activation_code_segment_3),
                panel.findViewById(R.id.activation_code_segment_4)};
        editor.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            String inserted = ActivationCodeInput.decode(source.subSequence(start, end).toString());
            if (inserted != null && ActivationCodeInput.accepts(dest.subSequence(0, dstart) + inserted + dest.subSequence(dend, dest.length()))) {
                rejectedSelectionStart = rejectedSelectionEnd = -1;
                return inserted;
            }
            // 空选区拒绝不会触发文字回调，不能留下一次替换的选区恢复标记。
            rejectedSelectionStart = dstart == dend ? -1 : editor.getSelectionStart();
            rejectedSelectionEnd = dstart == dend ? -1 : editor.getSelectionEnd();
            if (!binding) {
                error = getString(R.string.activation_code_invalid);
                renderError();
            }
            // 拒绝替换时返回原选区，不能因为非法粘贴而丢掉已输入的码。
            return dest.subSequence(dstart, dend);
        }});
        editor.bindSegments(segments, () -> {
            if (!binding && !submitted) rememberEditorState();
            render();
        });
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                boolean changed = !draft.equals(value.toString());
                draft = value.toString();
                if (!binding && changed && rejectedSelectionStart < 0) error = "";
                render();
            }

            @Override
            public void afterTextChanged(Editable value) {
                if (rejectedSelectionStart < 0) return;
                int start = rejectedSelectionStart, end = rejectedSelectionEnd;
                rejectedSelectionStart = rejectedSelectionEnd = -1;
                editor.setSelection(Math.min(value.length(), start), Math.max(0, Math.min(value.length(), end)));
            }
        });
        binding = true;
        editor.setText(draft);
        editor.setSelection(selectionStart, selectionEnd);
        binding = false;
        panel.findViewById(R.id.activation_code_clear).setOnClickListener(view -> {
            if (!submitted) editor.setText("");
        });
        panel.findViewById(R.id.activation_code_cancel).setOnClickListener(view -> {
            if (!submitted) dismiss();
        });
        panel.findViewById(R.id.activation_code_activate).setOnClickListener(view -> deliver(false));
        panel.findViewById(R.id.activation_code_scan).setOnClickListener(view -> deliver(true));
        createKeyboard(keyboard);
        bindKeyboard();
        render();
        return dialog;
    }

    /**
     * 宿主拒绝连接或授权条件时保留草稿，并允许修正或重试。
     */
    public void onSubmitRejected(String message) {
        submitted = false;
        error = message == null ? "" : message;
        setCancelable(true);
        if (panel != null) {
            render();
            resizeWindow();
        }
    }

    private void deliver(boolean scan) {
        if (submitted || getParentFragmentManager().isStateSaved()) return;
        String code = ActivationCodeInput.normalize(draft);
        if (!scan && code == null) return;
        rememberEditorState();
        stopRepeatingDelete();
        submitted = true;
        setCancelable(false);
        render();
        Bundle result = new Bundle();
        result.putString("action", scan ? "scan" : "activate");
        if (!scan) result.putString("code", code);
        // 不自动关闭：宿主拒绝请求或扫码取消时，当前草稿仍在这个 Fragment 中。
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
    }

    private void render() {
        if (editor == null || segments == null || rendering) return;
        rendering = true;
        try {
            int position = editor.isEnabled() ? Math.max(0, editor.getSelectionStart()) : selectionStart;
            int active = draft.length() == 16 && position == 16 ? -1 : Math.min(3, position / 4);
            for (int i = 0; i < segments.length; i++) {
                int start = Math.min(draft.length(), i * 4);
                String part = draft.substring(start, Math.min(draft.length(), start + 4));
                segments[i].setText(part.isEmpty() ? "····" : part);
                segments[i].setTextColor(ContextCompat.getColor(requireContext(),
                        part.isEmpty() ? R.color.mc_muted : R.color.mc_ink));
                segments[i].setBackgroundResource(i == active
                        ? R.drawable.activation_code_segment_active : R.drawable.activation_code_segment);
            }
            View activate = panel.findViewById(R.id.activation_code_activate);
            activate.setEnabled(!submitted && ActivationCodeInput.complete(draft));
            ((TextView) activate).setTextColor(ContextCompat.getColor(requireContext(),
                    activate.isEnabled() ? R.color.mc_white : R.color.mc_muted));
            if (editor.isEnabled() == submitted) {
                if (submitted) rememberEditorState();
                boolean previousBinding = binding;
                binding = true;
                try {
                    editor.setEnabled(!submitted);
                    if (!submitted) focusEditorAtSavedSelection();
                } finally {
                    binding = previousBinding;
                }
            }
            panel.findViewById(R.id.activation_code_scan).setEnabled(!submitted);
            panel.findViewById(R.id.activation_code_clear).setEnabled(!submitted);
            panel.findViewById(R.id.activation_code_cancel).setEnabled(!submitted);
            if (keyboard != null) setEnabled(keyboard, !submitted);
            renderError();
            editor.invalidate();
        } finally {
            rendering = false;
        }
    }

    /**
     * 禁用输入会让系统移动选区；提交前保留用户正在编辑的位置。
     */
    private void rememberEditorState() {
        if (editor == null) return;
        draft = editor.getText().toString();
        if (editor.isEnabled() && editor.getSelectionStart() >= 0 && editor.getSelectionEnd() >= 0) {
            selectionStart = Math.min(draft.length(), editor.getSelectionStart());
            selectionEnd = Math.min(draft.length(), editor.getSelectionEnd());
        }
    }

    private void focusEditorAtSavedSelection() {
        if (editor == null || submitted) return;
        boolean previousBinding = binding;
        binding = true;
        try {
            editor.requestFocus();
            editor.setSelection(Math.max(0, Math.min(editor.length(), selectionStart)),
                    Math.max(0, Math.min(editor.length(), selectionEnd)));
        } finally {
            binding = previousBinding;
        }
    }

    private String shownError = "";

    private void renderError() {
        if (panel == null) return;
        TextView message = panel.findViewById(R.id.activation_code_error);
        message.setVisibility(View.GONE);
        if (!error.isEmpty() && !error.equals(shownError))
            ToastEx.showFailureToast(requireContext(), error);
        shownError = error;
        View hint = panel.findViewWithTag("activation-alphabet-hint");
        if (hint != null) hint.setVisibility(error.isEmpty() ? View.VISIBLE : View.INVISIBLE);
    }

    private static void setEnabled(ViewGroup group, boolean enabled) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setEnabled(enabled);
            if (child instanceof ViewGroup) setEnabled((ViewGroup) child, enabled);
        }
    }

    private void bindKeyboard() {
        for (int i = 0; i < keyboard.getChildCount(); i++) {
            ViewGroup row = (ViewGroup) keyboard.getChildAt(i);
            for (int j = 0; j < row.getChildCount(); j++) {
                View key = row.getChildAt(j);
                if ("activation-backspace".equals(key.getTag())) {
                    backspace = key;
                    key.setOnClickListener(view -> deletePrevious());
                    key.setOnLongClickListener(view -> {
                        deletePrevious();
                        view.postDelayed(repeatDelete, 80);
                        return true;
                    });
                    key.setOnTouchListener((view, event) -> {
                        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                            view.removeCallbacks(repeatDelete);
                        return false;
                    });
                } else
                    key.setOnClickListener(view -> insert(((TextView) view).getText().toString()));
            }
        }
    }

    private void insert(String value) {
        if (editor == null || submitted) return;
        int start = Math.max(0, Math.min(editor.length(), Math.min(editor.getSelectionStart(), editor.getSelectionEnd())));
        int end = Math.max(start, Math.min(editor.length(), Math.max(editor.getSelectionStart(), editor.getSelectionEnd())));
        String inserted = ActivationCodeInput.decode(value);
        if (inserted == null || !ActivationCodeInput.accepts(editor.getText().subSequence(0, start) + inserted + editor.getText().subSequence(end, editor.length()))) {
            error = getString(R.string.activation_code_invalid);
            renderError();
            return;
        }
        editor.getText().replace(start, end, inserted);
        editor.requestFocus();
        // Editable 只更新文本和 span，专用键盘需要显式收起选区并移到新字符之后。
        editor.setSelection(start + inserted.length());
    }

    private void deletePrevious() {
        if (editor == null || submitted) return;
        int start = Math.max(0, Math.min(editor.length(), Math.min(editor.getSelectionStart(), editor.getSelectionEnd())));
        int end = Math.max(start, Math.min(editor.length(), Math.max(editor.getSelectionStart(), editor.getSelectionEnd())));
        if (start == end && start > 0) start--;
        if (start < end) {
            editor.getText().delete(start, end);
            editor.requestFocus();
            editor.setSelection(start);
        }
    }

    /**
     * XML 面板与配置测试共用真实键盘构建，不复制布局算法。
     */
    static void createKeyboard(View panel) {
        LinearLayout keyboard = panel.findViewById(R.id.activation_code_keyboard);
        keyboard.removeAllViews();
        String[] rows = {"123456789", "QWERTYUP", "ASDFGHJKL", "ZXCVBNM"};
        int gap = panel.getResources().getDimensionPixelSize(R.dimen.dp_2);
        for (int i = 0; i < rows.length; i++) {
            LinearLayout row = new LinearLayout(panel.getContext());
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            if (i > 0) params.topMargin = gap;
            keyboard.addView(row, params);
            for (int j = 0; j < rows[i].length(); j++) {
                TextView key = (TextView) LayoutInflater.from(panel.getContext()).inflate(R.layout.item_activation_code_key, row, false);
                String character = rows[i].substring(j, j + 1);
                key.setText(character);
                key.setTag("activation-key-" + character);
                key.setFocusable(false);
                row.addView(key);
            }
            if (i == 3) {
                ImageButton delete = new ImageButton(panel.getContext());
                delete.setImageResource(R.drawable.activation_code_backspace);
                delete.setBackgroundResource(R.drawable.activation_code_secondary);
                delete.setContentDescription(panel.getContext().getString(R.string.activation_code_backspace));
                delete.setTag("activation-backspace");
                delete.setFocusable(false);
                row.addView(delete, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));
            }
        }
    }

    /**
     * 按设计统一为四段输入、四行键盘与固定底部动作。
     */
    static void fitPanel(View panel, int width, int height, int mode) {
        Context context = panel.getContext();
        boolean expanded = mode == EXPANDED;
        boolean spacious = expanded && height >= dimension(context, R.dimen.dp_500) + dimension(context, R.dimen.dp_20);
        boolean shortPanel = !expanded && height < dimension(context, R.dimen.dp_360);
        int padding = dimension(context, spacious ? R.dimen.dp_16 : expanded ? R.dimen.dp_12
                : shortPanel ? R.dimen.dp_6 : R.dimen.dp_8);
        int gap = dimension(context, spacious ? R.dimen.dp_12 : expanded ? R.dimen.dp_8
                : shortPanel ? R.dimen.dp_4 : R.dimen.dp_9);
        int contentWidth = width - padding * 2;
        int headerHeight = dimension(context, spacious ? R.dimen.dp_48 : expanded ? R.dimen.dp_44 : R.dimen.dp_30);
        int inputHeight = dimension(context, spacious ? R.dimen.dp_64 : expanded ? R.dimen.dp_52
                : shortPanel ? R.dimen.dp_36 : R.dimen.dp_44);
        int metaHeight = dimension(context, spacious ? R.dimen.dp_28 : expanded ? R.dimen.dp_24 : R.dimen.dp_20);
        int errorHeight = dimension(context, expanded ? R.dimen.dp_44 : R.dimen.dp_30);
        int actionHeight = dimension(context, spacious ? R.dimen.dp_52 : expanded ? R.dimen.dp_44 : R.dimen.dp_34);
        int inputTextSize = spacious ? R.dimen.sp_24 : expanded ? R.dimen.sp_20 : R.dimen.sp_12;
        int keyTextSize = spacious ? R.dimen.sp_22 : expanded ? R.dimen.sp_18 : R.dimen.sp_12;
        int actionTextSize = spacious ? R.dimen.sp_18 : expanded ? R.dimen.sp_16 : R.dimen.sp_12;
        int linkTextSize = expanded ? R.dimen.sp_14 : R.dimen.sp_10;
        int formHeight = headerHeight + metaHeight + inputHeight + gap * 2;
        int keyHeight = Math.max(dimension(context, R.dimen.dp_60),
                height - padding * 2 - formHeight - errorHeight - actionHeight - gap * 3);
        panel.setPadding(padding, padding, padding, padding);
        LinearLayout body = panel.findViewById(R.id.activation_code_body);
        LinearLayout form = panel.findViewById(R.id.activation_code_form);
        LinearLayout keys = panel.findViewById(R.id.activation_code_keyboard);
        LinearLayout header = panel.findViewById(R.id.activation_code_header);
        View metadata = panel.findViewById(R.id.activation_code_metadata);
        TextView error = panel.findViewById(R.id.activation_code_error);
        TextView clear = panel.findViewById(R.id.activation_code_clear);
        TextView scan = panel.findViewById(R.id.activation_code_scan);
        LinearLayout actions = panel.findViewById(R.id.activation_code_actions);
        if (scan.getParent() != header) {
            ((ViewGroup) scan.getParent()).removeView(scan);
            header.addView(scan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight));
            form.removeView(metadata);
            form.addView(metadata, 1);
            form.removeView(error);
            ((ViewGroup) clear.getParent()).removeView(clear);
            LinearLayout statusLine = new LinearLayout(context);
            statusLine.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout statusText = new FrameLayout(context);
            TextView hint = new TextView(context);
            hint.setTag("activation-alphabet-hint");
            hint.setText(R.string.activation_code_rules);
            hint.setTextColor(ContextCompat.getColor(context, R.color.mc_muted));
            hint.setGravity(Gravity.CENTER_VERTICAL);
            textSize(hint, R.dimen.sp_10);
            statusText.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            error.setGravity(Gravity.CENTER_VERTICAL);
            statusText.addView(error, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            statusLine.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            statusLine.addView(clear, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            body.addView(statusLine);
        }
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(contentWidth, formHeight + keyHeight + errorHeight + gap * 2));
        form.setLayoutParams(new LinearLayout.LayoutParams(contentWidth, formHeight));
        header.setLayoutParams(new LinearLayout.LayoutParams(contentWidth, headerHeight));
        LinearLayout.LayoutParams metadataParams = new LinearLayout.LayoutParams(contentWidth, metaHeight);
        metadataParams.topMargin = gap;
        metadata.setLayoutParams(metadataParams);
        metadata.setVisibility(View.VISIBLE);
        panel.findViewById(R.id.activation_code_rules).setVisibility(View.VISIBLE);
        ((LinearLayout.LayoutParams) scan.getLayoutParams()).height = headerHeight;
        scan.setMinimumWidth(dimension(context, expanded ? R.dimen.dp_100 : R.dimen.dp_60));
        clear.setMinimumWidth(dimension(context, expanded ? R.dimen.dp_60 : R.dimen.dp_44));
        FrameLayout input = panel.findViewById(R.id.activation_code_input_row);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(contentWidth, inputHeight);
        inputParams.topMargin = gap;
        input.setLayoutParams(inputParams);
        int segmentWidth = (contentWidth - gap * 3) / 4;
        int[] ids = {R.id.activation_code_segment_1, R.id.activation_code_segment_2,
                R.id.activation_code_segment_3, R.id.activation_code_segment_4};
        for (int i = 0; i < ids.length; i++) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(segmentWidth, inputHeight);
            params.leftMargin = i * (segmentWidth + gap);
            input.findViewById(ids[i]).setLayoutParams(params);
            textSize(input.findViewById(ids[i]), inputTextSize);
        }
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(contentWidth, keyHeight);
        keyParams.topMargin = gap;
        keys.setLayoutParams(keyParams);
        fitKeyboard(keys, contentWidth, keyTextSize);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(contentWidth, errorHeight);
        errorParams.topMargin = gap;
        ((View) clear.getParent()).setLayoutParams(errorParams);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(contentWidth, actionHeight);
        actionParams.topMargin = gap;
        actions.setLayoutParams(actionParams);
        for (int id : new int[]{R.id.activation_code_cancel, R.id.activation_code_activate}) {
            TextView action = panel.findViewById(id);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) action.getLayoutParams();
            params.leftMargin = id == R.id.activation_code_cancel ? 0 : gap;
            action.setLayoutParams(params);
            action.setMinimumHeight(0);
            textSize(action, actionTextSize);
        }
        textSize(scan, linkTextSize);
        textSize(clear, linkTextSize);
        textSize(panel.findViewWithTag("activation-alphabet-hint"), spacious ? R.dimen.sp_14 : R.dimen.sp_10);
        scan.setBackgroundResource(R.drawable.mc_text_action);
        scan.setTextColor(ContextCompat.getColor(context, R.color.mc_blue));
        scan.setText(context.getString(R.string.i18n_b_scan_activate));
        scan.setMinimumHeight(0);
        clear.setMinimumHeight(0);
        textSize(panel.findViewById(R.id.activation_code_title), spacious ? R.dimen.sp_24
                : expanded ? R.dimen.sp_20 : R.dimen.sp_16);
        ((TextView) panel.findViewById(R.id.activation_code_title)).setText(context.getString(R.string.i18n_b_enter_activation));
        textSize(panel.findViewById(R.id.activation_code_rules), spacious ? R.dimen.sp_14
                : expanded ? R.dimen.sp_12 : R.dimen.sp_11);
        ((TextView) panel.findViewById(R.id.activation_code_rules)).setText(context.getString(R.string.i18n_b_activation_groups));
        textSize(error, linkTextSize);
    }

    static int[] panelSize(int width, int height, Context context) {
        int inset = dimension(context, R.dimen.dp_12);
        // 大横屏使用独立尺寸档；仍以基础 dimen 计算，避免 sw 资源与窗口适配重复放大。
        if (width > height && width >= dimension(context, R.dimen.dp_300) * 2
                && height >= dimension(context, R.dimen.dp_480)) {
            int panelWidth = Math.min(dimension(context, R.dimen.dp_400) * 2, Math.round(width * 0.8f));
            int panelHeight = dimension(context, R.dimen.dp_500) + dimension(context, R.dimen.dp_60);
            return new int[]{Math.min(panelWidth, width - inset * 2),
                    Math.min(panelHeight, height - inset * 2), EXPANDED};
        }
        int panelWidth = Math.max(1, Math.min(dimension(context,
                width > height ? R.dimen.dp_472 : R.dimen.dp_350), width - inset * 2));
        int preferredHeight = dimension(context, R.dimen.dp_8) * 2 + dimension(context, R.dimen.dp_30) * 2
                + dimension(context, R.dimen.dp_16) + dimension(context, R.dimen.dp_44)
                + dimension(context, R.dimen.dp_34) + dimension(context, R.dimen.dp_36) * 4
                + dimension(context, R.dimen.dp_3) * 3 + dimension(context, R.dimen.dp_9) * 5;
        return new int[]{panelWidth, Math.max(1, Math.min(preferredHeight, height - inset * 2)), REGULAR};
    }

    private static int dimension(Context context, int id) {
        return context.getResources().getDimensionPixelSize(id);
    }

    private static void textSize(TextView view, int id) {
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimension(view.getContext(), id));
    }

    /**
     * 保留原字符键、编辑及退格监听，按设计的键位顺序重排。
     */
    private static void fitKeyboard(LinearLayout keyboard, int width, int textSizeId) {
        Context context = keyboard.getContext();
        int gap = dimension(context, R.dimen.dp_3);
        java.util.Map<String, View> keys = new java.util.HashMap<>();
        for (int i = 0; i < keyboard.getChildCount(); i++) {
            ViewGroup row = (ViewGroup) keyboard.getChildAt(i);
            while (row.getChildCount() > 0) {
                View key = row.getChildAt(0);
                row.removeView(key);
                keys.put(String.valueOf(key.getTag()), key);
            }
        }
        keyboard.removeAllViews();
        keyboard.setPadding(0, 0, 0, 0);
        keyboard.setBackgroundResource(android.R.color.transparent);
        String[] rows = {"123456789", "QWERTYUP", "ASDFGHJKL", "ZXCVBNM"};
        int keyWidth = (width - gap * 8) / 9;
        for (int i = 0; i < rows.length; i++) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            if (i > 0) rowParams.topMargin = gap;
            keyboard.addView(row, rowParams);
            for (int j = 0; j < rows[i].length(); j++) {
                TextView key = (TextView) keys.get("activation-key-" + rows[i].charAt(j));
                textSize(key, textSizeId);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(keyWidth, ViewGroup.LayoutParams.MATCH_PARENT);
                if (j > 0) params.leftMargin = gap;
                row.addView(key, params);
            }
            if (i == 3) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(keyWidth * 2 + gap, ViewGroup.LayoutParams.MATCH_PARENT);
                params.leftMargin = gap;
                row.addView(keys.get("activation-backspace"), params);
            }
        }
    }

    boolean isKeyboardShowing() {
        return keyboard != null && keyboard.isShown();
    }

    View keyboardView() {
        return keyboard;
    }

    private void stopRepeatingDelete() {
        if (backspace != null) {
            backspace.removeCallbacks(repeatDelete);
            backspace.setPressed(false);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // Android 10 及以前的配置回调可能先于宿主测量；实际尺寸变化后再次适配。
        hostDecor = requireActivity().getWindow().getDecorView();
        hostDecor.addOnLayoutChangeListener(hostLayout);
        resizeWindow();
        focusEditorAtSavedSelection();
        render();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration configuration) {
        super.onConfigurationChanged(configuration);
        resizeWindow();
    }

    private void resizeWindow() {
        if (!isAdded() || getDialog() == null || panel == null) return;
        Window window = getDialog().getWindow();
        if (window == null) return;
        Rect bounds;
        if (Build.VERSION.SDK_INT >= 30)
            bounds = requireActivity().getWindowManager().getCurrentWindowMetrics().getBounds();
        else {
            View decor = requireActivity().getWindow().getDecorView();
            bounds = new Rect(0, 0, decor.getWidth() > 0 ? decor.getWidth() : getResources().getDisplayMetrics().widthPixels,
                    decor.getHeight() > 0 ? decor.getHeight() : getResources().getDisplayMetrics().heightPixels);
        }
        int[] size = panelSize(bounds.width(), bounds.height(), panel.getContext());
        int width = size[0], height = size[1];
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setDimAmount(0.4f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        window.setLayout(width, height);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = Gravity.CENTER;
        attributes.y = 0;
        window.setAttributes(attributes);
        ImmersiveUi.enable(window);
        fitPanel(panel, width, height, size[2]);
        if (getArguments() != null && getArguments().getBoolean("switching")) {
            ((TextView) panel.findViewById(R.id.activation_code_title)).setText(R.string.model_switch_code_title);
            ((TextView) panel.findViewById(R.id.activation_code_rules)).setText(R.string.model_switch_code_hint);
            ((TextView) panel.findViewById(R.id.activation_code_scan)).setText(R.string.model_switch_code_scan);
        }
        panel.requestLayout();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        rememberEditorState();
        out.putString("draft", draft);
        out.putInt("selectionStart", selectionStart);
        out.putInt("selectionEnd", selectionEnd);
        out.putString("error", error);
        out.putBoolean("submitted", submitted);
        super.onSaveInstanceState(out);
    }

    @Override
    public void onStop() {
        if (hostDecor != null) hostDecor.removeOnLayoutChangeListener(hostLayout);
        hostDecor = null;
        stopRepeatingDelete();
        super.onStop();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (isAdded()) {
            Bundle result = new Bundle();
            result.putString("action", "dismissed");
            getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        }
    }

    @Override
    public void onDestroyView() {
        stopRepeatingDelete();
        keyboard = null;
        if (backspace != null) backspace.removeCallbacks(repeatDelete);
        rememberEditorState();
        if (editor != null) editor.bindSegments(null, null);
        panel = backspace = null;
        editor = null;
        segments = null;
        super.onDestroyView();
    }
}
