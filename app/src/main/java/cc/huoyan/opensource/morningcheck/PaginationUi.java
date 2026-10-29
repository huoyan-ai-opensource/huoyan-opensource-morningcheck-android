package cc.huoyan.opensource.morningcheck;

import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import cc.huoyan.opensource.morningcheck.data.AppStore;

/**
 * 三种本地列表共用翻页边界与行内页码输入，读取仍由页面原有异步流程负责。
 */
final class PaginationUi {
    interface PageSelected {
        void selected(int page);
    }

    private final BaseMorningActivity host;
    private final View first, previous, next, last, jump;
    private final TextView info;
    private final EditText input;
    private final PageSelected selected;
    private final Runnable retry;
    private int page = 1, totalPages = 1;
    private boolean ready, failed;
    private AppStore.DataPage displayedData;

    PaginationUi(BaseMorningActivity host, View root, int firstId, int previousId,
                 int infoId, int inputId, int jumpId, int nextId, int lastId,
                 PageSelected selected, Runnable retry) {
        this.host = host;
        this.selected = selected;
        this.retry = retry;
        first = root.findViewById(firstId);
        previous = root.findViewById(previousId);
        info = root.findViewById(infoId);
        input = root.findViewById(inputId);
        jump = root.findViewById(jumpId);
        next = root.findViewById(nextId);
        last = root.findViewById(lastId);
        first.setOnClickListener(view -> navigate(1));
        previous.setOnClickListener(view -> navigate(page - 1));
        next.setOnClickListener(view -> navigate(page + 1));
        last.setOnClickListener(view -> navigate(totalPages));
        info.setOnClickListener(view -> {
            if (failed && available()) retry.run();
        });
        jump.setOnClickListener(view -> confirm());
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO
                    && (event == null || event.getKeyCode() != KeyEvent.KEYCODE_ENTER))
                return false;
            if (event == null || event.getAction() == KeyEvent.ACTION_UP) confirm();
            return true;
        });
    }

    void update(AppStore.DataPage data, boolean loading, boolean error) {
        boolean wasReady = ready;
        int previousPage = page;
        failed = error && !loading;
        ready = !loading && !error && data != null && data.total > 0;
        page = data == null ? 1 : Math.max(1, data.page);
        totalPages = data == null ? 1 : Math.max(1, data.totalPages);
        info.setText(error ? "点击重新读取"
                : loading || data == null ? ""
                  : host.getString(R.string.business_page_info, page, totalPages, data.total));
        info.setEnabled(failed);
        info.setContentDescription(info.getText());
        // 同一页重复绘制保留正在输入的草稿；新读取结果、页码变化或重新可用才显示当前页。
        boolean emptyResult = !loading && !error && data != null && data.total == 0;
        if (emptyResult || ready && (!wasReady || displayedData != data || previousPage != page))
            input.setText(Integer.toString(page));
        displayedData = data;
        input.setContentDescription(host.getString(R.string.pagination_page_input_description, totalPages));
        enable(input, ready);
        enable(first, ready && page > 1);
        enable(previous, ready && page > 1);
        enable(next, ready && page < totalPages);
        enable(last, ready && page < totalPages);
        enable(jump, ready);
    }

    private void navigate(int requestedPage) {
        if (!ready || !available() || requestedPage < 1
                || requestedPage > totalPages || requestedPage == page) return;
        selected.selected(requestedPage);
    }

    private void confirm() {
        if (!ready || !available()) return;
        String value = input.getText().toString().trim();
        if (!value.matches("[0-9]+")) {
            host.notice(host.getString(R.string.pagination_jump_invalid));
            return;
        }
        long requested;
        try {
            requested = Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            host.notice(host.getString(R.string.pagination_jump_out_of_range, totalPages));
            return;
        }
        if (requested < 1 || requested > totalPages) {
            host.notice(host.getString(R.string.pagination_jump_out_of_range, totalPages));
            return;
        }
        if (requested == page) {
            input.setText(Integer.toString(page));
            return;
        }
        navigate((int) requested);
    }

    private boolean available() {
        return host.foreground && !host.destroyed && !host.isFinishing();
    }

    private static void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : .45f);
    }
}
