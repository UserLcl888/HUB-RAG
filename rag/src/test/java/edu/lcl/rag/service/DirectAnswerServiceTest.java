package edu.lcl.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 直答判定与本地算术：只对"与知识库无关的简单问题"生效，绝不误伤正经问题。
 *
 * @author Codex
 */
class DirectAnswerServiceTest {

    private final DirectAnswerService service = new DirectAnswerService(mock(ChatClient.class));

    @Test
    void arithmeticIsAnsweredLocally() {
        assertThat(service.shouldAnswerDirectly("1+1=?")).isTrue();
        assertThat(service.evaluate("1+1=?")).contains("1+1 = 2");
        assertThat(service.evaluate("(12+8)×3÷2=?")).contains("(12+8)×3÷2 = 30");
    }

    @Test
    void smallTalkIsAnsweredDirectly() {
        assertThat(service.shouldAnswerDirectly("你好")).isTrue();
        assertThat(service.shouldAnswerDirectly("你是谁？")).isTrue();
    }

    /** 业务问题绝不能被判成直答——否则会失去知识库依据与来源。 */
    @Test
    void knowledgeBaseQuestionsAreNotDirect() {
        assertThat(service.shouldAnswerDirectly("高标准农田建设应满足什么要求？")).isFalse();
        assertThat(service.shouldAnswerDirectly("6.6.4 主要单体工程设计包括什么？")).isFalse();
        assertThat(service.shouldAnswerDirectly("GB/T 30600 是什么标准")).isFalse();
        assertThat(service.shouldAnswerDirectly("灌溉与排水工程设计应符合哪些标准的规定？")).isFalse();
        assertThat(service.shouldAnswerDirectly("")).isFalse();
        assertThat(service.shouldAnswerDirectly(null)).isFalse();
    }

    /** 算不了的表达式不要乱答（交给模型），例如除零。 */
    @Test
    void unparsableExpressionFallsBack() {
        assertThat(service.evaluate("1/0=?")).isEmpty();
        assertThat(service.evaluate("3+")).isEmpty();
    }
}
