package edu.lcl.rag.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标准代号的抽取与归一化。
 * <p>
 * 解决的问题：用户会写 {@code GB 1023}、{@code GB/T 1023}、{@code GB1023}、甚至只写 {@code 1023}，
 * 而库里的正文写作 {@code 应符合 GB/T 1023 规定}。双方归一化到同一个紧凑形式（{@code gb1023}），
 * 再用 {@code LIKE} 精确匹配，就能做到"单独查一个标准号也能命中它的内容"。
 * <p>
 * 正文侧使用同一条归一化规则（SQL 表达式，见 {@link #CONTENT_NORMALIZE_SQL}）。
 *
 * @author Codex
 */
@Component
public class StandardCodeExtractor {

    /**
     * 正文侧归一化表达式：小写、去空格、去 "/t"。
     * 与 {@link #normalize(String, String)} 保持一致，两边都变成 {@code gb1023} 这种形式。
     */
    public static final String CONTENT_NORMALIZE_SQL =
            "replace(replace(lower(content), ' ', ''), '/t', '')";

    /** 带前缀的标准代号：GB 1023 / GB/T 30600 / SL 482 / TD/T 1048 … */
    private static final Pattern CODE = Pattern.compile(
            "(?i)(GB|NY|SL|TD|DB|JGJ|CJJ|JTG|JTS|HJ|DL|NB|SN)\\s*/?\\s*T?\\s*(\\d{2,5}(?:\\.\\d+)*)");

    /** 裸数字兜底：用户只写 "1023" 时也认，但要求 4~5 位，避免把页码/面积当标准号 */
    private static final Pattern BARE_NUMBER = Pattern.compile("(?<![\\d.])(\\d{4,5})(?![\\d.])");

    /**
     * 从文本中抽取标准代号。
     *
     * @param text 用户问题或正文
     * @return 归一化后的代号（去重、保序），如 {@code [gb1023, gb/t30600]}
     */
    public List<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        Matcher matcher = CODE.matcher(text);
        while (matcher.find()) {
            codes.add(normalize(matcher.group(1), matcher.group(2)));
        }
        if (codes.isEmpty()) {
            Matcher bare = BARE_NUMBER.matcher(text);
            while (bare.find()) {
                codes.add(bare.group(1));
            }
        }
        return new ArrayList<>(codes);
    }

    /** 归一化：小写、去空格、去 /T —— {@code GB/T 30600} → {@code gb30600} */
    public String normalize(String prefix, String number) {
        return (prefix + number).toLowerCase().replace(" ", "").replace("/t", "");
    }

    /** 归一化任意写法（{@code GBT 1023} / {@code gb/t1023} / {@code GB 1023}） */
    public String normalize(String raw) {
        return raw == null ? "" : raw.toLowerCase().replace(" ", "").replace("/t", "");
    }

    /** LIKE 用的模式串：{@code %gb1023%} */
    public String likePattern(String normalizedCode) {
        return "%" + normalizedCode + "%";
    }
}
