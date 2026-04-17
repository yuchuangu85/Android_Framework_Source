package android.net.http;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// This is an almost identical clone of org.chromium.net.impl.UrlResponseInfoImpl.HeaderBlockImpl
// with few minor changes to `mAllHeadersList`. This is needed because we need to maintain the
// request headers in the wrapper layer between HttpEngine and CronetEngine because the wrapper
// does  not have access to the backend's HeaderBlock. This results in the HeaderBlocks being
// duplicated, once inside HttpEngine and another time inside CronetEngine.
final class HeaderBlockImpl extends android.net.http.HeaderBlock {
    private final List<Map.Entry<String, String>> mAllHeadersList;
    private Map<String, List<String>> mAllHeadersMap;

    public HeaderBlockImpl(List<Map.Entry<String, String>> allHeaders) {
        this.mAllHeadersList = Collections.unmodifiableList(allHeaders);
    }

    @NonNull
    @Override
    public List<Map.Entry<String, String>> getAsList() {
        return mAllHeadersList;
    }

    @NonNull
    @Override
    public Map<String, List<String>> getAsMap() {
        if (mAllHeadersMap != null) {
            return mAllHeadersMap;
        }
        synchronized (mAllHeadersList) {
            // This is potentially racy...but races will only result in multiple creation
            // of the same map.
            Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String, String> entry : mAllHeadersList) {
                List<String> values = new ArrayList<>();
                if (map.containsKey(entry.getKey())) {
                    values.addAll(map.get(entry.getKey()));
                }
                values.add(entry.getValue());
                map.put(entry.getKey(), Collections.unmodifiableList(values));
            }
            mAllHeadersMap = Collections.unmodifiableMap(map);
        }
        return mAllHeadersMap;
    }
}
