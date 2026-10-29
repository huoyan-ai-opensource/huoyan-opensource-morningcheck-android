package cc.huoyan.opensource.morningcheck;

import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

/**
 * 业务列表测试直接使用 RecyclerView 的数据快照和挂载行，不依赖旧 ListView 的位置换算。
 */
final class RecyclerListTestHelper {
    private RecyclerListTestHelper() {
    }

    static int count(RecyclerView list) {
        return list.getAdapter() == null ? 0 : list.getAdapter().getItemCount();
    }

    static JSONObject item(RecyclerView list, int position) {
        return ((JsonRowsAdapter<?>) list.getAdapter()).item(position);
    }

    static int first(RecyclerView list) {
        return ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
    }

    static int last(RecyclerView list) {
        return ((LinearLayoutManager) list.getLayoutManager()).findLastVisibleItemPosition();
    }

    static void scroll(RecyclerView list, int position, int offset) {
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, offset);
    }

    static View row(RecyclerView list, int position) {
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        return holder == null ? null : holder.itemView;
    }

    static boolean click(RecyclerView list, int position) {
        View row = row(list, position);
        return row != null && row.performClick();
    }

    static long id(RecyclerView list, int position) {
        return item(list, position).optLong("id", position);
    }

    @SuppressWarnings("unchecked")
    static RecyclerView.ViewHolder bind(RecyclerView list, int position, RecyclerView.ViewHolder recycled) {
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter =
                (RecyclerView.Adapter<RecyclerView.ViewHolder>) list.getAdapter();
        RecyclerView.ViewHolder holder = recycled == null
                ? adapter.createViewHolder(list, adapter.getItemViewType(position)) : recycled;
        adapter.bindViewHolder(holder, position);
        return holder;
    }
}
