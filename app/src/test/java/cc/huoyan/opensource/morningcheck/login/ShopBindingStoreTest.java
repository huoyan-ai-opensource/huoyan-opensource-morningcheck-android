package cc.huoyan.opensource.morningcheck.login;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import cc.huoyan.morningcheck.sdk.client.SdkConfig;

import static org.junit.Assert.*;

public final class ShopBindingStoreTest {
    @Test public void commitsScopeAndConfigurationTogetherAndIncrementsGeneration() {
        PreferenceFixture preferences = new PreferenceFixture();
        ShopBindingStore store = new ShopBindingStore(preferences.preferences);
        assertEquals(0, store.read().length());
        SdkConfig config = SdkConfig.automatic("ws://127.0.0.1:18087/");
        assertTrue(store.save(new ShopLoginCoordinator.Binding("a", "s", " 0001 ", config,
                "云端账号", "云端门店", "S001")));
        assertEquals(1, preferences.commits);
        JSONObject first = store.read();
        assertEquals("a", first.optString("account_id"));
        assertEquals("s", first.optString("shop_id"));
        assertEquals(" 0001 ", first.optString("external_shop_id"));
        assertEquals(config.getWsEndpoint(), first.optString("endpoint"));
        assertEquals("AUTO", first.optString("channel"));
        assertEquals("云端门店", first.optString("shop_name"));
        assertEquals("S001", first.optString("shop_no"));
        assertEquals("云端账号", first.optString("account_name"));
        assertEquals(1, first.optLong("generation"));
        assertTrue(first.optLong("bound_at") > 0);
        assertEquals(1, preferences.values.size());
        assertTrue(preferences.values.containsKey(ShopBindingStore.SNAPSHOT_KEY));
        assertTrue(store.save(new ShopLoginCoordinator.Binding("b", "t", "002", config)));
        assertEquals(2, preferences.commits);
        assertEquals(2, store.read().optLong("generation"));
        assertEquals("s", first.optString("shop_id"));
        assertFalse(preferences.values.containsKey("cloud_token"));
    }

    @Test public void commitFailureIsReportedAndCannotFabricateSuccess() {
        PreferenceFixture preferences = new PreferenceFixture();
        preferences.failCommit = true;
        ShopBindingStore store = new ShopBindingStore(preferences.preferences);
        assertFalse(store.save(new ShopLoginCoordinator.Binding("a", "s", "001", SdkConfig.aidl())));
        assertEquals(1, preferences.commits);
        assertEquals(1, preferences.applies);
        assertEquals(0, store.read().length());
        assertTrue(preferences.values.isEmpty());
    }

    @Test public void failedCommitRestoresEntirePreviousSnapshotIncludingNameAndConnection() {
        PreferenceFixture preferences = new PreferenceFixture();
        ShopBindingStore store = new ShopBindingStore(preferences.preferences);
        assertTrue(store.save(new ShopLoginCoordinator.Binding("a", "s", "001", SdkConfig.aidl(),
                "旧账号", "旧门店", "OLD")));
        String oldSnapshot = (String) preferences.values.get(ShopBindingStore.SNAPSHOT_KEY);
        preferences.failCommit = true;
        assertFalse(store.save(new ShopLoginCoordinator.Binding("b", "t", "002",
                SdkConfig.webSocket("ws://192.168.1.2:18087/"), "新账号", "新门店", "NEW")));
        assertEquals(oldSnapshot, preferences.values.get(ShopBindingStore.SNAPSHOT_KEY));
        assertEquals("s", store.read().optString("shop_id"));
        assertEquals("旧门店", store.read().optString("shop_name"));
        assertEquals("AIDL", store.read().optString("channel"));
        assertEquals(1, store.read().optLong("generation"));
        assertEquals(2, preferences.commits);
        assertEquals(1, preferences.applies);
    }

    @Test public void throwingCommitAfterMemoryWriteRestoresPreviousSnapshot() {
        PreferenceFixture preferences = new PreferenceFixture();
        ShopBindingStore store = new ShopBindingStore(preferences.preferences);
        assertTrue(store.save(new ShopLoginCoordinator.Binding("a", "s", "001", SdkConfig.aidl())));
        String oldSnapshot = (String) preferences.values.get(ShopBindingStore.SNAPSHOT_KEY);
        preferences.throwOnCommit = true;
        assertFalse(store.save(new ShopLoginCoordinator.Binding("b", "t", "002", SdkConfig.aidl())));
        assertEquals(oldSnapshot, preferences.values.get(ShopBindingStore.SNAPSHOT_KEY));
        assertEquals(1, preferences.applies);
    }

    @Test public void legacyScopeRemainsAvailableWhenFirstSnapshotCommitFails() {
        PreferenceFixture preferences = new PreferenceFixture();
        preferences.values.put("account_id", "legacy-account");
        preferences.values.put("shop_id", "legacy-shop");
        preferences.values.put("generation", 7L);
        preferences.failCommit = true;
        ShopBindingStore store = new ShopBindingStore(preferences.preferences);
        assertFalse(store.save(new ShopLoginCoordinator.Binding("a", "s", "001", SdkConfig.aidl())));
        assertFalse(preferences.values.containsKey(ShopBindingStore.SNAPSHOT_KEY));
        assertEquals("legacy-shop", store.read().optString("shop_id"));
        assertEquals(7, store.read().optLong("generation"));
    }

