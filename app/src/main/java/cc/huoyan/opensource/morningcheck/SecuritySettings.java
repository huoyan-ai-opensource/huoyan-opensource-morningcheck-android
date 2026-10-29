package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 自动进入晨检与安全密码共用本机设备配置，密码校验和修改必须在后台执行。
 */
public final class SecuritySettings {
    public static final String AUTO_CHECK_KEY = "auto_enter_check";
    static final int MIN_PASSWORD_LENGTH = 6, MAX_PASSWORD_LENGTH = 32;
    private static final String PASSWORD_KEY = "security_password_hash";
    private static final String HASH_VERSION = "pbkdf2-sha1";
    // Android 的 BC 实现按真机耗时取固定成本，023D 单次约 1.5 秒，避免退出验证长时间占用存储队列。
    private static final int ITERATIONS = 50_000, SALT_BYTES = 16, HASH_BITS = 128;
    private static final Object PASSWORD_LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecuritySettings() {
    }

    public static boolean isAutoCheckEnabled(Context context) {
        return preferences(context).getBoolean(AUTO_CHECK_KEY, false);
    }

    public static void setAutoCheckEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(AUTO_CHECK_KEY, enabled).apply();
    }

    public static boolean verify(Context context, String value) {
        char[] password = value == null ? new char[0] : value.toCharArray();
        try {
            return verify(context, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static boolean verify(Context context, char[] password) {
        if (!validPassword(password)) return false;
        synchronized (PASSWORD_LOCK) {
            SharedPreferences prefs = preferences(context);
            if (prefs.contains(PASSWORD_KEY)) return matchesPassword(prefs.getString(PASSWORD_KEY, ""), password);
            if (!matchesInitialPassword(password)) return false;
            // 首次验证只对已确认的默认密码派生一次；已有损坏值不进入默认密码分支。
            if (!prefs.edit().putString(PASSWORD_KEY, encodePassword(password)).commit())
                throw new IllegalStateException("安全密码初始化失败，请重试");
            return true;
        }
    }

    public static boolean changePassword(Context context, String original, String newValue) {
        char[] oldPassword = original == null ? new char[0] : original.toCharArray();
        char[] newPassword = newValue == null ? new char[0] : newValue.toCharArray();
        try {
            return changePassword(context, oldPassword, newPassword);
        } finally {
            Arrays.fill(oldPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }

    static boolean changePassword(Context context, char[] original, char[] newValue) {
        if (!validPassword(newValue)) throw new IllegalArgumentException("安全密码需为 6–32 位数字");
        if (!validPassword(original)) return false;
        synchronized (PASSWORD_LOCK) {
            SharedPreferences prefs = preferences(context);
            boolean correct = prefs.contains(PASSWORD_KEY)
                    ? matchesPassword(prefs.getString(PASSWORD_KEY, ""), original) : matchesInitialPassword(original);
            if (!correct) return false;
            if (!prefs.edit().putString(PASSWORD_KEY, encodePassword(newValue)).commit())
                throw new IllegalStateException("安全密码保存失败，请重试");
            return true;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("device", Context.MODE_PRIVATE);
    }

    private static boolean matchesInitialPassword(char[] value) {
        char[] initial = {'1', '2', '3', '4', '5', '6'};
        try {
            int difference = value.length ^ initial.length;
            for (int index = 0; index < initial.length; index++)
                difference |= initial[index] ^ (index < value.length ? value[index] : 0);
            return difference == 0;
        } finally {
            Arrays.fill(initial, '\0');
        }
    }

    static boolean validPassword(char[] value) {
        if (value == null || value.length < MIN_PASSWORD_LENGTH || value.length > MAX_PASSWORD_LENGTH) return false;
        for (char character : value)
            if (character < '0' || character > '9') return false;
        return true;
    }

    static String encodePassword(char[] value) {
        if (!validPassword(value)) throw new IllegalArgumentException("安全密码需为 6–32 位数字");
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(value, salt);
        try {
            return HASH_VERSION + "$" + ITERATIONS + "$" + hex(salt) + "$" + hex(hash);
        } finally {
            Arrays.fill(hash, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    static boolean matchesPassword(String encoded, char[] value) {
        if (!validPassword(value) || encoded == null || encoded.length() > 160) return false;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !HASH_VERSION.equals(parts[0]) || !String.valueOf(ITERATIONS).equals(parts[1]))
            return false;
        byte[] salt = unhex(parts[2], SALT_BYTES), expected = unhex(parts[3], HASH_BITS / 8);
        if (salt == null || expected == null) return false;
        byte[] actual = derive(value, salt);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    private static byte[] derive(char[] value, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(value, salt, ITERATIONS, HASH_BITS);
        try {
            // 使用 Android 5 已支持的算法，不依赖较新系统提供的 SHA256 变体。
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("安全密码校验不可用，请重试", error);
        } finally {
            spec.clearPassword();
        }
    }

    private static String hex(byte[] value) {
        char[] digits = "0123456789abcdef".toCharArray(), text = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            text[index * 2] = digits[(value[index] >>> 4) & 15];
            text[index * 2 + 1] = digits[value[index] & 15];
        }
        return new String(text);
    }

    private static byte[] unhex(String value, int bytes) {
        if (value.length() != bytes * 2) return null;
        byte[] decoded = new byte[bytes];
        for (int index = 0; index < bytes; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            decoded[index] = (byte) ((high << 4) | low);
        }
        return decoded;
    }
}
