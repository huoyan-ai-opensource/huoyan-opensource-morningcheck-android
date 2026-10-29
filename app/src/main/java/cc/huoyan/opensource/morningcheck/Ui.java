package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.widget.ArrayAdapter;

import java.util.Arrays;
import java.util.List;

/**
 * 通用视图绑定与动态业务颜色；视图结构、尺寸与背景均由 XML 资源定义。
 */
final class Ui {
    private Ui() {
    }

    static final int INK = 0xff293A51, MUTED = 0xff69788C, BLUE = 0xff3F3EED,
            ORANGE = 0xffC85B08, GREEN = 0xff137C64;

    static ArrayAdapter<String> spinnerAdapter(Context context, int entries) {
        return spinnerAdapter(context, Arrays.asList(context.getResources().getStringArray(entries)));
    }

    static ArrayAdapter<String> spinnerAdapter(Context context, List<String> entries) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.item_spinner_value, entries);
        adapter.setDropDownViewResource(R.layout.item_spinner_option);
        return adapter;
    }

    static ArrayAdapter<String> filterAdapter(Context context, List<String> entries, String label) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, R.layout.item_filter_value, entries) {
            @Override
            public android.view.View getView(int position, android.view.View convert, android.view.ViewGroup parent) {
                android.widget.TextView view = (android.widget.TextView) super.getView(position, convert, parent);
                view.setText(label + "  " + getItem(position));
                return view;
            }
        };
        adapter.setDropDownViewResource(R.layout.item_spinner_option);
        return adapter;
    }
}
