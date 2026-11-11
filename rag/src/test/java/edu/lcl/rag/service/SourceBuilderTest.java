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

    /**
     * 多文档兜底：上下文里有 doc1 / doc2 两份文档，模型只标了 [参考1]，
     * 另一份也要作为来源出现——"一个知识库多份文档 → 多个参考来源"不能靠模型标全。
     */
    @Test
    void uncitedDocumentIsAddedAsSource() throws Exception {
        setIncludeUncitedDocuments(true);

        List<RagResponse.Source> sources = sourceBuilder.buildSources(
                "施工组织设计包括施工条件、施工布置等。（来源：[参考1]）",
                List.of(hit(), otherDocumentHit()));

        assertThat(sources).hasSize(2);
        assertThat(sources).extracting(RagResponse.Source::getDocId).containsExactly(1L, 2L);
        assertThat(sources.get(0).getParentId()).isEqualTo(25L);
    }

    /** 兜底不重复：两份文档都被标注时，来源仍是两条，不会补出多余条目。 */
    @Test
    void citedDocumentsAreNotDuplicated() throws Exception {
        setIncludeUncitedDocuments(true);

        List<RagResponse.Source> sources = sourceBuilder.buildSources(
                "施工组织设计包括施工条件、施工布置等。（来源：[参考1][参考2]）",
                List.of(hit(), otherDocumentHit()));

        assertThat(sources).hasSize(2);
    }

    /** 开关关闭时保持旧行为（无标注就不挂来源）。 */
    @Test
    void disabledFallbackKeepsLegacyBehaviour() {
        List<RagResponse.Source> sources = sourceBuilder.buildSources(
                "施工组织设计包括施工条件、施工布置等。", List.of(hit(), otherDocumentHit()));

        assertThat(sources).isEmpty();
    }

    /** SourceBuilder 的开关来自 @Value，单元测试里直接 new，需要手动打开。 */
    private void setIncludeUncitedDocuments(boolean value) throws Exception {
        var field = SourceBuilder.class.getDeclaredField("includeUncitedDocuments");
        field.setAccessible(true);
        field.setBoolean(sourceBuilder, value);
    }

    /** 另一份文档（docId=2）的命中，分数更低。 */
    private ParentContextService.ParentHit otherDocumentHit() {
        DocParentChunk parent = new DocParentChunk();
        parent.setId(26L);
        parent.setDocId(2L);
        parent.setDocVersion(2);
        parent.setSectionTitle("6.6 项目工程设计");
        parent.setContent("6.6.4.3 田间道路工程设计应明确道路结构型式和具体技术参数。");

        DocChunk child = new DocChunk();
        child.setId(59L);
        child.setDocId(2L);
        child.setDocVersion(2);
        child.setParentId(26L);
        child.setPageNum(7);
        child.setContent("6.6.4.3 田间道路工程设计应明确道路结构型式和具体技术参数。");

        return new ParentContextService.ParentHit(
                parent, new HybridRetrieverService.ScoredChunk(child, 0.6D));
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
