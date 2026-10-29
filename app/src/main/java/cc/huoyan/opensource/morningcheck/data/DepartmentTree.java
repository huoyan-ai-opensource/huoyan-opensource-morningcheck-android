package cc.huoyan.opensource.morningcheck.data;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 部门数量有限，树关系在内存中计算；员工和记录仍交给 SQLite 按条件分页。
 */
final class DepartmentTree {
    private final Map<Long, JSONObject> nodes = new LinkedHashMap<>();
    private final Map<Long, List<Long>> children = new HashMap<>();
    private final Map<Long, String> paths = new HashMap<>();

    DepartmentTree(List<JSONObject> values) {
        for (JSONObject value : values) {
            long id = value.optLong("id");
            nodes.put(id, value);
            children.computeIfAbsent(value.optLong("parent_id"), ignored -> new ArrayList<>()).add(id);
        }
    }

    boolean contains(long id) {
        return nodes.containsKey(id);
    }

    String path(long id) {
        if (id == 0) return "";
        String cached = paths.get(id);
        if (cached != null) return cached;
        ArrayDeque<JSONObject> chain = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        long current = id;
        while (current != 0 && !paths.containsKey(current)) {
            if (!seen.add(current))
                throw new IllegalStateException("部门层级存在循环，请联系管理员");
            JSONObject value = nodes.get(current);
            if (value == null) throw new IllegalStateException("上级部门不存在，请重新选择部门");
            chain.push(value);
            current = value.optLong("parent_id");
        }
        String prefix = current == 0 ? "" : paths.get(current);
        while (!chain.isEmpty()) {
            JSONObject value = chain.pop();
            prefix = prefix.isEmpty() ? value.optString("name") : prefix + " / " + value.optString("name");
            paths.put(value.optLong("id"), prefix);
        }
        return paths.get(id);
    }

    Set<Long> subtree(long id) {
        Set<Long> result = new HashSet<>();
        if (!nodes.containsKey(id)) return result;
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(id);
        while (!queue.isEmpty()) {
            long current = queue.remove();
            if (!result.add(current)) continue;
            List<Long> descendants = children.get(current);
            if (descendants != null) queue.addAll(descendants);
        }
        return result;
    }

    /**
     * 自由文本仅在名称或完整路径唯一时归属，不把同名子部门任意绑定到某一个节点。
     */
    long resolve(String text) {
        Set<Long> matches = matches(text);
        return matches.size() == 1 ? matches.iterator().next() : 0;
    }

    boolean hasMatch(String text) {
        return !matches(text).isEmpty();
    }

    private Set<Long> matches(String text) {
        Set<Long> matches = new HashSet<>();
        if (text == null || text.isEmpty()) return matches;
        for (JSONObject value : nodes.values()) {
            long id = value.optLong("id");
            if (text.equals(value.optString("name")) || text.equals(path(id))) matches.add(id);
        }
        return matches;
    }

    List<JSONObject> options(long excludedSubtreeId) {
        Set<Long> excluded = subtree(excludedSubtreeId);
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject value : nodes.values()) {
            long id = value.optLong("id");
            if (excluded.contains(id)) continue;
            JSONObject option = Jsons.copy(value);
            Jsons.put(option, "path", path(id));
            result.add(option);
        }
        result.sort(Comparator.comparing(value -> value.optString("path")));
        return result;
    }
}
