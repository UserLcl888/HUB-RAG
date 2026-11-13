package edu.lcl.rag.business.project;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 不依赖城市别名、演示项目名或数据库扩展；只在指定年度真实项目名称中做候选召回。 */
@Component
public class ProjectNameMatcher {
    private static final List<String> GENERIC_TERMS = List.of(
            "高标准农田建设", "高标准农田", "农田建设", "改造提升", "新建项目", "建设项目", "项目");

    public String normalize(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("(?:19|20|21)\\d{2}年?", "");
        for (String term : GENERIC_TERMS) {
            normalized = normalized.replace(term, "");
        }
        return normalized.replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }

    public double fuzzyScore(String normalizedQuery, String normalizedName) {
        if (normalizedQuery.length() < 2 || normalizedName.isBlank()) {
            return 0;
        }
        Set<String> queryPairs = pairs(normalizedQuery);
        Set<String> namePairs = pairs(normalizedName);
        long sharedPairs = queryPairs.stream().filter(namePairs::contains).count();
        Set<Integer> queryCharacters = new HashSet<>();
        normalizedQuery.codePoints().forEach(queryCharacters::add);
        Set<Integer> nameCharacters = new HashSet<>();
        normalizedName.codePoints().forEach(nameCharacters::add);
        long sharedCharacters = queryCharacters.stream().filter(nameCharacters::contains).count();
        double pairRecall = (double) sharedPairs / queryPairs.size();
        double characterRecall = (double) sharedCharacters / queryCharacters.size();
        // 至少共享一个连续词组；不能只因零散同字就认作相近项目。
        return sharedPairs == 0 ? 0 : 0.75 * pairRecall + 0.25 * characterRecall;
    }

    private Set<String> pairs(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 1 < value.length(); index++) {
            result.add(value.substring(index, index + 2));
        }
        return result;
    }
}