    @Test public void corruptSnapshotDoesNotFallBackToStaleLegacyScope() {
        PreferenceFixture preferences = new PreferenceFixture();
        preferences.values.put("account_id", "legacy-account");
        preferences.values.put("shop_id", "legacy-shop");
        preferences.values.put(ShopBindingStore.SNAPSHOT_KEY, "{corrupt");
        assertEquals(0, new ShopBindingStore(preferences.preferences).read().length());
    }

    @Test public void partialPersistedScopeIsNotPresentedAsLoggedIn() {
        PreferenceFixture preferences = new PreferenceFixture();
        preferences.values.put("account_id", "a");
        ShopBindingStore store = new ShopBindingStore(preferences.preferences);
        assertEquals(0, store.read().length());
        preferences.values.put("shop_id", " ");
        assertEquals(0, store.read().length());
    }

    @Test public void addressChangePreservesScopeButRequiresNewConfirmedBinding() {
        PreferenceFixture preferences = new PreferenceFixture();
        ShopBindingStore store = new ShopBindingStore(preferences.preferences);
        SdkConfig config = SdkConfig.aidl();
        assertTrue(store.save(new ShopLoginCoordinator.Binding("a", "s", "001", config)));
        assertTrue(ShopBindingStore.matchesConnection(store.read(), config));
        assertTrue(store.markNeedsVerification());
        assertEquals("s", store.read().optString("shop_id"));
        assertFalse(ShopBindingStore.matchesConnection(store.read(), config));
        assertTrue(store.save(new ShopLoginCoordinator.Binding("a", "s2", "002", config)));
        assertTrue(ShopBindingStore.matchesConnection(store.read(), config));
        assertFalse(store.read().has("needs_verification"));
    }

    @Test public void failedVerificationMarkNeverRestoresOldScopeAsCurrent() {
        PreferenceFixture preferences = new PreferenceFixture();
        ShopBindingStore store = new ShopBindingStore(preferences.preferences);
        assertTrue(store.save(new ShopLoginCoordinator.Binding("a", "s", "001", SdkConfig.aidl())));
        preferences.failCommit = true;
        assertFalse(store.markNeedsVerification());
        assertEquals("s", store.read().optString("shop_id"));
        assertFalse(ShopBindingStore.matchesConnection(store.read(), SdkConfig.aidl()));
    }

    @Test public void asynchronousInvalidationTakesEffectImmediatelyAndSurvivesFailedRelogin() {
        PreferenceFixture preferences = new PreferenceFixture();
        ShopBindingStore store = new ShopBindingStore(preferences.preferences);
        assertTrue(store.save(new ShopLoginCoordinator.Binding("a", "s", "001", SdkConfig.aidl())));
        assertTrue(store.markNeedsVerificationAsync());
        assertEquals(1, preferences.commits);
        assertEquals(1, preferences.applies);
        assertFalse(ShopBindingStore.matchesConnection(store.read(), SdkConfig.aidl()));
        preferences.failCommit = true;
        assertFalse(store.save(new ShopLoginCoordinator.Binding("a", "new", "002", SdkConfig.aidl())));
        assertEquals("s", store.read().optString("shop_id"));
        assertFalse(ShopBindingStore.matchesConnection(store.read(), SdkConfig.aidl()));
    }

    /** 模拟一次 Editor 的暂存区，使测试能够观察提交边界及旧快照是否受到污染。 */
    private static final class PreferenceFixture {
        final Map<String, Object> values = new LinkedHashMap<>();
        int commits;
        int applies;
        boolean failCommit;
        boolean throwOnCommit;
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getString":
                        case "getLong": return values.containsKey(arguments[0]) ? values.get(arguments[0]) : arguments[1];
                        case "edit": return editor();
                        case "toString": return "测试 SDK 归属存储";
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == arguments[0];
                        default: throw new AssertionError("非预期的存储操作：" + method.getName());
                    }
                });

        private SharedPreferences.Editor editor() {
            Map<String, Object> pending = new LinkedHashMap<>();
            Set<String> removals = new HashSet<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "putString":
                            case "putLong": pending.put((String) arguments[0], arguments[1]); return proxy;
                            case "remove": removals.add((String) arguments[0]); return proxy;
                            case "commit":
                                commits++;
                                // Android 先提交内存，再等待磁盘结果；失败也不能撤销内存修改。
                                for (String key : removals) values.remove(key);
                                values.putAll(pending);
                                if (throwOnCommit) throw new IllegalStateException("磁盘提交异常");
                                return !failCommit;
                            case "apply":
                                applies++;
                                for (String key : removals) values.remove(key);
                                values.putAll(pending);
                                return null;
                            default: throw new AssertionError("非预期的提交操作：" + method.getName());
                        }
                    });
        }
    }
}
