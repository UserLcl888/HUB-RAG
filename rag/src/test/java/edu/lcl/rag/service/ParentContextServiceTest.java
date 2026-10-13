package edu.lcl.rag.service;

import edu.lcl.rag.entity.DocChunk;
import edu.lcl.rag.entity.DocParentChunk;
import edu.lcl.rag.repository.DocParentChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证问答上下文同时包含命中位置和所属父块内容。
 *
 * @author Codex
 * @since 2026-10-13
 */
class ParentContextServiceTest {

    /** 父块上下文服务。 */
    private ParentContextService service;

    /** 测试用分词计数器。 */
    private ContextTrimmerService tokenCounter;

    /** 初始化无需外部数据库的上下文服务。 */
    @BeforeEach
    void setUp() {
        tokenCounter = new ContextTrimmerService();
        tokenCounter.init();
        service = new ParentContextService(mock(DocParentChunkRepository.class), tokenCounter);
        ReflectionTestUtils.setField(service, "maxContextTokens", 9000);
        ReflectionTestUtils.setField(service, "maxParents", 5);
    }

    /** 子块命中应在父块原文中定位，并保留同节其他要求。 */
    @Test
    void marksMatchedChildWithinFullParentWithoutDuplicatingIt() {
        String parentText = "项目工程设计应统筹安排。灌溉工程应明确布置。道路工程应说明衔接。";
        var hit = createHit(parentText, "灌溉工程应明确布置。");

        String context = service.buildContext(List.of(hit));

        assertThat(context).contains("所属章节：项目工程设计")
                .contains("项目工程设计应统筹安排。")
                .contains("【命中子块开始】灌溉工程应明确布置。【命中子块结束】")
                .contains("道路工程应说明衔接。");
        assertThat(context.split("灌溉工程应明确布置。", -1)).hasSize(2);
    }

    /** 旧索引中的子块与父块文本不完全一致时，仍将二者一同提供给模型。 */
    @Test
    void keepsChildAndParentWhenExactMatchIsUnavailable() {
        var hit = createHit("父块补充工程衔接要求。", "直接命中的设计内容。");

        String context = service.buildContext(List.of(hit));

        assertThat(context).contains("父块补充工程衔接要求。")
                .contains("直接命中的子块：\n直接命中的设计内容。");
    }

    /** 查询子条款时只向模型提供所属二级小节，排除其他章节与相邻小节。 */
    @Test
    void selectsOnlyNearestSectionForClauseQuestion() {
        var chapterFive = createHit(1L, "5 其他章节", "其他章节内容。", "其他章节内容。");
        var chapterSix = createHit(2L, "6 初步设计", "章节总体内容。", "章节总体内容。");
        var sectionSixSeven = createHit(3L, "6 初步设计 / 6.7 施工组织设计",
                "6.7施工组织设计\n6.7.1主要包括施工条件。\n6.7.5施工质量与安全。\n6.7.6施工进度计划。",
                "6.7.1主要包括施工条件。");
        var sectionSixEight = createHit(4L, "6 初步设计 / 6.8 其他设计",
                "6.8其他设计\n其他小节内容。", "其他小节内容。");

        var selected = service.selectForContext("6.7.1主要包括什么？",
                List.of(chapterFive, sectionSixEight, chapterSix, sectionSixSeven));

        assertThat(selected).containsExactly(sectionSixSeven);
        assertThat(service.buildContext(selected))
                .contains("6.7.1", "6.7.5", "6.7.6")
                .doesNotContain("其他章节内容", "章节总体内容", "6.8其他设计");
    }

    /** 用户只说小节名称时，也应选择该小节而非整章或其他小节。 */
    @Test
    void selectsSectionByTitleWithoutNumber() {
        var target = createHit(3L, "6 初步设计 / 6.7 施工组织设计",
                "施工组织设计全文。", "施工组织设计全文。");
        var unrelated = createHit(4L, "6 初步设计 / 6.8 工程管理",
                "工程管理内容。", "工程管理内容。");

        assertThat(service.selectForContext("施工组织设计是什么？", List.of(unrelated, target)))
                .containsExactly(target);
    }

    /** 先筛选问题所属小节再按父块数量限制，避免相关结果排序靠后时被截掉。 */
    @Test
    void scopesBeforeApplyingParentCountLimit() {
        var target = createHit(7L, "6 初步设计 / 6.7 施工组织设计",
                "6.7施工组织设计\n6.7.1施工条件。\n6.7.6进度计划。",
                "6.7.1施工条件。");
        var unrelated = new java.util.ArrayList<ParentContextService.ParentHit>();
        for (long id = 1; id <= 6; id++) {
            unrelated.add(createHit(id, "6 初步设计 / 6." + id + " 其他小节",
                    "其他小节内容。", "其他小节内容。"));
        }
        unrelated.add(target);

        assertThat(service.selectForContext("6.7.1是什么？", unrelated))
                .containsExactly(target);
    }

