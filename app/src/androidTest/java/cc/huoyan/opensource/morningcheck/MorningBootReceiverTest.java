package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** 使用独立配置并截获启动请求，不打开现场晨检或修改真实设备设置。 */
@RunWith(AndroidJUnit4.class)
public final class MorningBootReceiverTest {
    private SharedPreferences preferences;
    private CaptureContext context;

    @Before
    public void isolateBootConfiguration() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = target.getSharedPreferences("codex_boot_receiver_test", Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        context = new CaptureContext(target);
    }

    @After
    public void clearBootConfiguration() {
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void defaultAndDisabledOptionsDoNotLaunchOnBoot() {
        MorningBootReceiver receiver = new MorningBootReceiver();
        receiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(context.started);
        assertTrue(preferences.edit().putBoolean(SecuritySettings.AUTO_CHECK_KEY, false).commit());
        receiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(context.started);
    }

    @Test
    public void enabledBootStartsCheckWithoutHomeOrSdkPreconditions() {
        assertTrue(preferences.edit().putBoolean(SecuritySettings.AUTO_CHECK_KEY, true).commit());
        new MorningBootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNotNull(context.started);
        assertEquals(CheckActivity.class.getName(), context.started.getComponent().getClassName());
        assertNotEquals(0, context.started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        assertNotEquals(0, context.started.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    @Test
    public void unrelatedBroadcastsCannotStartCheckEvenWhenEnabled() {
        assertTrue(preferences.edit().putBoolean(SecuritySettings.AUTO_CHECK_KEY, true).commit());
        MorningBootReceiver receiver = new MorningBootReceiver();
        receiver.onReceive(context, null);
        receiver.onReceive(context, new Intent(Intent.ACTION_USER_PRESENT));
        receiver.onReceive(context, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        assertNull(context.started);
    }

    @Test
    public void deniedBackgroundStartDoesNotCrashTheBootBroadcast() {
        assertTrue(preferences.edit().putBoolean(SecuritySettings.AUTO_CHECK_KEY, true).commit());
        context.denyStart = true;
        new MorningBootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertEquals(1, context.attempts);
        assertNull(context.started);
    }

    private final class CaptureContext extends ContextWrapper {
        Intent started;
        boolean denyStart;
        int attempts;

        CaptureContext(Context base) {
            super(base);
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }

        @Override
        public void startActivity(Intent intent) {
            attempts++;
            if (denyStart) throw new SecurityException("测试系统后台启动限制");
            started = new Intent(intent);
        }
    }
}
