package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.text.InputFilter;
import android.text.method.DigitsKeyListener;
import android.text.method.PasswordTransformationMethod;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.widget.AppCompatEditText;

/**
 * 密码窗口使用自己的数字键盘，避免全局输入法覆盖弹窗内的按键。
 */
public final class SecurityPasswordEditText extends AppCompatEditText {
    public SecurityPasswordEditText(Context context, AttributeSet attributes) {
        super(context, attributes);
        setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        setTransformationMethod(PasswordTransformationMethod.getInstance());
        setFilters(new InputFilter[]{new InputFilter.LengthFilter(SecuritySettings.MAX_PASSWORD_LENGTH)});
        setShowSoftInputOnFocus(false);
        setSaveEnabled(false);
        setLongClickable(false);
        setCursorVisible(false);
        setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }
        });
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        return false;
    }
}
