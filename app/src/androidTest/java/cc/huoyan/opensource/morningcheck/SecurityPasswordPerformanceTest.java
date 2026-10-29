package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 固定测试材料仅在内存派生，不访问设备配置，也不记录密码或派生值。
 */
@RunWith(AndroidJUnit4.class)
public final class SecurityPasswordPerformanceTest {
    private static final String TAG = "SecurityPasswordPerf";

    @Test
    public void measureDefaultProviderCostsAndRepeatability() throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        Log.i(TAG, "PBKDF2 默认实现=" + factory.getProvider().getName());
        char[] password = "001234567890123456789012".toCharArray();
        byte[] salt = new byte[16];
        for (int index = 0; index < salt.length; index++) salt[index] = (byte) index;
        try {
            for (int bits : new int[]{128, 256}) {
                for (int iterations : new int[]{10_000, 20_000, 50_000}) {
                    long started = SystemClock.elapsedRealtime();
                    byte[] first = derive(factory, password, salt, iterations, bits);
                    long firstMillis = SystemClock.elapsedRealtime() - started;
                    started = SystemClock.elapsedRealtime();
                    byte[] repeated = derive(factory, password, salt, iterations, bits);
                    long repeatMillis = SystemClock.elapsedRealtime() - started;
                    try {
                        assertEquals(bits / 8, first.length);
                        assertTrue("相同输入必须得到相同派生结果", MessageDigest.isEqual(first, repeated));
                        Log.i(TAG, "轮数=" + iterations + " 输出位数=" + bits
                                + " 首次毫秒=" + firstMillis + " 重复毫秒=" + repeatMillis);
                    } finally {
                        Arrays.fill(first, (byte) 0);
                        Arrays.fill(repeated, (byte) 0);
                    }
                }
            }
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(salt, (byte) 0);
        }
    }

    @Test
    public void defaultProviderMatchesIndependentNumericVectorAndRejectsChangedPassword() throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        char[] password = "123456".toCharArray(), changedPassword = "123457".toCharArray();
        byte[] salt = {'s', 'a', 'l', 't'};
        byte[] expected = hex("089c7f098c22f037c1da60989b7103504f8dc262");
        byte[] actual = derive(factory, password, salt, 4096, 160);
        byte[] changed = derive(factory, changedPassword, salt, 4096, 160);
        try {
            assertTrue("必须匹配 Python hashlib 独立生成的数字口令向量", MessageDigest.isEqual(expected, actual));
            assertFalse("修改密码后不能匹配原派生结果", MessageDigest.isEqual(actual, changed));
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(changedPassword, '\0');
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(changed, (byte) 0);
        }
    }

    private static byte[] derive(SecretKeyFactory factory, char[] password, byte[] salt, int iterations, int bits)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
        try {
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] hex(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int index = 0; index < result.length; index++)
            result[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        return result;
    }
}
