package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.domain.CaptureSession;
import cc.huoyan.opensource.morningcheck.domain.CheckPipeline;
import cc.huoyan.opensource.morningcheck.domain.CheckGuidance;
import cc.huoyan.opensource.morningcheck.domain.DetectionClasses;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;

/** 实际页面提示出口与 XML 排版验收；不启动 Activity、相机、串口、数据库或系统语音。 */
@RunWith(AndroidJUnit4.class)
public final class CheckGuidanceUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final List<CheckActivity> fixtures = new ArrayList<>();
    private final List<MorningVoice> voices = new ArrayList<>();
    private final String preferenceName = "check_guidance_test_" + SystemClock.elapsedRealtimeNanos();
    private int spoken;

    @After public void closeFixtures() {
        onMain(() -> {
            for (CheckActivity check : fixtures) {
                check.foreground = false;
                check.destroyed = true;
                check.overlay = null;
                check.main.removeCallbacks((Runnable) field(check, "facePresenceExpiry"));
                check.main.removeCallbacks((Runnable) field(check, "temperaturePresenceExpiry"));
                check.sdkSession.client().destroy();
            }
            for (MorningVoice voice : voices) voice.close();
            instrumentation.getTargetContext().getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
                    .edit().clear().apply();
        });
        instrumentation.waitForIdleSync();
        assertEquals("文字反馈不应调用系统语音", 0, spoken);
    }

    @Test public void faceAbsenceAndUnmatchedEmployeeImmediatelyChangeVisiblePromptWithVoiceDisabled() {
        onMain(() -> {
            CheckActivity check = fixture(CheckPipeline.Stage.IDENTITY);
            assertNormal(check);
            assertFalse(text(check).contains("未匹配"));
            CameraSource.Frame empty = frame();
            preview(check, empty, new JSONArray(), true);
            assertEquals("未检测到人脸\n请进入摄像头画面", text(check));
            assertWarning(check);
            CameraSource.Frame present = frame();
            preview(check, present, faceBox(), true);
            assertNormal(check);
            identity(check, present, true);
            assertEquals("未匹配员工\n请确认已录入人脸", text(check));
            assertWarning(check);
            preview(check, frame(), new JSONArray(), false);
            assertFalse("检测结果未知时不能残留未匹配提示", text(check).contains("未匹配"));
            assertNormal(check);
            preview(check, frame(), faceBox(), true);
            assertEquals("有效未匹配事实尚在，重新入镜后应恢复文字反馈", "未匹配员工\n请确认已录入人脸", text(check));
            assertWarning(check);
        });
    }

    @Test public void facePromptExpiresWhileLayoutRemainsVisible() {
        final CheckActivity[] check = new CheckActivity[1];
        onMain(() -> {
            check[0] = fixture(CheckPipeline.Stage.IDENTITY);
            CameraSource.Frame present = frame();
            preview(check[0], present, faceBox(), true);
            identity(check[0], present, true);
            assertWarning(check[0]);
        });
        SystemClock.sleep(1700);
        onMain(() -> {
            assertFalse("断流不能一直显示上一次未匹配员工", text(check[0]).contains("未匹配"));
            assertNormal(check[0]);
        });
    }

    @Test public void headPromptUsesFaceCountWithoutDemandingAnotherIdentityMatch() {
        onMain(() -> {
            CheckActivity check = fixture(CheckPipeline.Stage.HEAD);
            CameraSource.Frame present = frame();
            Object presence = field(check, "facePresence");
            invoke(presence, "observeFaces", new Class<?>[]{long.class, int.class, long.class},
                    present.receivedAtNanos, 1, System.nanoTime());
            invoke(presence, "match", new Class<?>[]{long.class, boolean.class, long.class},
                    present.receivedAtNanos, false, System.nanoTime());
            updateVoice(check);
            assertFalse("头检不能要求戴口罩的员工再次识别身份", text(check).contains("未匹配"));
            assertNormal(check);
            CameraSource.Frame multiple = frame();
            invoke(presence, "observeFaces", new Class<?>[]{long.class, int.class, long.class},
                    multiple.receivedAtNanos, 2, System.nanoTime());
            updateVoice(check);
            assertTrue("多人入镜应给出独立提示", text(check).contains("多人"));
            assertWarning(check);
            CameraSource.Frame absent = frame();
            invoke(presence, "observeFaces", new Class<?>[]{long.class, int.class, long.class},
                    absent.receivedAtNanos, 0, System.nanoTime());
            updateVoice(check);
            assertEquals("人已离开后应立即反馈未入镜", "未检测到人脸\n请进入摄像头画面", text(check));
        });
    }

    @Test public void headClothingAndHandFindingsAreVisibleWithoutChangingBusinessVerdict() {
        onMain(() -> {
            CheckActivity head = fixture(CheckPipeline.Stage.HEAD);
            CheckPipeline headPipeline = pipeline(head);
            headPipeline.onHeadDetections(Collections.singletonList(detection(DetectionClasses.HEAD_HAT)), true);
            updateCheck(head);
            assertTrue("缺口罩应在当前提示直接显示", text(head).contains("口罩"));
            assertWarning(head);
            assertTrue("实时提示不能提前写入最终结论", headPipeline.getFindings().isEmpty());

            CheckActivity hand = fixture(CheckPipeline.Stage.HAND_BACK);
            CheckPipeline handPipeline = pipeline(hand);
            handPipeline.onDetections(Collections.singletonList(detection(DetectionClasses.HAND_GLOVE)));
            updateCheck(hand);
            assertTrue("手套问题应可见，不要求点击状态弹窗", text(hand).contains("手套"));
            assertWarning(hand);
            assertEquals(0, handPipeline.getProgress());
            handPipeline.onDetections(Arrays.asList(detection(DetectionClasses.HAND_BACK),
                    detection(DetectionClasses.HAND_BACK), detection(DetectionClasses.HAND_RING)));
            updateCheck(hand);
            assertTrue("连续有效采样中发现戒指，应立即显示", text(hand).contains("戒指"));
            assertWarning(hand);
            assertTrue("待采样异常不能提前成为最终异常", handPipeline.getFindings().isEmpty());
            handPipeline.onDetections(Collections.emptyList());
            updateCheck(hand);
            assertFalse("无效姿态打断后不得残留上一组戒指提示", text(hand).contains("戒指"));
            handPipeline.onDetections(Arrays.asList(detection(DetectionClasses.HAND_BACK), detection(DetectionClasses.HAND_BACK)));
            updateCheck(hand);
            assertNormal(hand);
        });
    }

    @Test public void handGuidanceFollowsStageAndDisappearsWhenPausedOrCameraFails() {
        onMain(() -> {
            CheckActivity identity = fixture(CheckPipeline.Stage.IDENTITY);
            assertEquals("未出帧不能显示设备已就绪", "等待设备",
                    ((TextView) identity.content.findViewById(R.id.check_camera_status)).getText().toString());
            identity.latestFrame = frame();
            updateCheck(identity);
            assertEquals("设备已就绪", ((TextView) identity.content.findViewById(R.id.check_camera_status)).getText().toString());
            assertEquals("人脸识别", ((TextView) identity.content.findViewById(R.id.check_camera_stage)).getText().toString());
            assertNotNull("晨检动画常驻信息卡", identity.content.findViewById(R.id.check_face_guide));
            set(identity, "activeStaff", new JSONObject());
            updateCheck(identity);
            assertNotNull(identity.content.findViewById(R.id.check_face_guide));
            CheckActivity hand = fixture(CheckPipeline.Stage.HAND_BACK);
            View guide = hand.content.findViewById(R.id.check_hand_guide);
            assertEquals(View.VISIBLE, guide.getVisibility());
            assertEquals(hand.getString(R.string.design_hand_back_guide),
                    ((TextView) hand.content.findViewById(R.id.check_hand_guide_caption)).getText().toString());
            CheckPipeline pipeline = pipeline(hand);
            for (int i = 0; i < 3; i++) pipeline.onDetections(Arrays.asList(
                    detection(DetectionClasses.HAND_BACK), detection(DetectionClasses.HAND_BACK)));
            assertEquals(CheckPipeline.Stage.HAND_PALM, pipeline.getStage());
            updateCheck(hand);
            assertEquals(hand.getString(R.string.design_hand_palm_guide),
                    ((TextView) hand.content.findViewById(R.id.check_hand_guide_caption)).getText().toString());
            set(hand, "paused", true);
            updateCheck(hand);
            assertEquals("检查已暂停", ((TextView) hand.content.findViewById(R.id.check_camera_status)).getText().toString());
            assertEquals(View.GONE, guide.getVisibility());
            set(hand, "paused", false);
            hand.cameraError = "test camera disconnected";
            updateCheck(hand);
            assertEquals("相机错误后不能残留就绪状态", "等待设备",
                    ((TextView) hand.content.findViewById(R.id.check_camera_status)).getText().toString());
            assertEquals(View.GONE, guide.getVisibility());

            Context target = instrumentation.getTargetContext();
            Configuration device = target.getResources().getConfiguration();
            boolean portrait = "portrait".equals(InstrumentationRegistry.getArguments().getString("design_orientation"));
            int shortSide = Math.min(device.screenWidthDp, device.screenHeightDp);
            int longSide = Math.max(device.screenWidthDp, device.screenHeightDp);
            int width = portrait ? shortSide : longSide, height = portrait ? longSide : shortSide;
            CheckActivity preview = fixture(CheckPipeline.Stage.HAND_BACK, configuredContext(width, height, 1f));
            saveHandPreview(preview, width, height, "hand-back-fixture");
            for (int i = 0; i < 3; i++) pipeline(preview).onDetections(Arrays.asList(
                    detection(DetectionClasses.HAND_BACK), detection(DetectionClasses.HAND_BACK)));
            updateCheck(preview);
            saveHandPreview(preview, width, height, "hand-palm-fixture");
        });
    }

    private void saveHandPreview(CheckActivity check, int width, int height, String name) {
        ViewGroup root = (ViewGroup) check.content.getRootView();
        measure(root, width, height);
        assertContained(root, root.findViewById(R.id.check_hand_guide_image), name);
        assertFullText(root.findViewById(R.id.check_hand_guide_caption), name);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("design-ui");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            root.draw(new Canvas(bitmap));
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (Exception error) { throw new AssertionError(error); }
        finally { bitmap.recycle(); }
    }

    @Test public void hardwareStateIsNotRenderedAndOnlyActualTemperatureValueRemains() {
        onMain(() -> {
            CheckActivity check = fixture(CheckPipeline.Stage.IDENTITY);
            set(check, "sensorState", "测试串口设备状态不应显示");
            set(check, "lightState", "测试灯光状态不应显示");
            set(check, "liveTemperature", 36.65d);
            updateCheck(check);
            assertEquals("体温 · 36.65°C", ((TextView) check.content.findViewById(R.id.check_temperature)).getText().toString());
            String visible = collectText(check.content);
            assertFalse(visible.contains("测试串口"));
            assertFalse(visible.contains("测试灯光"));
            assertFalse("步骤名称也不能继续把测温混入身份标题", visible.contains("人脸识别与体温采集"));
            assertRemovedStatusWidgets(check.content);
            pipeline(check).skipTemperature();
            updateCheck(check);
            assertEquals("未启用测温时不显示设备提示代替读数", View.GONE,
                    check.content.findViewById(R.id.check_temperature).getVisibility());
        });
    }

    @Test public void portraitLandscapeAndFourFontsKeepPromptReadableAndActionsFixed() {
        onMain(() -> {
            List<String> prompts = new ArrayList<>(Arrays.asList("未检测到人脸\n请进入摄像头画面",
                    "未匹配员工\n请确认已录入人脸", "未匹配当前员工\n请本人继续检测",
                    "未佩戴工作帽、口罩\n请按要求佩戴后继续检测", "手背：戒指\n请伸出双手手背"));
            prompts.addAll(actualLongFindingPrompts());
            for (int[] size : new int[][]{{1280, 800}, {360, 640}, {640, 360}, {800, 1280}})
                for (int font = 0; font <= 3; font++) {
                    Context context = configuredContext(size[0], size[1], AppFontSettings.scaleFor(font));
                    for (String prompt : prompts) {
                        ViewGroup root = inflate(context);
                        prepareHeader(root);
                        TextView hint = root.findViewById(R.id.check_hint);
                        hint.setText(prompt);
                        hint.setBackgroundResource(R.drawable.mc_check_prompt_warning);
                        hint.setTextColor(ContextCompat.getColor(context, R.color.mc_check_prompt_warning_ink));
                        TextView promptLabel = root.findViewById(R.id.check_prompt_label);
                        promptLabel.setText("当前异常");
                        promptLabel.setTextColor(ContextCompat.getColor(context, R.color.mc_check_prompt_warning_ink));
                        ((TextView) root.findViewById(R.id.check_person)).setText("测试员工");
                        assertNotNull("信息卡包含完整晨检动画", root.findViewById(R.id.check_face_guide));
                        TextView detail = root.findViewById(R.id.check_person_detail);
                        detail.setVisibility(View.VISIBLE);
                        detail.setText("厨房部 · 工号 A001");
                        ((TextView) root.findViewById(R.id.check_temperature)).setText("体温 · 36.65°C");
                        ((TextView) root.findViewById(R.id.check_progress)).setText("人脸识别 · 步骤 1/5 · 连续采样 0/4");
                        measure(root, size[0], size[1]);
                        String label = Arrays.toString(size) + " 字号 " + font + " 提示 " + prompt;
                        assertContained(root, root.findViewById(R.id.check_header), label);
                        assertFullText(root.findViewById(R.id.header_shop), label);
                        assertFullText(root.findViewById(R.id.check_clock), label);
                        assertNoOverlap(root, root.findViewById(R.id.header_shop), root.findViewById(R.id.check_clock), label);
                        Rect title = bounds(root, root.findViewById(R.id.header_page_title));
                        assertEquals("现场晨检标题相对页面居中：" + label, root.getWidth() / 2f, title.exactCenterX(), 1f);
                        assertFullText(root.findViewById(R.id.header_page_title), label);
                        assertNoOverlap(root, root.findViewById(R.id.header_shop), root.findViewById(R.id.header_page_title), label);
                        assertEquals("场所和时间左对齐：" + label,
                                bounds(root, root.findViewById(R.id.header_shop)).left,
                                bounds(root, root.findViewById(R.id.check_clock)).left);
                        assertNull("晨检页不能保留返回首页入口", root.findViewById(R.id.header_home));
                        assertFullText(root.findViewById(R.id.check_camera_status), label);
                        assertFullText(root.findViewById(R.id.check_camera_stage), label);
                        assertNoOverlap(root, root.findViewById(R.id.check_camera_status), root.findViewById(R.id.check_camera_stage), label);
                        assertNull("晨检预览移除实时采集文案", root.findViewById(R.id.camera_preview_caption));
                        Rect animation = bounds(root, root.findViewById(R.id.check_face_guide));
                        Rect person = bounds(root, root.findViewById(R.id.check_person));
                        if (size[0] > size[1]) {
                            assertEquals("动画与员工信息左侧对齐：" + label, animation.left, person.left);
                            assertEquals("动画与员工信息右侧对齐：" + label, animation.right, person.right);
                            assertEquals("员工信息紧接动画：" + label,
                                    context.getResources().getDimensionPixelSize(R.dimen.dp_8),
                                    person.top - animation.bottom);
                        } else {
                            assertEquals("动画与员工信息顶部对齐：" + label, animation.top, person.top);
                        }
                        assertContained(root, hint, label);
                        assertFullText(hint, label);
                        assertCompactPrompt(hint, label);
                        assertContained(root, root.findViewById(R.id.check_prompt_label), label);
                        assertContained(root, root.findViewById(R.id.check_exit), label);
                        assertContained(root, root.findViewById(R.id.check_temperature), label);
                        assertOnlyInfoPanelScrolls(root);
                        assertPromptScrollEndReachable(root, hint, label);
                        assertRemovedStatusWidgets(root);
                        View camera = root.findViewById(R.id.check_camera_slot);
                        assertTrue("提示布局必须留下实际相机预览面积：" + label,
                                camera.getWidth() > 0 && camera.getHeight() > 0);
                        assertNoOverlap(root, hint, root.findViewById(R.id.check_prompt_label), label);
                        assertNoOverlap(root, hint, root.findViewById(R.id.check_temperature), label);
                        saveRequestedPreview(root, size, font, prompt);
                    }
                }
        });
    }

    private static List<String> actualLongFindingPrompts() {
        CheckPipeline.Rules rules = new CheckPipeline.Rules(1, 3, 3, true, true, true, 37.3);
        CheckPipeline pipeline = new CheckPipeline(rules);
        pipeline.onIdentity(1, true);
        pipeline.onTemperature(36.6);
        pipeline.onHeadDetections(Collections.emptyList(), true);
        String headPrompt = CheckGuidance.from(pipeline, rules).text;
        assertTrue("几何验收必须使用真实双项着装异常", headPrompt.contains("工作帽") && headPrompt.contains("口罩"));
        assertEquals("着装未达标应留在头检，不能为了生成手部提示带异常进入下一步",
                CheckPipeline.Stage.HEAD, pipeline.getStage());
        for (int i = 0; i < 3; i++) pipeline.onHeadDetections(Arrays.asList(
                detection(DetectionClasses.HEAD_HAT), detection(DetectionClasses.HEAD_MASK)), true);
        for (int i = 0; i < 3; i++) pipeline.onDetections(Arrays.asList(
                detection(DetectionClasses.HAND_BACK), detection(DetectionClasses.HAND_BACK),
                detection(DetectionClasses.HAND_NAIL), detection(DetectionClasses.HAND_PLASTER),
                detection(DetectionClasses.HAND_RING), detection(DetectionClasses.HAND_WOUND)));
        pipeline.onDetections(Arrays.asList(detection(DetectionClasses.HAND_PALM), detection(DetectionClasses.HAND_PALM),
                detection(DetectionClasses.HAND_NAIL), detection(DetectionClasses.HAND_PLASTER),
                detection(DetectionClasses.HAND_RING), detection(DetectionClasses.HAND_WOUND)));
        String palmPrompt = CheckGuidance.from(pipeline, rules).text;
        assertTrue("几何验收必须展示当前手心及全部八项手部摘要，已纠正着装不能残留",
                palmPrompt.contains("手心") && palmPrompt.contains("8项"));
        return Arrays.asList(headPrompt, palmPrompt);
    }

    private CheckActivity fixture(CheckPipeline.Stage stage) {
        return fixture(stage, configuredContext(1280, 800, 1f));
    }

    private CheckActivity fixture(CheckPipeline.Stage stage, Context context) {
        CheckActivity check = new CheckActivity();
        // 无 ActivityThread 宿主的夹具只注入资源 Context，不执行生命周期或硬件初始化。
        setDeclared(ContextWrapper.class, check, "mBase", context);
        // Activity.setTheme 会访问尚不存在的 Window；这里仅设置资源主题，不执行 Activity UI 初始化。
        setDeclared(ContextThemeWrapper.class, check, "mThemeResource", R.style.AppTheme);
        fixtures.add(check);
        check.foreground = true;
        check.prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
        check.sdkSession = new MorningSdkSession(context);
        check.sdkSession.onConnection(true, "本机提示界面夹具");
        try {
            check.sdkSession.onStatus(new JSONObject().put("sdk_state", "READY")
                    .put("certificate_valid", true).put("model_ready", true));
        } catch (Exception error) { throw new AssertionError(error); }
        check.sdk = check.sdkSession.client();
        CheckPipeline.Rules rules = new CheckPipeline.Rules(1, 3, 3, true, true, true, 37.3);
        CheckPipeline pipeline = new CheckPipeline(rules);
        if (stage != CheckPipeline.Stage.IDENTITY) {
            pipeline.onIdentity(1, true);
            pipeline.onTemperature(36.6);
        }
        if (stage == CheckPipeline.Stage.HAND_BACK)
            for (int i = 0; i < 3; i++) pipeline.onHeadDetections(Arrays.asList(
                    detection(DetectionClasses.HEAD_HAT), detection(DetectionClasses.HEAD_MASK)), true);
        set(check, "pipeline", pipeline);
        set(check, "checkRules", rules);
        set(check, "resumed", true);
        set(check, "frameRequest", 1);
        set(check, "facePreviewGeneration", 1);
        ((CaptureSession) field(check, "captureSession")).begin(check.sdkGeneration(), check.sdk.getStatus().optString("device_id"));
        ViewGroup root = inflate(context);
        prepareHeader(root);
        check.content = root.findViewById(R.id.page_content);
        for (String[] binding : new String[][]{{"checkHint", "check_hint"}, {"checkPromptLabel", "check_prompt_label"},
                {"checkPerson", "check_person"}, {"checkPersonDetail", "check_person_detail"},
                {"checkProgress", "check_progress"}, {"checkTemperature", "check_temperature"}, {"pauseButton", "check_pause"}}) {
            int id = context.getResources().getIdentifier(binding[1], "id", context.getPackageName());
            set(check, binding[0], root.findViewById(id));
        }
        MorningVoice voice = new MorningVoice(new Handler(Looper.getMainLooper()), () -> false, listener -> new MorningVoice.Speaker() {
            @Override public int setLanguage() { return android.speech.tts.TextToSpeech.LANG_AVAILABLE; }
            @Override public int speak(String text) { spoken++; return android.speech.tts.TextToSpeech.SUCCESS; }
            @Override public boolean isSpeaking() { return false; }
            @Override public void stop() { }
            @Override public void shutdown() { }
        });
        voices.add(voice);
        voice.resume();
        set(check, "speech", voice);
        updateCheck(check);
        return check;
    }

    private static CheckPipeline.Detection detection(int id) { return new CheckPipeline.Detection(id, .9f); }
    private static CameraSource.Frame frame() { return new CameraSource.Frame(new byte[12], 2, 2); }
    private static JSONArray faceBox() {
        try { return new JSONArray().put(new JSONObject().put("x", .2).put("y", .2).put("w", .4).put("h", .4)); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static CheckPipeline pipeline(CheckActivity check) { return (CheckPipeline) field(check, "pipeline"); }
    private static String text(CheckActivity check) { return ((TextView) field(check, "checkHint")).getText().toString(); }
    private static void preview(CheckActivity check, CameraSource.Frame frame, JSONArray boxes, boolean available) {
        invoke(check, "acceptFacePreview", new Class<?>[]{CameraSource.Frame.class, JSONArray.class, boolean.class,
                        int.class, CheckPipeline.Stage.class, int.class, boolean.class},
                frame, boxes, available, check.session, pipeline(check).getStage(), 1, false);
    }
    private static void identity(CheckActivity check, CameraSource.Frame frame, boolean available) {
        invoke(check, "acceptIdentity", new Class<?>[]{int.class, int.class, long.class, boolean.class, boolean.class,
                JSONObject.class, CameraSource.Frame.class}, 1, check.session, 0L, false, available, null, frame);
    }
    private static void updateVoice(CheckActivity check) { invoke(check, "updateVoice", new Class<?>[0]); }
    private static void updateCheck(CheckActivity check) { invoke(check, "updateCheck", new Class<?>[0]); }
    private static void assertWarning(CheckActivity check) { assertPromptStyle(check, true); }
    private static void assertNormal(CheckActivity check) { assertPromptStyle(check, false); }
    private static void assertPromptStyle(CheckActivity check, boolean warning) {
        TextView hint = (TextView) field(check, "checkHint");
        TextView label = (TextView) field(check, "checkPromptLabel");
        assertEquals(warning ? "当前异常" : "当前提示", label.getText().toString());
        int color = warning ? R.color.mc_check_prompt_warning_ink : R.color.mc_check_prompt_ink;
        assertEquals("文字颜色应随实时状态变化", ContextCompat.getColor(hint.getContext(), color), hint.getCurrentTextColor());
        Drawable expected = ContextCompat.getDrawable(hint.getContext(), warning ? R.drawable.mc_check_prompt_warning : R.drawable.mc_check_prompt_normal);
        assertEquals("提示背景应随实时状态变化", backgroundColor(expected), backgroundColor(hint.getBackground()));
    }
    private static int backgroundColor(Drawable drawable) {
        assertNotNull(drawable);
        Bitmap bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        Rect old = new Rect(drawable.getBounds());
        drawable.setBounds(0, 0, 40, 40);
        drawable.draw(new Canvas(bitmap));
        int color = bitmap.getPixel(20, 20);
        drawable.setBounds(old);
        bitmap.recycle();
        return color;
    }
    private Context configuredContext(int width, int height, float font) {
        Context app = instrumentation.getTargetContext();
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.screenWidthDp = width;
        configuration.screenHeightDp = height;
        configuration.smallestScreenWidthDp = Math.min(width, height);
        configuration.orientation = width > height ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        configuration.fontScale = font;
        return new ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.AppTheme);
    }
    private static ViewGroup inflate(Context context) {
        ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.activity_check_design_active, new FrameLayout(context), false);
        LayoutInflater.from(context).inflate(R.layout.view_check_camera_preview,
                root.findViewById(R.id.check_camera_slot), true);
        ((android.view.TextureView) root.findViewById(R.id.camera_texture)).setOpaque(false);
        ((TextView) root.findViewById(R.id.check_camera_stage)).setText("头部着装检查");
        return root;
    }
    private static void prepareHeader(ViewGroup root) {
        ((TextView) root.findViewById(R.id.header_shop)).setText("安和餐饮服务有限公司 · 第一食堂后厨");
        // 离屏布局不触发 TextClock 的附着刷新，使用完整日期时间验证换行与标题对齐。
        ((TextView) root.findViewById(R.id.check_clock)).setText("2026-10-21  10:45:32");
    }
    private static void measure(ViewGroup root, int widthDp, int heightDp) {
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
    private void saveRequestedPreview(ViewGroup root, int[] size, int font, String prompt) {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("guidance_screenshots")) || font != 3) return;
        Configuration device = instrumentation.getTargetContext().getResources().getConfiguration();
        boolean landscape = device.screenWidthDp > device.screenHeightDp;
        if (size[0] != (landscape ? 1280 : 360) || size[1] != (landscape ? 800 : 640)) return;
        String state;
        if ("未匹配员工\n请确认已录入人脸".equals(prompt)) state = "unmatched";
        else if ("手背：戒指\n请伸出双手手背".equals(prompt)) state = "ring";
        else return;
        FaceAlignmentGuideView guide = root.findViewById(R.id.check_face_guide);
        invoke(guide, "loadGuide", new Class<?>[0]);
        // 仅导出可控 XML 夹具，不截取真实相机、员工资料或其他应用画面。
        File directory = instrumentation.getTargetContext().getExternalFilesDir("check-guidance-qa");
        assertNotNull("仪器预览目录应可用", directory);
        assertTrue("仪器预览目录应可创建", directory.isDirectory() || directory.mkdirs());
        File output = new File(directory, "check-" + size[0] + "x" + size[1] + "-font130-" + state + ".png");
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(bitmap));
            try (FileOutputStream stream = new FileOutputStream(output)) {
                assertTrue("布局预览应完整导出", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
        } catch (Exception error) { throw new AssertionError(error); }
        finally { bitmap.recycle(); }
    }
    private static Rect bounds(ViewGroup root, View child) {
        Rect bounds = new Rect(); child.getDrawingRect(bounds);
        root.offsetDescendantRectToMyCoords(child, bounds); return bounds;
    }
    private static void assertContained(ViewGroup root, View child, String label) {
        assertNotNull(label, child); assertEquals(label, View.VISIBLE, child.getVisibility());
        Rect bounds = bounds(root, child);
        assertTrue("控件必须有面积：" + label, bounds.width() > 0 && bounds.height() > 0);
        assertTrue("控件必须完整位于页面：" + label + " " + bounds,
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.getWidth() && bounds.bottom <= root.getHeight());
    }
    private static void assertNoOverlap(ViewGroup root, View first, View second, String label) {
        assertFalse("当前提示不能盖住其他信息：" + label, Rect.intersects(bounds(root, first), bounds(root, second)));
    }
    private static void assertFullText(TextView text, String label) {
        Layout layout = text.getLayout(); assertNotNull(label, layout);
        String geometry = label + " 控件=" + text.getWidth() + "x" + text.getHeight()
                + " 字号px=" + text.getTextSize() + " 行数=" + layout.getLineCount() + "/" + text.getMaxLines()
                + " compoundPadding=" + text.getCompoundPaddingTop() + "," + text.getCompoundPaddingBottom()
                + " totalPadding=" + text.getTotalPaddingTop() + "," + text.getTotalPaddingBottom()
                + " layoutHeight=" + layout.getHeight()
                + " lastLineBottom=" + (layout.getLineCount() > 0 ? layout.getLineBottom(layout.getLineCount() - 1) : -1);
        assertTrue(geometry, layout.getLineCount() > 0);
        assertEquals("提示文字不能被最大行数截断：" + geometry, text.getText().length(), layout.getLineEnd(layout.getLineCount() - 1));
        assertTrue("提示文字不能纵向裁切：" + geometry,
                layout.getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        for (int i = 0; i < layout.getLineCount(); i++) {
            assertEquals("提示不能省略：" + geometry, 0, layout.getEllipsisCount(i));
            assertTrue("提示不能横向超出：" + geometry,
                    layout.getLineRight(i) <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
        }
    }
    private static void assertCompactPrompt(TextView text, String label) {
        Layout layout = text.getLayout();
        assertNotNull(label, layout);
        // 只为文字、最少行数和内边距保留高度；中心对齐产生的空白不计入所需高度。
        int neededLinesHeight = Math.max(layout.getHeight(), text.getLineHeight() * Math.max(0, text.getMinLines()));
        int neededHeight = neededLinesHeight + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom();
        int roundingAllowance = Math.max(2, Math.round(2 * text.getResources().getDisplayMetrics().density));
        assertTrue("提示框应随文字保持紧凑，不能填满右侧剩余区域：" + label
                        + " actualHeight=" + text.getHeight() + " neededHeight=" + neededHeight
                        + " lineHeight=" + text.getLineHeight() + " lineCount=" + layout.getLineCount()
                        + " minLines=" + text.getMinLines() + " roundingAllowance=" + roundingAllowance,
                text.getHeight() <= neededHeight + roundingAllowance);
    }
    private static void assertPromptScrollEndReachable(ViewGroup root, TextView hint, String label) {
        ScrollView scroll = (ScrollView) hint.getParent().getParent();
        Rect exitBefore = bounds(root, root.findViewById(R.id.check_exit));
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        Rect viewport = bounds(root, scroll);
        Rect prompt = bounds(root, hint);
        assertTrue("滚动到末尾必须可读提示最后一行：" + label,
                prompt.bottom <= viewport.bottom && prompt.bottom > viewport.top);
        assertEquals("卡片内部滚动不移动退出按钮", exitBefore, bounds(root, root.findViewById(R.id.check_exit)));
    }

    private static void assertOnlyInfoPanelScrolls(View view) {
        if (view instanceof ScrollView) {
            assertNotNull("滚动区域只能是晨检信息区", view.findViewById(R.id.check_hint));
            assertNull("相机预览必须固定显示", view.findViewById(R.id.check_camera_slot));
            assertNull("退出操作必须固定显示", view.findViewById(R.id.check_exit));
            return;
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertOnlyInfoPanelScrolls(((ViewGroup) view).getChildAt(i));
    }
    private static void assertRemovedStatusWidgets(ViewGroup root) {
        Context context = root.getContext();
        for (String name : new String[]{"check_device", "check_status_details"}) {
            int id = context.getResources().getIdentifier(name, "id", context.getPackageName());
            if (id != 0) assertNull("设备文字和查看状态入口应已移除：" + name, root.findViewById(id));
        }
    }
    private static String collectText(View view) {
        StringBuilder text = new StringBuilder();
        if (view.getVisibility() != View.VISIBLE) return "";
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) text.append(collectText(((ViewGroup) view).getChildAt(i)));
        return text.toString();
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void set(Object target, String name, Object value) { setDeclared(target.getClass(), target, name, value); }
    private static void setDeclared(Class<?> type, Object target, String name, Object value) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void onMain(Runnable action) { instrumentation.runOnMainSync(action); }
}
