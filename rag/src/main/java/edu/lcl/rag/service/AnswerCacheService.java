package edu.lcl.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.lcl.rag.dto.RagResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 仅首页热门问题的 Redis 答案缓存（默认 7 天），普通问题和带历史的回答不参与。
 *
 * <p>为什么需要：首页那几个预设热门问题（建设规划布局要做到什么程度 / 施工组织设计怎么做 /
 * 什么是田块整治工程 / 如何进行项目工程设计）每次点击都要走一遍
 * 向量检索 → 精排 → 大模型生成，既慢又费额度；相同资料范围下的独立提问可复用答案。
 *
 * <p>key 设计：
 * <pre>
 *   hbnt:answer:v2:hot:g{代}:{kbIds}:{docId|all}:{sha256(规范化问题)}
 * </pre>
 * <ul>
 *   <li>{@code g{代}}：全局缓存代。文档重新解析 / 删除资料时 {@link #invalidate()} 让代 +1，
 *       旧 key 自然失效（无需扫描删除），避免"资料换了、答案还是旧的"。</li>
 *   <li>{@code kbIds} 进 key：不同知识库分开存；当前只允许全库范围，指定文档不参与。</li>
 *   <li>问题取 sha256 前 32 位：key 长度可控，且不受问题里标点/空格的影响。</li>
 * </ul>
 *
 * <p>可靠性：Redis 不可用时全部退化为"不缓存"，问答主流程不受影响。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnswerCacheService {

    // 切换命名空间后，旧版普通问答缓存不再读取；原 key 按已有 TTL 自然过期。
    private static final String KEY_PREFIX = "hbnt:answer:v2:hot:";
    private static final String GENERATION_KEY = "hbnt:answer:gen";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final HotQuestionCatalog hotQuestions;

    /** 答案缓存有效期（默认 7 天） */
    @Value("${rag.cache.answer-ttl:7d}")
    private Duration answerTtl;

    /** 一条缓存答案（存 JSON；用普通 POJO 而不是 record，避开 Jackson 对 record 的反序列化差异） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CachedAnswer {
        /** 原始问题，便于排查命中情况 */
        private String question;
        /** 回答正文 */
        private String answer;
        /** 来源列表，回放时原样返回给前端 */
        private List<RagResponse.Source> sources;
        /** 写入时间 */
        private String savedAt;
    }

    /** 当前缓存代（Redis 异常时退回 0，不影响问答） */
    public long generation() {
        try {
            String value = redis.opsForValue().get(GENERATION_KEY);
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (Exception e) {
            log.debug("[AnswerCache] 读取缓存代失败，按 0 处理：{}", e.getMessage());
            return 0L;
        }
    }

    /** 生成缓存 key */
    public String buildKey(List<Long> kbIds, Long docId, String question) {
        if (!hotQuestions.contains(question) || docId != null || kbIds == null || kbIds.isEmpty()
                || kbIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("仅支持有效知识库范围内的首页热门问题答案缓存");
        }
        String scope = kbIds.stream().distinct().sorted().toList().toString();
        return KEY_PREFIX + "g" + generation() + ":" + scope + ":"
                + (docId == null ? "all" : docId) + ":" + sha256(normalize(question));
    }

    /** 读缓存；Redis 异常或内容损坏都当"未命中" */
    public Optional<CachedAnswer> get(String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            CachedAnswer cached = objectMapper.readValue(json, CachedAnswer.class);
            if (cached == null || !hotQuestions.contains(cached.getQuestion())
                    || cached.getAnswer() == null || cached.getAnswer().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(cached);
        } catch (Exception e) {
            log.warn("[AnswerCache] 读取失败，按未命中处理：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 写缓存（带 TTL）；失败只记日志 */
    public void put(String key, CachedAnswer value) {
        if (key == null || !key.startsWith(KEY_PREFIX) || value == null
                || !hotQuestions.contains(value.getQuestion())) {
            throw new IllegalArgumentException("禁止缓存非热门问题的最终答案");
        }
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), answerTtl);
            log.info("[AnswerCache] 已缓存答案：key={}，TTL={}，字数={}",
                    key, answerTtl, value.getAnswer() == null ? 0 : value.getAnswer().length());
        } catch (Exception e) {
            log.warn("[AnswerCache] 写入失败：{}", e.getMessage());
        }
    }

    /** 使全部答案缓存失效：文档重新解析、删除资料时调用（代 +1，旧 key 自然作废） */
    public long invalidate() {
        try {
            Long generation = redis.opsForValue().increment(GENERATION_KEY);
            log.info("[AnswerCache] 资料已变化，答案缓存代 +1 → {}", generation);
            return generation == null ? 0L : generation;
        } catch (Exception e) {
            log.warn("[AnswerCache] 失效失败：{}", e.getMessage());
            return -1L;
        }
    }

    /** 物理删除所有答案缓存 key（仅运维排查用；正常失效走代 +1） */
    public long clearAllKeys() {
        long removed = 0;
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build())) {
            while (cursor.hasNext()) {
                redis.delete(cursor.next());
                removed++;
            }
        } catch (Exception e) {
            log.warn("[AnswerCache] 清理失败：{}", e.getMessage());
        }
        return removed;
    }

    /**
     * 规范化问题：去掉所有空白与首尾标点。
     * 这样"…什么程度?"与"…什么程度？"、"GB 50265 是什么"与"GB50265是什么"都能命中同一条缓存。
     */
    static String normalize(String question) {
        if (question == null) {
            return "";
        }
        return question.trim()
                .replaceAll("\\s+", "")
                .replaceAll("^[?？。.！!、,，;；:：]+", "")
                .replaceAll("[?？。.！!、,，;；:：]+$", "");
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
            return hex.substring(0, 32);
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
