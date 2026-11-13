package edu.lcl.rag.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** 首页先展示业务快捷问题；只有资料问题进入答案缓存及预热白名单。 */
@Component
@ConfigurationProperties(prefix = "rag.chat")
@Getter
@Setter
public class HotQuestionCatalog {

    private List<String> hotQuestions = List.of();
    private List<String> businessHotQuestions = List.of();

    /** 保持原资料问题名单的缓存语义，不能把业务问题加入该返回值。 */

    public List<String> questions() {
        return hotQuestions == null ? List.of() : hotQuestions.stream()
                .filter(question -> question != null && !question.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    /** 首页展示顺序：业务查询在前，资料问题在后。业务查询不参与答案缓存或预热。 */
    public List<String> displayQuestions() {
        return java.util.stream.Stream.concat(
                        businessHotQuestions == null ? java.util.stream.Stream.<String>empty()
                                : businessHotQuestions.stream(),
                        questions().stream())
                .filter(question -> question != null && !question.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    public boolean contains(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = AnswerCacheService.normalize(question);
        return questions().stream()
                .anyMatch(item -> AnswerCacheService.normalize(item).equals(normalized));
    }
}
