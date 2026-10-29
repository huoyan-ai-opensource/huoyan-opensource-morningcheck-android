package cc.huoyan.opensource.morningcheck.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 主线程上的人脸操作代次。写操作完成后代次仍保留，防止之前发出的查询覆盖新登记。
 */
public final class FaceOperations {
    private final Set<Long> writing = new HashSet<>();
    private final Map<Long, Long> versions = new HashMap<>();

    public boolean isWriting(long personId) {
        return writing.contains(personId);
    }

    public long version(long personId) {
        return versions.getOrDefault(personId, 0L);
    }

    public boolean beginWrite(long personId) {
        if (!writing.add(personId)) return false;
        versions.put(personId, version(personId) + 1);
        return true;
    }

    public void finishWrite(long personId) {
        writing.remove(personId);
    }

    public boolean acceptsQuery(long personId, long version) {
        return !isWriting(personId) && version(personId) == version;
    }
}
