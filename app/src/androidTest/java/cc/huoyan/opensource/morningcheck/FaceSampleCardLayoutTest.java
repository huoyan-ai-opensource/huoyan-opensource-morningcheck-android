package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** 绘制真实照片卡片与录入预览，检查照片比例、整卡圆角及预览铺满；不启动相机或访问业务数据。 */
@RunWith(AndroidJUnit4.class)
public final class FaceSampleCardLayoutTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test
    public void loadedPhotoCardsShowTheirCompletePhotoWithoutLetterboxingAndKeepCardCorners() {
        instrumentation.runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(instrumentation.getTargetContext(), R.style.AppTheme);
            View row = LayoutInflater.from(context).inflate(R.layout.item_face_sample, new FrameLayout(context), false);
            ImageView image = row.findViewById(R.id.face_sample_image);
            row.findViewById(R.id.face_sample_missing).setVisibility(View.GONE);
            ((TextView) row.findViewById(R.id.face_sample_uid)).setText("人脸1");
            int width = context.getResources().getDimensionPixelSize(R.dimen.dp_60) * 4;
            int[][] sizes = {{240, 60}, {120, 120}, {60, 120}};
            String[] labels = {"宽图", "正方形图", "竖图"};
            for (int index = 0; index < sizes.length; index++) {
                Bitmap source = Bitmap.createBitmap(sizes[index][0], sizes[index][1], Bitmap.Config.ARGB_8888);
                source.eraseColor(Color.GREEN);
                Canvas sourceCanvas = new Canvas(source);
                Paint bands = new Paint();
                bands.setColor(Color.RED);
                sourceCanvas.drawRect(0, 0, source.getWidth(), source.getHeight() / 4f, bands);
                bands.setColor(Color.BLUE);
                sourceCanvas.drawRect(0, source.getHeight() * 3 / 4f, source.getWidth(), source.getHeight(), bands);
                image.setMinimumHeight(0);
                image.setImageBitmap(source);
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
                Bitmap rendered = Bitmap.createBitmap(row.getWidth(), row.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    row.draw(new Canvas(rendered));
                    if (index == 0) exportIfRequested(context, rendered);
                    assertCardCorners(context, rendered, labels[index]);
                    assertCompletePhotoWithoutLetterboxing(rendered, image,
                            sizes[index][0], sizes[index][1], labels[index]);
                } finally {
                    image.setImageDrawable(null);
                    rendered.recycle();
                    source.recycle();
                }
            }
        });
    }

    @Test
    public void enrollmentPreviewFillsWideTallAndSquareContainersWithoutStretching() {
        instrumentation.runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(instrumentation.getTargetContext(), R.style.AppTheme);
            FaceEnrollmentPreviewLayout host = new FaceEnrollmentPreviewLayout(context, null);
            FrameLayout content = new FrameLayout(context);
            content.setBackgroundColor(Color.BLUE);
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(image, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            host.addView(content, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            Bitmap source = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888);
            source.eraseColor(Color.GREEN);
            image.setImageBitmap(source);
            host.setFrameSize(source.getWidth(), source.getHeight());
            int[][] sizes = {{600, 240}, {240, 600}, {360, 360}, {640, 480}};
            try {
                for (int[] size : sizes) {
                    host.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
                    host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());
                    Bitmap rendered = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                    try {
                        host.draw(new Canvas(rendered));
                        String label = "预览 " + size[0] + "×" + size[1];
                        int middleX = size[0] / 2, middleY = size[1] / 2;
                        for (int[] point : new int[][]{{middleX, 0}, {middleX, size[1] - 1},
                                {0, middleY}, {size[0] - 1, middleY}, {middleX, middleY}}) {
                            assertEquals(label + "四边与中心应为画面，不能露出容器背景", Color.GREEN,
                                    rendered.getPixel(point[0], point[1]));
                        }
                        for (int[] corner : new int[][]{{0, 0}, {size[0] - 1, 0},
                                {0, size[1] - 1}, {size[0] - 1, size[1] - 1}}) {
                            assertEquals(label + "放大后的画面仍须裁切出四角圆弧", 0,
                                    Color.alpha(rendered.getPixel(corner[0], corner[1])));
                        }
                        assertTrue(label + "内容应使用有效缩放", content.getScaleX() > 0);
                        assertEquals(label + "摄像头和检测框所在容器应统一等比缩放",
                                content.getScaleX(), content.getScaleY(), .0001f);
                    } finally {
                        rendered.recycle();
                    }
                }
            } finally {
                image.setImageDrawable(null);
                source.recycle();
            }
        });
    }

    private static void assertCardCorners(Context context, Bitmap rendered, String label) {
        int right = rendered.getWidth() - 1, bottom = rendered.getHeight() - 1;
        for (int[] corner : new int[][]{{0, 0}, {right, 0}, {0, bottom}, {right, bottom}}) {
            assertEquals(label + "整张卡片四角应透明，不能被深蓝子背景补成直角", 0,
                    Color.alpha(rendered.getPixel(corner[0], corner[1])));
        }
        assertEquals(label + "卡片顶部中间应保留内容", 255,
                Color.alpha(rendered.getPixel(rendered.getWidth() / 2, 0)));
        assertEquals(label + "卡片底部中间应保留背景", 255,
                Color.alpha(rendered.getPixel(rendered.getWidth() / 2, bottom)));
        int inset = context.getResources().getDimensionPixelSize(R.dimen.dp_4) * 2;
        assertEquals(label + "顶部左侧圆角之后应显示原图，不能留下深蓝边", Color.RED,
                rendered.getPixel(inset, 0));
        assertEquals(label + "顶部右侧圆角之后应显示原图，不能留下深蓝边", Color.RED,
                rendered.getPixel(right - inset, 0));
    }

    private static void assertCompletePhotoWithoutLetterboxing(Bitmap rendered, ImageView image,
                                                               int sourceWidth, int sourceHeight, String label) {
        assertEquals("照片仍须等比完整显示", ImageView.ScaleType.FIT_CENTER, image.getScaleType());
        double expectedHeight = (double) image.getWidth() * sourceHeight / sourceWidth;
        assertEquals(label + "图片区域高度应跟随原图比例", expectedHeight, image.getHeight(), 1d);
        int middleX = image.getLeft() + image.getWidth() / 2;
        int middleY = image.getTop() + image.getHeight() / 2;
        assertEquals(label + "原图顶部应完整显示且没有上黑边", Color.RED,
                rendered.getPixel(middleX, image.getTop()));
        assertEquals(label + "原图底部应完整显示且没有下黑边", Color.BLUE,
                rendered.getPixel(middleX, image.getBottom() - 1));
        assertEquals(label + "照片左下角应保留，不能对照片另加圆角", Color.BLUE,
                rendered.getPixel(image.getLeft(), image.getBottom() - 1));
        assertEquals(label + "照片右下角应保留，不能对照片另加圆角", Color.BLUE,
                rendered.getPixel(image.getRight() - 1, image.getBottom() - 1));
        assertEquals(label + "原图左侧应完整显示", Color.GREEN,
                rendered.getPixel(image.getLeft(), middleY));
        assertEquals(label + "原图右侧应完整显示", Color.GREEN,
                rendered.getPixel(image.getRight() - 1, middleY));
        assertEquals(label + "原图中心应保留", Color.GREEN, rendered.getPixel(middleX, middleY));
    }

    private static void exportIfRequested(Context context, Bitmap rendered) {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureSampleCard"))) return;
        File folder = context.getExternalCacheDir();
        assertNotNull("截图导出目录必须可用", folder);
        assertTrue("截图导出目录必须存在", folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(folder, "sample-card-loaded.png"))) {
            assertTrue("整卡截图应成功导出", rendered.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (IOException error) {
            throw new AssertionError("整卡截图导出失败", error);
        }
    }
}
