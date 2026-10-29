package cc.huoyan.opensource.morningcheck.hardware;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LightConfigPreferencesTest {
    private SharedPreferences preferences;

    @Before
    public void openIsolatedPreferences() {
        preferences = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("light-command-defaults-test", Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
    }

    @After
    public void clearIsolatedPreferences() {
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void upgradeRestoresCommandsWithoutChangingWiring() {
        preferences.edit().putString("light_mode", LightConfig.SERIAL)
                .putString("light_serial_path", "/dev/ttyS4").putString("light_baud", "9600")
                .putString("light_gpio", "122,148").putString("light_on_level", "1")
                .putString("light_off_level", "0")
                .putString("light_on_command", "A0 01 01 A0")
                .putString("light_off_command", "A0 01 00 A0").commit();

        LightConfig.restoreDefaultCommandsOnce(preferences);

        LightConfig config = LightConfig.read(preferences);
        assertEquals(LightConfig.DEFAULT_ON_COMMAND, config.onCommand);
        assertEquals(LightConfig.DEFAULT_OFF_COMMAND, config.offCommand);
        assertEquals(LightConfig.SERIAL, config.mode);
        assertEquals("/dev/ttyS4", config.path);
        assertEquals(9600, config.baud);
        assertEquals("122,148", preferences.getString("light_gpio", ""));
        assertEquals("1", preferences.getString("light_on_level", ""));
        assertEquals("0", preferences.getString("light_off_level", ""));
    }

    @Test
    public void laterStartupKeepsSavedCustomCommands() {
        LightConfig.restoreDefaultCommandsOnce(preferences);
        new LightConfig(LightConfig.SERIAL, "/dev/ttyS4", 115200, "", 0, 1,
                "HEX:A5 01", "HEX:A5 00").save(preferences);

        LightConfig.restoreDefaultCommandsOnce(preferences);

        LightConfig config = LightConfig.read(preferences);
        assertEquals("HEX:A5 01", config.onCommand);
        assertEquals("HEX:A5 00", config.offCommand);
    }
}
