package cc.huoyan.opensource.morningcheck;

import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * 串口路径来自所用 SerialPortFinder；波特率来自该库 native getBaudrate 支持表。
 */
final class SerialChoices {
    private static final List<String> BAUD_RATES = Arrays.asList(
            "50", "75", "110", "134", "150", "200", "300", "600", "1200", "1800", "2400", "4800",
            "9600", "19200", "38400", "57600", "115200", "230400", "460800", "500000", "576000",
            "921600", "1000000", "1152000", "1500000", "2000000", "2500000", "3000000", "3500000", "4000000");

    private SerialChoices() {
    }

    static void bind(BaseMorningActivity host, TextView field, boolean path, Runnable changed) {
        bind(host, field, path, host.getString(R.string.i18n_b_serial_select_saved), changed);
    }

    static void bind(BaseMorningActivity host, TextView field, boolean path, String hint, Runnable changed) {
        field.setFocusable(true);
        final boolean[] reading = {false};
        final boolean[] detached = {false};
        final int[] generation = {0};
        final androidx.appcompat.app.AlertDialog[] selector = {null};
        final LoadingDialog[] loading = {null};
        Runnable close = () -> {
            generation[0]++;
            reading[0] = false;
            if (selector[0] != null) {
                selector[0].dismiss();
                selector[0] = null;
            }
            if (loading[0] != null) {
                loading[0].dismiss();
                loading[0] = null;
            }
        };
        androidx.lifecycle.DefaultLifecycleObserver observer = new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override
            public void onStop(androidx.lifecycle.LifecycleOwner owner) {
                close.run();
            }

            @Override
            public void onDestroy(androidx.lifecycle.LifecycleOwner owner) {
                close.run();
                owner.getLifecycle().removeObserver(this);
            }
        };
        host.getLifecycle().addObserver(observer);
        field.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                // 横竖屏重排不一定触发 onStop，旧字段销毁时也必须释放弹窗和异步请求。
                detached[0] = true;
                close.run();
                host.getLifecycle().removeObserver(observer);
                view.removeOnAttachStateChangeListener(this);
            }
        });
        field.setOnClickListener(v -> {
            if (detached[0] || !field.isAttachedToWindow() || !host.foreground || host.destroyed
                    || host.isFinishing() || reading[0] || selector[0] != null && selector[0].isShowing())
                return;
            int expectedGeneration = ++generation[0];
            BooleanSupplier current = () -> !detached[0] && generation[0] == expectedGeneration
                    && host.foreground && !host.destroyed && !host.isFinishing() && field.isAttachedToWindow();
            if (!path) {
                selector[0] = show(host, field, host.getString(R.string.i18n_b_baud_rate), hint, BAUD_RATES, current, changed);
                return;
            }
            reading[0] = true;
            LoadingDialog expectedLoading = new LoadingDialog(host);
            loading[0] = expectedLoading;
            expectedLoading.show(host.getString(R.string.i18n_b_reading_serials));
            host.runIo(() -> {
                try {
                    List<String> devices = new ArrayList<>(new TreeSet<>(Arrays.asList(
                            new android.serialport.SerialPortFinder().getAllDevicesPath())));
                    host.main.post(() -> {
                        expectedLoading.dismiss();
                        if (!current.getAsBoolean()) return;
                        loading[0] = null;
                        reading[0] = false;
                        selector[0] = show(host, field, host.getString(R.string.i18n_b_serial_path), hint, devices, current, changed);
                    });
                } catch (RuntimeException error) {
                    host.main.post(() -> {
                        expectedLoading.dismiss();
                        if (!current.getAsBoolean()) return;
                        loading[0] = null;
                        reading[0] = false;
                        host.notice(host.failure(error));
                    });
                }
            });
        });
    }

    private static androidx.appcompat.app.AlertDialog show(BaseMorningActivity host, TextView field, String title, String hint, List<String> values, BooleanSupplier current, Runnable changed) {
        return ImmersiveUi.show(DesignChoiceDialog.create(host, title, hint, values,
                values.indexOf(field.getText().toString()), index -> {
                    if (!current.getAsBoolean()) return;
                    field.setText(values.get(index));
                    changed.run();
                }));
    }
}
