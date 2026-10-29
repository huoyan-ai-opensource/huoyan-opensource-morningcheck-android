package cc.huoyan.opensource.morningcheck;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 业务表格沿用原行布局和分隔线，数据变化时不播放条目动画。
 */
final class BusinessListUi {
    private BusinessListUi() {
    }

    static void bind(RecyclerView list) {
        list.setLayoutManager(new LinearLayoutManager(list.getContext()));
        list.setItemAnimator(null);
        if (list.getItemDecorationCount() > 0) return;
        int thickness = list.getResources().getDimensionPixelSize(R.dimen.dp_0_5);
        Paint paint = new Paint();
        paint.setColor(Color.rgb(231, 235, 241));
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                outRect.bottom = thickness;
            }

            @Override
            public void onDraw(Canvas canvas, RecyclerView parent, RecyclerView.State state) {
                for (int index = 0; index < parent.getChildCount(); index++) {
                    View child = parent.getChildAt(index);
                    float top = child.getBottom() + child.getTranslationY();
                    canvas.drawRect(parent.getPaddingLeft(), top,
                            parent.getWidth() - parent.getPaddingRight(), top + thickness, paint);
                }
            }
        });
    }
}
