package cc.huoyan.opensource.morningcheck;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务行按主键做差异更新；内容保留独立快照，不能让后续 JSON 修改污染旧列表。
 */
abstract class JsonRowsAdapter<H extends RecyclerView.ViewHolder> extends ListAdapter<JsonRowsAdapter.Row, H> {
    private final String idKey;

    JsonRowsAdapter(String idKey) {
        super(new DiffUtil.ItemCallback<Row>() {
            @Override
            public boolean areItemsTheSame(@NonNull Row before, @NonNull Row after) {
                return before.key.equals(after.key);
            }

            @Override
            public boolean areContentsTheSame(@NonNull Row before, @NonNull Row after) {
                return before.content.equals(after.content);
            }
        });
        this.idKey = idKey;
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    final JSONObject item(int position) {
        return getItem(position).value;
    }

    final void submitRows(List<JSONObject> values, Runnable committed) {
        Map<String, Row> previous = new HashMap<>();
        for (Row row : getCurrentList()) previous.put(row.key, row);
        Map<String, Integer> positions = new HashMap<>();
        List<Row> next = new ArrayList<>(values.size());
        for (JSONObject value : values) {
            String key = value.optString(idKey);
            String content = value.toString();
            Row row = previous.get(key);
            if (row == null || !content.equals(row.content)) row = new Row(key, content);
            // 新增记录可能改变 OFFSET 边界，按主键去重，避免同一条数据出现两次。
            Integer existing = positions.put(key, next.size());
            if (existing == null) next.add(row);
            else {
                positions.put(key, existing);
                next.set(existing, row);
            }
        }
        submitList(next, committed);
    }

    static final class Row {
        final String key, content;
        final JSONObject value;

        Row(String key, String content) {
            this.key = key;
            this.content = content;
            try {
                value = new JSONObject(content);
            } catch (JSONException invalid) {
                throw new IllegalArgumentException("列表数据无法生成快照", invalid);
            }
        }
    }
}
