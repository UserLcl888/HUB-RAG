package edu.lcl.rag.service;

import edu.lcl.rag.entity.DocChunk;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 记录现有检索链路的风险行为；用于诊断，不代表期望的修复行为。
 *
 * @author Codex
 * @since 2026-10-12
 */
class RetrievalDiagnosticTest {

    /** 中文问题现在会切成 2-gram 词法查询（旧实现是整句当一个词，永远 0 命中）。 */
    @Test
    void chineseQuestionIsSplitIntoBigramTerms() {
        String question = "规划建设布局包括什么内容";
        TsQueryBuilder.TsQueries queries = new TsQueryBuilder().build(question);

        // 严格版：汉字 2-gram 全 AND，且"包括/什么"这类含停用字的组合已被丢弃
        assertThat(queries.strict()).contains("'规划'").contains("'建设'").contains("'布局'");
        assertThat(queries.strict()).doesNotContain("包括").doesNotContain("什么");
        // 宽松版：同样词元但用 OR 连接
        assertThat(queries.loose()).contains("|");
        System.out.println("DIAGNOSTIC bigram strict: " + queries.strict());
        System.out.println("DIAGNOSTIC bigram loose : " + queries.loose());
    }

    /** 验证全部低于置信度阈值时仍保留最高分片段。 */
    @Test
    void unrelatedCandidateSurvivesConfidenceThreshold() {
        ConfidenceFilter filter = new ConfidenceFilter();
        ReflectionTestUtils.setField(filter, "minScore", 0.5);
        DocChunk chunk = new DocChunk();
        chunk.setId(1L);
        chunk.setContent("规划编制内容：指导思想、基本原则、目标任务。");
        var candidate = new HybridRetrieverService.ScoredChunk(chunk, 0.01);

        var result = filter.filter(List.of(candidate), true);

        assertThat(result).containsExactly(candidate);
        System.out.println("DIAGNOSTIC score 0.01 survives threshold 0.5");
    }

    /** 验证候选不超过十五条时精排服务完全不调用模型。 */
    @Test
    void smallCandidateSetSkipsReranking() {
        WebClient.Builder builder = mock(WebClient.Builder.class);
        RerankerService reranker = new RerankerService(builder);
        DocChunk chunk = new DocChunk();
        chunk.setId(1L);
        chunk.setContent("规划编制内容：指导思想、基本原则、目标任务。");
        var candidate = new HybridRetrieverService.ScoredChunk(chunk, 0.02);

        var result = reranker.rerank("规划建设布局包括什么内容", List.of(candidate), 15);

        assertThat(result.reranked()).isFalse();
        assertThat(result.chunks()).containsExactly(candidate);
        verifyNoInteractions(builder);
        System.out.println("DIAGNOSTIC small candidate set bypasses reranker");
    }
}
