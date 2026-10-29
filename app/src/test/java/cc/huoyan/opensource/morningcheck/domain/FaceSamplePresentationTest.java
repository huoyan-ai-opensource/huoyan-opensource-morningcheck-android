package cc.huoyan.opensource.morningcheck.domain;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public final class FaceSamplePresentationTest {
    @Test public void sparseUidsUseListOrderAndRenumberAfterDelete() {
        JSONArray values = new JSONArray().put(5).put(90).put(300);
        assertEquals(1, FaceSamplePresentation.ordinal(values, 5));
        assertEquals(2, FaceSamplePresentation.ordinal(values, 90));
        assertEquals(0, FaceSamplePresentation.ordinal(values, 8));
        assertEquals(1, FaceSamplePresentation.ordinal(new JSONArray().put(90).put(300), 90));
    }
    @Test public void photosJoinByUidAndMissingPhotosDoNotRemoveSamples() throws Exception {
        JSONArray uids = new JSONArray().put(5).put(90);
        JSONArray samples = new JSONArray().put(new JSONObject().put("uid", 90).put("image_url", "http://localhost/photo90"))
                .put(new JSONObject().put("uid", 300).put("image_url", "http://localhost/unrelated"));
        Map<Long, String> urls = FaceSamplePresentation.urls(uids, samples);
        assertEquals(2, urls.size());
        assertEquals("", urls.get(5L));
        assertEquals("http://localhost/photo90", urls.get(90L));
        assertFalse(urls.containsKey(300L));
        assertEquals(2, FaceSamplePresentation.urls(uids, null).size());
    }

    @Test public void legacyPhotosNeverAssociateRoundedFloatingUidWithAnotherSample() throws Exception {
        long actual = 9007199254740992L;
        JSONArray samples = new JSONArray().put(new JSONObject()
                .put("uid", 9007199254740993d).put("image_url", "http://localhost/wrong.jpg"));
        Map<Long, String> urls = FaceSamplePresentation.urls(new JSONArray().put(actual), samples);
        assertEquals("失精度 UID 不能把另一张图关联到已核对样本", "", urls.get(actual));
    }

    @Test public void legacyPhotosKeepExactLongAndDoNotCoerceStringOrFractionalUid() throws Exception {
        long large = 2097500510197125121L;
        JSONArray samples = new JSONArray().put(new JSONObject().put("uid", large)
                .put("image_url", "http://localhost/large.jpg"))
                .put(new JSONObject().put("uid", "5").put("image_url", "http://localhost/string.jpg"))
                .put(new JSONObject().put("uid", 90.5).put("image_url", "http://localhost/fraction.jpg"));
        Map<Long, String> urls = FaceSamplePresentation.urls(new JSONArray().put(large).put(5).put(90), samples);
        assertEquals("http://localhost/large.jpg", urls.get(large));
        assertEquals("", urls.get(5L));
        assertEquals("", urls.get(90L));
    }
}
