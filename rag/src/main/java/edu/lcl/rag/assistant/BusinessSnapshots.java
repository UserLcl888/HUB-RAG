package edu.lcl.rag.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前会话 JSONB 中的查询快照，不使用 Redis。读取不延长快照寿命；
 * 请求条件精确相同、未过期且未要求刷新时才复用，不把候选当作完整结果。
 */
@Component
public class BusinessSnapshots {
    public static final String KEY = "querySnapshots";
    private final ObjectMapper mapper;
    private final long ttlSeconds;

    public BusinessSnapshots(ObjectMapper mapper,
                             @Value("${business.conversation.snapshot-ttl-seconds:300}") long ttlSeconds) {
        this.mapper = mapper;
        if (ttlSeconds < 1) throw new IllegalArgumentException("会话快照有效期必须为正数");
        this.ttlSeconds = ttlSeconds;
    }

    public <T> T find(Map<String, Object> context, String key, Object request, Class<T> type, boolean refresh) {
        Entry entry = entry(context, key, refresh);
        if (entry == null || !mapper.valueToTree(request).equals(mapper.valueToTree(entry.request()))) return null;
        return convert(entry.result(), type);
    }

    public <T> T latest(Map<String, Object> context, String key, Class<T> type, boolean refresh) {
        Entry entry = entry(context, key, refresh);
        return entry == null ? null : convert(entry.result(), type);
    }

    public void remember(Map<String, Object> context, String key, Object request, Object result) {
        Map<String, Object> entries = entries(context);
        entries.put(key, new Entry(request, result, Instant.now().toString()));
        context.put(KEY, entries);
    }

    private Entry entry(Map<String, Object> context, String key, boolean refresh) {
        if (refresh) return null;
        Entry entry = convert(entries(context).get(key), Entry.class);
        if (entry == null) return null;
        try {
            long age = Duration.between(Instant.parse(entry.fetchedAt()), Instant.now()).getSeconds();
            return age >= 0 && age < ttlSeconds ? entry : null;
        } catch (RuntimeException incompatible) {
            return null;
        }
    }

    private Map<String, Object> entries(Map<String, Object> context) {
        Object stored = context.get(KEY);
        Map<String, Object> result = new LinkedHashMap<>();
        if (stored instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(key.toString(), value));
        return result;
    }

    private <T> T convert(Object value, Class<T> type) {
        if (value == null) return null;
        try {
            return mapper.convertValue(value, type);
        } catch (IllegalArgumentException incompatible) {
            return null;
        }
    }

    public record Entry(Object request, Object result, String fetchedAt) {}
}