    /**
     * 方案 A：问题只提标题、没写编号时，把库里"同名小节"的父块全部装入
     *（术语条目 3.6 给定义 + 正文小节 6.7 给 6.7.1~6.7.6）；写了编号则只按编号取。
     */
    @Test
    void expandsSameTitleSectionsAcrossChapters() {
        DocParentChunkRepository repository = mock(DocParentChunkRepository.class);
        ParentContextService scoped = new ParentContextService(repository, tokenCounter);
        ReflectionTestUtils.setField(scoped, "maxContextTokens", 9000);
        ReflectionTestUtils.setField(scoped, "maxParents", 5);

        DocParentChunk term = parent(1L, 0, "3 术语和定义 / 3.6 施工组织设计",
                "3.6 施工组织设计 以拟建工程为对象编制的,在人力和物力、时间和空间、技术和组织等方面所做的全面、合理安排。");
        DocParentChunk section = parent(2L, 1, "6 初步设计报告规定 / 6.7 施工组织设计",
                "6.7 施工组织设计 6.7.1 主要包括施工条件、施工布置。6.7.5 施工质量与安全应说明要求。");
        when(repository.findByDocIdAndDocVersionOrderByParentIndexAsc(2L, 1))
                .thenReturn(List.of(term, section));

        // 检索只召回了"术语条目"那一个
        ParentContextService.ParentHit termHit = new ParentContextService.ParentHit(
                term, new HybridRetrieverService.ScoredChunk(child(term, "3.6 施工组织设计 以拟建工程为对象编制的…"), 0.8D));

        List<ParentContextService.ParentHit> selected =
                scoped.selectForContext("施工组织设计给我说说?", List.of(termHit));

        assertThat(selected).hasSize(2);
        assertThat(selected.get(0).parent().getId()).isEqualTo(1L);
        assertThat(selected.get(1).continuation()).isTrue();
        assertThat(selected.get(1).parent().getId()).isEqualTo(2L);
        assertThat(scoped.buildContext(selected)).contains("6.7.1", "6.7.5");

        // 写了编号 → 不做同名扩展，只取 6.7 那一节
        ParentContextService.ParentHit sectionHit = new ParentContextService.ParentHit(
                section, new HybridRetrieverService.ScoredChunk(child(section, "6.7.1 主要包括施工条件、施工布置。"), 0.9D));
        assertThat(scoped.selectForContext("6.7.1主要包括什么?", List.of(sectionHit)))
                .extracting(item -> item.parent().getId())
                .containsExactly(2L);
    }

    /** 构造父块实体（同一文档 docId=2 / docVersion=1，按 parentIndex 排序）。 */
    private DocParentChunk parent(Long id, int parentIndex, String sectionTitle, String content) {
        DocParentChunk parent = new DocParentChunk();
        parent.setId(id);
        parent.setDocId(2L);
        parent.setDocVersion(1);
        parent.setParentIndex(parentIndex);
        parent.setSectionTitle(sectionTitle);
        parent.setContent(content);
        return parent;
    }

    /** 构造子块实体。 */
    private DocChunk child(DocParentChunk parent, String content) {
        DocChunk docChunk = new DocChunk();
        docChunk.setId(100L + parent.getId());
        docChunk.setDocId(parent.getDocId());
        docChunk.setDocVersion(parent.getDocVersion());
        docChunk.setParentId(parent.getId());
        docChunk.setContent(content);
        return docChunk;
    }

    /** 构造关联的父块和检索子块。 */
    private ParentContextService.ParentHit createHit(String parentText, String childText) {
        return createHit(1L, "项目工程设计", parentText, childText);
    }

    /**
     * 父块因超限被切开（尾块带【本节未完，续见后一块】）时，
     * 检索侧要按 parent_index 相邻性把接续块一并装入上下文，保证条款一条不丢。
     */
    @Test
    void expandsContinuationBlockMarkedByChunker() {
        DocParentChunkRepository repository = mock(DocParentChunkRepository.class);
        ParentContextService scoped = new ParentContextService(repository, tokenCounter);
        ReflectionTestUtils.setField(scoped, "maxContextTokens", 9000);
        ReflectionTestUtils.setField(scoped, "maxParents", 5);

        DocParentChunk first = parent(1L, 0, "6 初步设计 / 6.7 施工组织设计",
                "6.7施工组织设计\n6.7.1主要包括施工条件。\n" + ParentChunkService.PARENT_CONTINUE_MARK);
        DocParentChunk second = parent(2L, 1, "6 初步设计 / 6.7 施工组织设计",
                ParentChunkService.PARENT_RESUME_MARK + "\n6.7.5施工质量与安全。");
        when(repository.findByDocIdAndDocVersionOrderByParentIndexAsc(2L, 1))
                .thenReturn(List.of(first, second));

        ParentContextService.ParentHit hit = new ParentContextService.ParentHit(
                first, new HybridRetrieverService.ScoredChunk(child(first, "6.7.1主要包括施工条件。"), 0.9D));

        List<ParentContextService.ParentHit> selected =
                scoped.selectForContext("6.7.1主要包括什么？", List.of(hit));

        assertThat(selected).hasSize(2);
        assertThat(selected.get(0).continuation()).isFalse();
        assertThat(selected.get(1).continuation()).isTrue();
        assertThat(selected.get(1).parent().getContent()).contains("6.7.5");
        assertThat(scoped.buildContext(selected))
                .contains("6.7.1", "6.7.5", "本节接续内容");
    }

    /** 构造指定小节下关联的父块和检索子块。 */
    private ParentContextService.ParentHit createHit(Long id, String sectionTitle,
                                                     String parentText, String childText) {
        DocParentChunk parent = new DocParentChunk();
        parent.setId(id);
        parent.setDocId(2L);
        parent.setSectionTitle(sectionTitle);
        parent.setContent(parentText);

        DocChunk child = new DocChunk();
        child.setId(3L);
        child.setDocId(2L);
        child.setParentId(id);
        child.setContent(childText);

        return new ParentContextService.ParentHit(
                parent, new HybridRetrieverService.ScoredChunk(child, 0.9D));
    }
}
