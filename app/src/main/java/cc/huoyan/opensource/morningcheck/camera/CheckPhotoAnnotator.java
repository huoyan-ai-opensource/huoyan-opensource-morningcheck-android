package cc.huoyan.opensource.morningcheck.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 在原 RGB 位图上写入证据标注，JPEG 保存后详情和上传均能看到同样的内容。
 */
public final class CheckPhotoAnnotator {
    private static final int ALERT_RED = Color.rgb(216, 45, 49);
    private static final int TEXT_BACKGROUND = Color.argb(210, 17, 29, 45);

    private CheckPhotoAnnotator() {
    }

    /**
     * 调用者持有位图并在后台绘制、保存，方法不创建或回收位图。
     */
    public static void annotateHand(Bitmap bitmap, List<HandEvidenceCapture.Abnormality> abnormalities) {
        if (abnormalities == null || abnormalities.isEmpty()) return;
        Canvas canvas = canvas(bitmap);
        float edge = Math.min(bitmap.getWidth(), bitmap.getHeight());
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(ALERT_RED);
        stroke.setStrokeWidth(Math.max(1, edge / 160f));
        Paint text = textPaint(edge / 26f);
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(ALERT_RED);
        float inset = stroke.getStrokeWidth() / 2;
        for (HandEvidenceCapture.Abnormality abnormality : abnormalities) {
            RectF box = new RectF(abnormality.x * bitmap.getWidth(), abnormality.y * bitmap.getHeight(),
                    (abnormality.x + abnormality.w) * bitmap.getWidth(),
                    (abnormality.y + abnormality.h) * bitmap.getHeight());
            // 裁剪发生在同一原图坐标系；边缘框向内缩半笔宽，避免线条被 JPEG 裁掉。
            box.left = Math.max(inset, box.left);
            box.top = Math.max(inset, box.top);
            box.right = Math.min(bitmap.getWidth() - inset, box.right);
            box.bottom = Math.min(bitmap.getHeight() - inset, box.bottom);
            if (box.isEmpty()) continue;
            canvas.drawRect(box, stroke);
            drawLabel(canvas, abnormality.label, box.left, box.top, text, background,
                    bitmap.getWidth(), bitmap.getHeight(), false);
        }
    }

    public static void annotateFace(Bitmap bitmap, long captureTimeMillis, double temperature) {
        Canvas canvas = canvas(bitmap);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(captureTimeMillis));
        String bodyTemperature = Double.isFinite(temperature)
                ? String.format(Locale.CHINA, "体温 %.1f°C", temperature) : "体温 未测";
        float edge = Math.min(bitmap.getWidth(), bitmap.getHeight());
        float margin = Math.max(1, edge / 70f);
        Paint text = textPaint(edge / 27f);
        float padding = Math.max(1, text.getTextSize() * .28f);
        // 小图缩字而不截断关键数字，左右标注各自保留完整文字及间隔。
        float combined = text.measureText(time) + text.measureText(bodyTemperature) + padding * 4;
        if (combined > bitmap.getWidth() - margin * 3)
            text.setTextSize(text.getTextSize() * Math.max(.05f,
                    (bitmap.getWidth() - margin * 3) / combined));
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(TEXT_BACKGROUND);
        drawLabel(canvas, time, margin, margin, text, background,
                bitmap.getWidth(), bitmap.getHeight(), true);
        padding = Math.max(1, text.getTextSize() * .28f);
        float rightWidth = text.measureText(bodyTemperature) + padding * 2;
        drawLabel(canvas, bodyTemperature, bitmap.getWidth() - margin - rightWidth, margin,
                text, background, bitmap.getWidth(), bitmap.getHeight(), true);
    }

    private static Canvas canvas(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || !bitmap.isMutable())
            throw new IllegalArgumentException("证据标注需要有效的可写位图");
        return new Canvas(bitmap);
    }

    private static Paint textPaint(float size) {
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(Math.max(1, size));
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return text;
    }

    private static void drawLabel(Canvas canvas, String label, float x, float y, Paint text,
                                  Paint background, int width, int height, boolean anchoredTop) {
        float savedTextSize = text.getTextSize();
        float padding = Math.max(1, savedTextSize * .28f);
        float labelWidth = text.measureText(label) + padding * 2;
        if (labelWidth > width) {
            text.setTextSize(savedTextSize * Math.max(.05f, (width - 2f) / labelWidth));
            padding = Math.max(.25f, text.getTextSize() * .28f);
            labelWidth = Math.min(width, text.measureText(label) + padding * 2);
        }
        Paint.FontMetrics metrics = text.getFontMetrics();
        float labelHeight = Math.min(height, metrics.descent - metrics.ascent + padding * 2);
        float left = Math.max(0, Math.min(x, width - labelWidth));
        float top = anchoredTop ? y : (y >= labelHeight ? y - labelHeight : y);
        top = Math.max(0, Math.min(top, height - labelHeight));
        canvas.drawRect(left, top, left + labelWidth, top + labelHeight, background);
        canvas.drawText(label, left + padding, top + padding - metrics.ascent, text);
        text.setTextSize(savedTextSize);
    }
}
