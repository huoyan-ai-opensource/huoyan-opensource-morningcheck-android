package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Layout;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.MockWebServer;

/** 本机隔离服务检查当前首页统计弹窗、记录导航与字号适配，不写入业务记录。 */
@RunWith(AndroidJUnit4.class)
public final class HomeTodayReportUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MorningApplication app;
    private SharedPreferences prefs;
    private Map<String,Object> original;
    private MockWebServer server;
    private ActivityScenario<HomeActivity> scenario;
    private static final int[] SHORTCUTS = {R.id.home_staff, R.id.home_records, R.id.home_connection,
            R.id.home_lighting, R.id.home_authorization, R.id.home_camera,
            R.id.home_items, R.id.home_temperature, R.id.home_system};

    @Before public void isolateServices() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        prefs = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        original = new HashMap<>(prefs.getAll());
        instrumentation.runOnMainSync(() -> { finishPages(); app.sdkSession().disconnect(); });
        app.io.submit(() -> {}).get(10, TimeUnit.SECONDS);
        server = new MockWebServer(); server.start();
        assertTrue(prefs.edit().putString(MorningSdkSettings.TRANSPORT_KEY,"ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY,server.url("/sdk").toString().replaceFirst("http","ws"))
                .putString("cloud_url",server.url("/cloud").toString()).putString("cloud_token", "")
                .putBoolean("voice", false).putInt(AppFontSettings.KEY,AppFontSettings.DEFAULT_INDEX).commit());
        scenario = ActivityScenario.launch(new Intent(app,HomeActivity.class));
        long deadline=SystemClock.elapsedRealtime()+10000;
        AtomicReference<Boolean> ready=new AtomicReference<>(false);
        do { scenario.onActivity(a -> ready.set(a.loaded)); if(ready.get())break; SystemClock.sleep(30); }
        while(SystemClock.elapsedRealtime()<deadline);
        assertTrue("统计必须实际读取完成",ready.get());
        scenario.onActivity(a -> assertHomePlace(a.findViewById(R.id.main_root), a.currentShopName()));
    }
    @After public void restoreServices() throws Exception {
        try {
            if(scenario!=null)scenario.close();
            instrumentation.runOnMainSync(() -> { finishPages(); app.sdkSession().disconnect(); });
            instrumentation.waitForIdleSync(); app.io.submit(() -> {}).get(10,TimeUnit.SECONDS);
        } finally {
            SharedPreferences.Editor e=prefs.edit().clear();
            for(Map.Entry<String,Object> item:original.entrySet()) {
                Object v=item.getValue();String k=item.getKey();
                if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);
                else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);
                else if(v instanceof Float)e.putFloat(k,(Float)v);
                else if(v instanceof java.util.Set)e.putStringSet(k,new java.util.HashSet<>((java.util.Set<String>)v));
            }
            assertTrue(e.commit()); if(server!=null)server.shutdown();
        }
    }
    @Test public void zeroPartialAndCompletedCountsAppearInCurrentSummaryDialog() {
        for (long[] counts : new long[][]{{0, 0, 0}, {1234, 900, 87}, {10, 10, 0}, {10, 0, 10}}) {
            scenario.onActivity(activity -> {
                setCounts(activity, counts[0], counts[1], counts[2]);
                assertTrue("主卡长按应打开实际统计窗口", activity.findViewById(R.id.home_start).performLongClick());
            });
            instrumentation.waitForIdleSync();
            String expected = "未检：" + Math.max(0, counts[0] - counts[1] - counts[2])
                    + "\n已检：" + (counts[1] + counts[2]) + "\n正常：" + counts[1] + "\n异常：" + counts[2];
            // 主线程空闲时无障碍窗口可能仍未更新，等待本次完整统计文本出现。
            long deadline = SystemClock.elapsedRealtime() + 10000;
            boolean windowFound = false, matched = false;
            do {
                android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root != null) {
                    windowFound = true;
                    for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(expected))
                        if (expected.equals(String.valueOf(node.getText()))) matched = true;
                }
                if (matched) break;
                SystemClock.sleep(30);
            } while (SystemClock.elapsedRealtime() < deadline);
            assertTrue("统计弹窗应成为当前窗口", windowFound);
            assertTrue("统计窗口必须展示本次四项人数：" + expected, matched);
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            instrumentation.waitForIdleSync();
        }
    }

    @Test public void recordsEntryReturnsWithoutRebuildingHomeOrLosingShortcutPosition() {
        AtomicReference<View> root = new AtomicReference<>();
        AtomicReference<Integer> offset = new AtomicReference<>();
        scenario.onActivity(activity -> {
            ScrollView scroll = activity.findViewById(R.id.home_shortcut_scroll);
            if (scroll != null) {
                scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                offset.set(scroll.getScrollY());
            }
            root.set(activity.content);
            activity.findViewById(R.id.home_records).performClick();
        });
        instrumentation.waitForIdleSync();
        AtomicReference<RecordsActivity> records = new AtomicReference<>();
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            instrumentation.runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (activity instanceof RecordsActivity) records.set((RecordsActivity) activity);
            });
            if (records.get() != null) break;
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertNotNull("必须通过当前可见入口进入记录页", records.get());
        instrumentation.runOnMainSync(() -> records.get().finish());
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            assertSame("返回只刷新统计，不能重建主页入口", root.get(), activity.content);
            if (offset.get() != null) assertEquals("返回保留入口位置", (int) offset.get(),
                    ((ScrollView) activity.findViewById(R.id.home_shortcut_scroll)).getScrollY());
        });
    }

    @Test public void midnightRefreshExistsOnlyWhileHomeIsVisible() {
        AtomicReference<Runnable> callback=new AtomicReference<>();
        AtomicReference<android.os.Handler> handler=new AtomicReference<>();
        scenario.onActivity(a -> {
            callback.set((Runnable)field(a,"nextDaySummary"));handler.set(a.main);
            assertTrue("可见首页预约本地换日刷新",a.main.hasCallbacks(callback.get()));
        });
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        instrumentation.runOnMainSync(() -> assertFalse("离页取消换日预约",handler.get().hasCallbacks(callback.get())));
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        scenario.onActivity(a -> assertTrue("回到首页重新预约换日",a.main.hasCallbacks(callback.get())));
    }
    @Test public void sixWindowsAndFourFontsKeepCurrentStartHeaderAndShortcutsInside() {
        scenario.onActivity(activity -> {
            for (int[] size : new int[][]{{360, 640}, {640, 360}, {800, 1280}, {1280, 800}, {360, 480}, {640, 320}})
                for (float font : new float[]{.85f, 1f, 1.15f, 1.3f}) {
                    Configuration configuration = new Configuration(activity.getResources().getConfiguration());
                    configuration.screenWidthDp = size[0];
                    configuration.screenHeightDp = size[1];
                    configuration.smallestScreenWidthDp = Math.min(size[0], size[1]);
                    configuration.orientation = size[0] > size[1] ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
                    configuration.fontScale = font;
                    Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme);
                    ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.activity_home_design, new FrameLayout(context), false);
                    prepare(root, context);
                    float density = context.getResources().getDisplayMetrics().density;
                    int width = Math.round(size[0] * density), height = Math.round(size[1] * density);
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    root.layout(0, 0, width, height);
                    String label = size[0] + "×" + size[1] + " font=" + font;
                    assertText(root, root.findViewById(R.id.home_start), label);
                    assertText(root, root.findViewById(R.id.home_settings), label);
                    ScrollView scroll = root.findViewById(R.id.home_shortcut_scroll);
                    for (int id : SHORTCUTS) {
                        View entry = root.findViewById(id);
                        if (scroll != null) {
                            Rect target = new Rect();
                            entry.getDrawingRect(target);
                            scroll.offsetDescendantRectToMyCoords(entry, target);
                            scroll.scrollTo(0, Math.max(0, target.top - scroll.getPaddingTop()));
                        }
                        assertTextTree(root, entry, label);
                        assertTrue(label + " 功能入口至少44dp", entry.getHeight() >= Math.round(44 * density));
                    }
                    if (scroll != null) scroll.scrollTo(0, 0);
                    if (font == 1f || font == 1.3f) save(root, "home-design-" + size[0] + "x" + size[1] + "-font-" + font);
                }
        });
    }

    private static void prepare(ViewGroup root, Context context) {
        android.widget.LinearLayout.LayoutParams leading = (android.widget.LinearLayout.LayoutParams)
                root.findViewById(R.id.header_leading).getLayoutParams();
        leading.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        leading.weight = 0;
        android.widget.LinearLayout.LayoutParams trailing = (android.widget.LinearLayout.LayoutParams)
                root.findViewById(R.id.header_trailing).getLayoutParams();
        trailing.width = 0;
        trailing.weight = 0;
        root.findViewById(R.id.header_home).setVisibility(View.GONE);
        root.findViewById(R.id.header_page_title).setVisibility(View.GONE);
        root.findViewById(R.id.header_brand).setVisibility(View.VISIBLE);
        TextView about = root.findViewById(R.id.home_settings);
        about.setVisibility(View.VISIBLE);
        about.setText("关于设备");
        TextView place = root.findViewById(R.id.header_shop);
        place.setVisibility(View.VISIBLE);
        place.setText("未设置场所");
        assertHomePlace(root, "");
        int iconDp = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && context.getResources().getConfiguration().screenWidthDp < 1100 ? 24 : 28;
        for (int id : SHORTCUTS) {
            View icon = ((ViewGroup) root.findViewById(id)).getChildAt(0);
            icon.getLayoutParams().width = Math.round(iconDp * context.getResources().getDisplayMetrics().density);
            icon.getLayoutParams().height = icon.getLayoutParams().width;
        }
    }

    private static void assertHomePlace(View root, String name) {
        TextView place = root.findViewById(R.id.header_shop);
        assertNotNull("首页应展示场所名称", place);
        assertEquals("首页场所名称应可见", View.VISIBLE, place.getVisibility());
        assertEquals("首页采用当前场所名称，未配置时显示占位", name.isEmpty() ? "未设置场所" : name,
                place.getText().toString());
        assertFalse("场所名称展示不应保留旧扫码导航监听", place.hasOnClickListeners());
        assertFalse("场所名称展示不能触发旧扫码导航", place.performClick());
    }

    private static void setCounts(HomeActivity a,long staff,long normal,long abnormal) {
        try {
            for(String name:new String[]{"staffCount","checkedTodayCount","normalTodayCount","abnormalTodayCount"}) {
                Field f=HomeActivity.class.getDeclaredField(name);f.setAccessible(true);
                f.setLong(a,name.equals("staffCount")?staff:name.equals("checkedTodayCount")?normal+abnormal:name.equals("normalTodayCount")?normal:abnormal);
            }
        }catch(Exception e){throw new AssertionError(e);}
    }
    private static Object field(Object target,String name) {
        try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static void assertTextTree(ViewGroup root,View view,String label) {
        if(view.getVisibility()!=View.VISIBLE)return;
        if(view instanceof TextView)assertText(root,(TextView)view,label);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)assertTextTree(root,((ViewGroup)view).getChildAt(i),label);
    }
    private static void assertText(ViewGroup root,TextView text,String label) {
        Rect b=new Rect();text.getDrawingRect(b);root.offsetDescendantRectToMyCoords(text,b);
        assertTrue(label+" 控件不可见 "+text.getText()+" "+b,b.width()>0&&b.height()>0);
        for(ViewParent p=text.getParent();p instanceof ViewGroup;p=p.getParent()) {
            ViewGroup ancestor=(ViewGroup)p;Rect local=new Rect();text.getDrawingRect(local);ancestor.offsetDescendantRectToMyCoords(text,local);
            Rect available=new Rect(ancestor.getScrollX()+ancestor.getPaddingLeft(),ancestor.getScrollY()+ancestor.getPaddingTop(),ancestor.getScrollX()+ancestor.getWidth()-ancestor.getPaddingRight(),ancestor.getScrollY()+ancestor.getHeight()-ancestor.getPaddingBottom());
            assertTrue(label+" 被祖先裁切 "+text.getText()+" "+local+" / "+available,available.contains(local));
            if(ancestor==root)break;
        }
        Layout l=text.getLayout();assertNotNull(l);
        assertEquals(label+" 文字不能丢失 "+text.getText(),text.length(),l.getLineEnd(l.getLineCount()-1));
        for(int line=0;line<l.getLineCount();line++)assertEquals(label+" 文字省略 "+text.getText(),0,l.getEllipsisCount(line));
        assertTrue(label+" 文字高度不足 "+text.getText()+" "+l.getHeight()+" / "+text.getHeight(),l.getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom());
    }
    private void save(View root,String name) {
        try {
            File dir=new File(app.getExternalFilesDir(null),"qa-dashboard");assertTrue(dir.isDirectory()||dir.mkdirs());
            Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(bitmap));
            try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        }catch(Exception e){throw new AssertionError(e);}
    }
    private static void finishPages() {
        HashSet<Activity> pages=new HashSet<>();
        for(Stage s:new Stage[]{Stage.RESUMED,Stage.STARTED,Stage.CREATED,Stage.PAUSED,Stage.STOPPED})pages.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(s));
        for(Activity a:pages)if(a instanceof BaseMorningActivity)a.finish();
    }
}
