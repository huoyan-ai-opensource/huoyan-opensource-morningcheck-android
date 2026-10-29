package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.core.graphics.PathParser;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 引导框和检测框均使用 TextureView 的实际画面区域，前摄仅镜像显示坐标。
 */
public final class DetectionOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF contentBounds = new RectF();
    private final RectF measuredContent = new RectF();
    private final Matrix previewTransform = new Matrix();
    private final Path headOutline = PathParser.createPathFromPathData(
            "M50 3 C34 3 26 14 26 29 L26 38 C22 36 21 39 22 44 L24 51 "
                    + "C25 54 27 55 29 54 C31 65 40 74 47 76 C49 77 51 77 53 76 "
                    + "C60 74 69 65 71 54 C73 55 75 54 76 51 L78 44 "
                    + "C79 39 78 36 74 38 L74 29 C74 14 66 3 50 3 Z");
    private final Path shoulderOutline = PathParser.createPathFromPathData(
            "M38 71 L38 80 C38 84 30 87 21 90 C9 94 4 99 3 107 "
                    + "M62 71 L62 80 C62 84 70 87 79 90 C91 94 96 99 97 107");
    private final Path contourHighlights = PathParser.createPathFromPathData(
            "M26 29 C26 14 34 3 50 3 C66 3 74 14 74 29 "
                    + "M31 60 C35 68 42 74 47 76 C49 77 51 77 53 76 C58 74 65 68 69 60");
    private final Path alignmentArcs = PathParser.createPathFromPathData(
            "M18 29 C18 14 25 5 34 1 M66 1 C75 5 82 14 82 29 M14 43 L18 43 M82 43 L86 43");
    private final Path guideOutline = new Path();
    private final Matrix guideTransform = new Matrix();
    private ViewTreeObserver observedTree;
    private final ViewTreeObserver.OnPreDrawListener previewBoundsListener = () -> {
        // TextureView 更新变换不会改变布局，只在有效画面边界发生变化时重画覆盖层。
        if (updateContentBounds()) invalidate();
        return true;
    };
    private JSONArray boxes = new JSONArray();
    private int frameWidth = 640, frameHeight = 480;
    private boolean mirrored;
    private boolean multipleFaces;
    private boolean guideVisible = true;

    void setGuideVisible(boolean visible) {
        guideVisible = visible;
        invalidate();
    }

    public DetectionOverlay(Context context) {
        this(context, null);
    }

    public DetectionOverlay(Context context, AttributeSet attributes) {
        super(context, attributes);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        observedTree = getViewTreeObserver();
        observedTree.addOnPreDrawListener(previewBoundsListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (observedTree != null && observedTree.isAlive())
            observedTree.removeOnPreDrawListener(previewBoundsListener);
        observedTree = null;
        super.onDetachedFromWindow();
    }

    private boolean updateContentBounds() {
        measuredContent.setEmpty();
        TextureView preview = getParent() instanceof View
                ? ((View) getParent()).findViewById(R.id.camera_texture) : null;
        if (preview != null && preview.isAvailable() && preview.getWidth() > 0 && preview.getHeight() > 0) {
            preview.getTransform(previewTransform);
            measuredContent.set(0, 0, preview.getWidth(), preview.getHeight());
            previewTransform.mapRect(measuredContent);
            measuredContent.offset(preview.getLeft() - getLeft(), preview.getTop() - getTop());
            if (!measuredContent.intersect(0, 0, getWidth(), getHeight()))
                measuredContent.setEmpty();
        } else if (isInEditMode()) {
            float scale = Math.min((float) getWidth() / frameWidth, (float) getHeight() / frameHeight);
            float width = frameWidth * scale, height = frameHeight * scale;
            measuredContent.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                    (getWidth() + width) / 2, (getHeight() + height) / 2);
        }
        if (contentBounds.equals(measuredContent)) return false;
        contentBounds.set(measuredContent);
        return true;
    }

    void show(JSONArray value, int width, int height, boolean mirror) {
        show(value, width, height, mirror, false);
    }

    void show(JSONArray value, int width, int height, boolean mirror, boolean multiple) {
        boxes = value == null ? new JSONArray() : value;
        frameWidth = width;
        frameHeight = height;
        mirrored = mirror;
        multipleFaces = multiple;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        updateContentBounds();
        if (contentBounds.isEmpty()) return;
        paint.setStyle(Paint.Style.STROKE);
        float stroke = getResources().getDimension(R.dimen.dp_1);
        if (guideVisible) {
            float guideHeight = Math.min(contentBounds.height() * .80f, contentBounds.width() * .84f / .95f);
            float guideWidth = guideHeight * .95f;
            bounds.set(contentBounds.centerX() - guideWidth / 2, contentBounds.centerY() - guideHeight / 2,
                    contentBounds.centerX() + guideWidth / 2, contentBounds.centerY() + guideHeight / 2);
            guideTransform.setScale(guideWidth / 100, guideHeight / 110);
            guideTransform.postTranslate(bounds.left, bounds.top);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            drawGuidePath(c, headOutline, 0x245287C5, stroke * 7);
            drawGuidePath(c, headOutline, 0xFF57799D, stroke * 1.5f);
            drawGuidePath(c, shoulderOutline, 0xFF718BA8, stroke * 1.5f);
            drawGuidePath(c, contourHighlights, 0xFF3975B8, stroke * 2.5f);
            drawGuidePath(c, alignmentArcs, 0xFF8FA9C4, stroke);
        }
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
        float width = contentBounds.width(), height = contentBounds.height();
        for (int i = 0; i < boxes.length(); i++) {
            JSONObject b = boxes.optJSONObject(i);
            if (b == null) continue;
            float x = (float) b.optDouble("x"), y = (float) b.optDouble("y"), w = (float) b.optDouble("w"), h = (float) b.optDouble("h");
            if (mirrored) x = 1 - x - w;
            paint.setColor(multipleFaces ? Color.rgb(255, 171, 77) : Color.rgb(93, 225, 191));
            bounds.set(x * width + contentBounds.left, y * height + contentBounds.top,
                    (x + w) * width + contentBounds.left, (y + h) * height + contentBounds.top);
            float boxRadius = getResources().getDimension(R.dimen.dp_3);
            c.drawRoundRect(bounds, boxRadius, boxRadius, paint);
        }
    }

    private void drawGuidePath(Canvas canvas, Path path, int color, float strokeWidth) {
        path.transform(guideTransform, guideOutline);
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidth);
        canvas.drawPath(guideOutline, paint);
    }
}
