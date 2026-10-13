package edu.lcl.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.lcl.rag.dto.RagResponse;
import edu.lcl.rag.entity.DocChunk;
import edu.lcl.rag.entity.DocParentChunk;
import edu.lcl.rag.repository.KbDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 来源构建的边界：拒答时不挂来源，也不再"无引用就列出全部块"。
 *
 * @author Codex
 */
class SourceBuilderTest {

    private SourceBuilder sourceBuilder;

    @BeforeEach
    void setUp() {
        sourceBuilder = new SourceBuilder(new CitationParser(), mock(KbDocumentRepository.class), new ObjectMapper());
    }

    /** 回答"未找到"时不应给出任何来源（回归：曾出现「1+1=?」挂着第 15 页来源）。 */
    @Test
    void refusalAnswerHasNoSources() {
        List<RagResponse.Source> sources = sourceBuilder.buildSources(
                "在知识库中未找到关于“1+1=?”的信息。", List.of(hit()));

        assertThat(sources).isEmpty();
    }

    /** 有内容但没有引用标注时也不挂来源（避免给出与回答无关的出处）。 */
    @Test
    void answerWithoutCitationsHasNoSources() {
        List<RagResponse.Source> sources = sourceBuilder.buildSources(
                "高标准农田建设应遵循规划引导、因地制宜等基本原则，并统筹安排各项工程。", List.of(hit()));

        assertThat(sources).isEmpty();
    }

    /** 正常引用仍能解析出来。 */
    @Test
    void citedAnswerKeepsItsSource() {
        List<RagResponse.Source> sources = sourceBuilder.buildSources(
                "施工组织设计包括施工条件、施工布置等。（来源：[参考1]）", List.of(hit()));

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getParentId()).isEqualTo(25L);
    }

    private ParentContextService.ParentHit hit() {
        DocParentChunk parent = new DocParentChunk();
        parent.setId(25L);
        parent.setDocId(1L);
        parent.setDocVersion(2);
        parent.setSectionTitle("6 初步设计报告规定 / 6.7 施工组织设计");
        parent.setContent("6.7 施工组织设计 6.7.1 主要包括施工条件…");

        DocChunk child = new DocChunk();
        child.setId(58L);
        child.setDocId(1L);
        child.setDocVersion(2);
        child.setParentId(25L);
        child.setPageNum(10);
        child.setContent("6.7 施工组织设计 6.7.1 主要包括施工条件、施工布置。");

        return new ParentContextService.ParentHit(
                parent, new HybridRetrieverService.ScoredChunk(child, 0.8D));
    }
}
