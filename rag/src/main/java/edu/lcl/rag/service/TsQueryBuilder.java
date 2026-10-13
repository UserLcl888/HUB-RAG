package edu.lcl.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 将用户查询转换为 PostgreSQL tsquery（配合 {@code content_tsv_bigram} 列使用）。
 * <p>
 * 为什么自己切 2-gram：PostgreSQL 的 {@code simple} 配置不切中文，整串汉字会变成一个词元，
 * 于是「泵站设计应符合什么标准」这种问题永远 0 命中（实测中文查询只有子串匹配的 0%~30%）。
 * 索引侧由 {@code hbnt_tsv()} 把汉字展开成 2-gram，这里在查询侧用**同一套规则**生成 tsquery，
 * 两边对齐后中文名词召回从 0~2 提升到 50+（实测「高标准农田」59 命中）。
 * <p>
 * 三个要点：
 * <ol>
 *   <li>含停用字的 bigram（"什么""包括""么是"）直接丢弃——否则长问句的严格查询必然落空；</li>
 *   <li>每个词元用单引号包裹，彻底消除 {@code GB/T 30600} 造成的 tsquery 语法错（原来会 500）；</li>
 *   <li>同时给出**严格版**（汉字 bigram 全 AND）与**宽松版**（bigram OR），
 *       调用方先严后宽，宽松版的排序交给 gte-rerank 精排。</li>
 * </ol>
 *
 * @author Codex
 */
@Component
@Slf4j
public class TsQueryBuilder {

    /** 汉字串 */
    private static final Pattern HAN_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");

    /** ASCII/数字词元：GB/T、30600、20kv、8% 等（simple 配置不会在 "/" 处切分，所以 gb/t 是一个词元） */
    private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9./-]*");

    /**
     * 停用字：只要 2-gram 里含有其中任意一个字，就丢弃该 bigram。
     * 目的是让「什么是高标准农田」「农田地力提升工程包括什么」这类问句的严格版也能命中。
     */
    private static final String STOP_CHARS =
            "的了是在有和与或及什么怎哪些吗呢请你我他她它们包括要会能可就都也还把被从到对为用这那如何例如多少哪";

    /** 英文停用词（中文资料里很少，防御性保留） */
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "is", "are", "of", "to", "and", "or");

    /**
     * 一次查询的两种形态。
     *
     * @param strict 严格版：所有汉字 bigram 都命中（精确、命中数少）
     * @param loose  宽松版：任意汉字 bigram 命中即可（召回广，靠 ts_rank/精排排序）
     */
    public record TsQueries(String strict, String loose) {
        public static final TsQueries EMPTY = new TsQueries("", "");

        public boolean isEmpty() {
            return strict.isBlank() && loose.isBlank();
        }
    }

    /**
     * 构造严格版与宽松版 tsquery。
     *
     * @param query 用户问题
     * @return 两种形态；都为空时调用方应跳过词法检索
     */
    public TsQueries build(String query) {
        if (query == null || query.isBlank()) {
            return TsQueries.EMPTY;
        }

        Set<String> bigrams = new LinkedHashSet<>();
        Matcher hanMatcher = HAN_RUN.matcher(query);
        while (hanMatcher.find()) {
            String run = hanMatcher.group();
            if (run.length() == 1) {
                continue;                       // 单字噪声太大，不参与
            }
            for (int i = 0; i + 2 <= run.length(); i++) {
                String bigram = run.substring(i, i + 2);
                if (isMeaningful(bigram)) {
                    bigrams.add(bigram);
                }
            }
        }

        Set<String> ascii = new LinkedHashSet<>();
        Matcher asciiMatcher = ASCII_TOKEN.matcher(query);
        while (asciiMatcher.find()) {
            String token = asciiMatcher.group().toLowerCase();
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                ascii.add(token);
            }
        }

        if (bigrams.isEmpty() && ascii.isEmpty()) {
            return TsQueries.EMPTY;
        }

        String andBigrams = quoteJoin(bigrams, " & ");
        String orBigrams = quoteJoin(bigrams, " | ");
        String codes = quoteJoin(ascii, " & ");

        TsQueries queries = new TsQueries(combine(andBigrams, codes), combine(orBigrams, codes));
        log.debug("[TsQuery] '{}' → 严格='{}' 宽松='{}'", query, queries.strict(), queries.loose());
        return queries;
    }

    /** bigram 里含停用字就丢弃（"什么""么是""包括"这些词文里几乎不会出现） */
    private boolean isMeaningful(String bigram) {
        for (int i = 0; i < bigram.length(); i++) {
            if (STOP_CHARS.indexOf(bigram.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private String quoteJoin(Set<String> tokens, String separator) {
        return tokens.stream()
                .map(token -> "'" + token.replace("'", "") + "'")
                .collect(Collectors.joining(separator));
    }

    /** 汉字部分与 ASCII 部分用 AND 相连；中文部分含 OR 时加括号，避免优先级歧义 */
    private String combine(String chinese, String codes) {
        if (chinese.isBlank()) {
            return codes;
        }
        if (codes.isBlank()) {
            return chinese.contains("|") ? "(" + chinese + ")" : chinese;
        }
        return "(" + chinese + ") & " + codes;
    }
}
