package edu.lcl.rag.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** 首页展示、预热和答案缓存共用的唯一热门问题名单。 */
@Component
@ConfigurationProperties(prefix = "rag.chat")
@Getter
@Setter
public class HotQuestionCatalog {

    private List<String> hotQuestions = List.of();

    public List<String> questions() {
        return hotQuestions == null ? List.of() : hotQuestions.stream()
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
