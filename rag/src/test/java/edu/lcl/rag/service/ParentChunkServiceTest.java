package edu.lcl.rag.service;

import edu.lcl.rag.service.loader.ParseResult;
import edu.lcl.rag.service.splitter.ChunkResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证标准文本的按标题分块与原文页码保留。
 *
 * @author Codex
 * @since 2026-10-12
 */
class ParentChunkServiceTest {

    private final ParentChunkService service = new ParentChunkService(6000, 8500);

    @Test
    void joinsStandaloneNumberWithTitleAcrossPages() {
        List<ParentChunkService.ParentBlock> parents = service.split(document(
                "GB/T 30600-2022\n3 术语和定义\n3.1",
                "建设项目\n正文第一段。\n3.2 其他术语\n正文第二段。"));

        assertThat(parents).hasSize(3);
        assertThat(parents.get(0).sectionTitle()).isEqualTo("3 术语和定义");
        assertThat(parents.get(1).sectionTitle()).isEqualTo("3 术语和定义 / 3.1 建设项目");
        assertThat(parents.get(1).content()).contains("3.1\n建设项目\n正文第一段");
        // 子块改为按条款切分后，跨页内容不再按页拆开：整块保留，页码取首行所在页
        assertThat(service.children(parents.get(1))).extracting(ChunkResult::getPageNum).containsExactly(1);
        assertThat(parents.get(2).sectionTitle()).isEqualTo("3 术语和定义 / 3.2 其他术语");
    }

    @Test
    void normalizesFullWidthNumbersAndKeepsCrossPageSection() {
        List<ParentChunkService.ParentBlock> parents = service.split(document(
                "３．１ 高标准农田\n第一页面内容。",
                "第二页面内容。\n３．２ 下一节\n新章节。"));

        assertThat(parents).hasSize(2);
        assertThat(parents.get(0).sectionTitle()).isEqualTo("3.1 高标准农田");
        assertThat(parents.get(0).content()).contains("第二页面内容");
        assertThat(parents.get(1).sectionTitle()).isEqualTo("3.2 下一节");
    }

    @Test
    void retainsUntitledTextAndLongSectionsWithoutDroppingCharacters() {
        String text = "正文。".repeat(4000);
        List<ParentChunkService.ParentBlock> parents = service.split(document(text));

        assertThat(parents).hasSizeGreaterThan(1);
        assertThat(parents).allSatisfy(parent -> {
            assertThat(parent.content().length()).isLessThanOrEqualTo(8500);
            assertThat(service.children(parent)).allSatisfy(child ->
                    assertThat(child.getContent().length()).isLessThanOrEqualTo(6000));
        });
        assertThat(parents.stream().map(ParentChunkService.ParentBlock::content).reduce("", String::concat))
                .isEqualTo(text);
    }

    @Test
    void keepsAppendixAndMarkdownHeading() {
        List<ParentChunkService.ParentBlock> parents = service.split(document(
                "附录 A 图纸要求\n说明。\n## 资料准备\n清单。"));

        assertThat(parents).hasSize(2);
        assertThat(parents.get(0).sectionTitle()).isEqualTo("附录 A 图纸要求");
        assertThat(parents.get(1).sectionTitle()).isEqualTo("附录 A 图纸要求 / 资料准备");
    }

    /**
     * 回归：PDF 会把长条款换行，换行处没有句末标点。
     * 旧实现只看行尾标点，把 6.5.1 / 6.5.2 / 6.5.5 误判为标题，
     * 使得"6.5 建设规划布局"在库里变成 4 个父块（实测 docId=6 的 307/308/309/310），
     * 其中一块只剩标题文本，于是"这一节讲了什么"永远答不全。
     * 下面是该文档第 8 页的真实断行文本。
     */
    @Test
    void keepsWrappedClausesInsideOneSection() {
        List<ParentChunkService.ParentBlock> parents = service.split(document("""
                6.5 建设规划布局
                6.5.1 项目区建设规划布局应包括指导思想及基本原则、目标任务、建设规模和内容、总体
                布局等内容。
                6.5.2 指导思想及基本原则应说明项目区规划布局的指导思想,根据相关法律、法规、政策、
                社会经济发展水平和项目建设需求等因素,提出的项目规划原则。
                6.5.3 建设目标任务说明项目区建设总目标及各项工程的建设任务。
                6.5.4 建设规模和内容应说明项目建设总规模和各项工程主要建设内容。
                6.5.5 总体布局应根据项目建设目标及存在问题,对农田基础设施建设工程(田块整治工程、
                灌溉与排水工程、田间道路工程、农田防护与生态环境保护工程、农田输配电工程、其他工
                程)和农田地力提升工程(土壤改良工程、障碍土层消除工程、土壤培肥工程)进行全面规
                划和统筹安排,确定工程布置和规模,阐明工程之间的衔接配套关系,并充分说明工程布局
                的合理性。具体项目工程布局应按照GB/T 30600规定执行。"""));

        assertThat(parents).hasSize(1);
        assertThat(parents.get(0).sectionTitle()).isEqualTo("6.5 建设规划布局");
        assertThat(parents.get(0).content())
                .contains("6.5.1")
                .contains("6.5.2")
                .contains("6.5.3")
                .contains("6.5.4")
                .contains("6.5.5");
        assertThat(service.children(parents.get(0))).hasSize(4);
    }

    /** 无空格章节标题及其连续条款应组成同一父块，支持按整节回答。 */
    @Test
    void keepsConstructionOrganizationClausesTogether() {
        List<ParentChunkService.ParentBlock> parents = service.split(document("""
                6.7施工组织设计
                6.7.1主要包括施工条件、施工布置、施工工艺和技术流程、施工质量与安全、施工进度计
                划等。
                6.7.2施工组织及施工条件应说明项目区工程实施的施工组织，以及自然条件、交通条件、
                天然建筑材料等情况。
                6.7.3施工布置应说明施工总体布置的原则、分区布置情况等。
                6.7.4施工工艺和技术流程应按土石方工程、混凝土工程、管道工程、机电设备安装、
                田间道路、农田防护林等主体工程，说明各主体工程施工方法及要求。
                6.7.5施工质量与安全应说明施工中施工质量控制，以及施工安全要求。
                6.7.6施工进度计划应说明项目区主体工程的施工进度，并列施工进度表。"""));

        assertThat(parents).hasSize(1);
        assertThat(parents.get(0).sectionTitle()).isEqualTo("6.7 施工组织设计");
        assertThat(parents.get(0).content()).contains(
                "6.7.1", "6.7.2", "6.7.3", "6.7.4", "6.7.5", "6.7.6");
    }

    /** 反向保护：含顿号的短标题仍要认成标题，不要为了修上面的问题把标题也吞掉。 */
    @Test
    void keepsShortHeadingContainingEnumerationComma() {
        List<ParentChunkService.ParentBlock> parents = service.split(document(
                "3.2 年假、病假与事假\n本节说明假期类型。"));

        assertThat(parents).hasSize(1);
        assertThat(parents.get(0).sectionTitle()).isEqualTo("3.2 年假、病假与事假");
    }

    /** 短条款标题也属于最近二级小节，不能把整个小节切散。 */
    @Test
    void keepsShortClauseHeadingsWithinSection() {
        List<ParentChunkService.ParentBlock> parents = service.split(document("""
                6.5 建设规划布局
                6.5.1 一般规定
                本节给出通用要求。
                6.5.5 总体布局
                布局要求如下。"""));

        assertThat(parents).hasSize(1);
        assertThat(parents.get(0).sectionTitle()).isEqualTo("6.5 建设规划布局");
        assertThat(parents.get(0).content()).contains("6.5.1 一般规定", "6.5.5 总体布局");
    }

    /** 同级小节的边界必须严格隔离：父块只包含 6.7，不含第 5 章或 6.8。 */
    @Test
    void isolatesTheNearestNumberedSection() {
        List<ParentChunkService.ParentBlock> parents = service.split(document("""
                5 上一章
                上一章的内容。
                6 初步设计
                本章概要。
                6.7施工组织设计
                6.7.1主要包括施工条件、施工布置。
                6.7.2施工组织应说明条件。
                6.7.3施工布置应说明原则。
                6.7.4施工工艺应说明施工方法。
                6.7.5施工质量与安全应说明要求。
                6.7.6施工进度计划应说明进度。
                6.8其他设计
                其他小节的内容。"""));

        assertThat(parents).extracting(ParentChunkService.ParentBlock::sectionTitle)
                .containsExactly("5 上一章", "6 初步设计",
                        "6 初步设计 / 6.7 施工组织设计",
                        "6 初步设计 / 6.8 其他设计");
        assertThat(parents.get(2).content())
                .contains("6.7施工组织设计", "6.7.1", "6.7.6")
                .doesNotContain("上一章的内容", "本章概要", "6.8其他设计", "其他小节的内容");
    }

    /**
     * 二级小节优先整块；只有真的超过父块上限才切开，并打【本节未完/续前】标记。
     * 标记的意义：内容一条不丢，检索侧（ParentContextService）据此把两块一起装入上下文。
     */
    @Test
    void splitsOverlongSectionWithContinuationMarks() {
        String longContent = "6.7施工组织设计\n"
                + "6.7.1主要包括施工条件。\n"
                + "6.7.5施工质量与安全。\n"
                + "施工要求。".repeat(3000)
                + "\n6.8其他设计\n其他小节内容。";

        List<ParentChunkService.ParentBlock> parents = service.split(document(longContent));

        List<ParentChunkService.ParentBlock> section = parents.stream()
                .filter(parent -> "6.7 施工组织设计".equals(parent.sectionTitle()))
                .toList();
        // 超长小节被切成连续的同名父块（这里 3 块：标题+条款 / 超长行前半 / 超长行后半）
        assertThat(section).hasSizeGreaterThanOrEqualTo(2);
        assertThat(section.get(0).content())
                .contains("6.7.1", "6.7.5", ParentChunkService.PARENT_CONTINUE_MARK)
                .doesNotContain(ParentChunkService.PARENT_RESUME_MARK);
        assertThat(section.get(1).content())
                .startsWith(ParentChunkService.PARENT_RESUME_MARK)
                .contains(ParentChunkService.PARENT_CONTINUE_MARK);
        assertThat(section.get(section.size() - 1).content())
                .startsWith(ParentChunkService.PARENT_RESUME_MARK)
                .doesNotContain(ParentChunkService.PARENT_CONTINUE_MARK);
        assertThat(parents).extracting(ParentChunkService.ParentBlock::sectionTitle)
                .contains("6.8 其他设计");
    }

    /** 子块=单条款：6.3.1、6.3.2、6.3.3 各自成块（条款内的分项留在本块）。 */
    @Test
    void keepsEachClauseAsItsOwnChild() {
        List<ParentChunkService.ParentBlock> parents = service.split(document("""
                6.3 灌溉与排水工程
                6.3.1 灌溉与排水工程指为防治农田旱、涝、渍和盐碱等对农业生产的危害所修建的水利设施,应遵循水土资源合理利用的原则。
                6.3.2 灌溉与排水工程应配套完整,符合灌溉与排水系统水位、水量、流量、水质处理、运行、管理等要求。
                6.3.3 灌溉工程设计时应首先确定灌溉设计保证率,并据此确定工程规模与布置方式。"""));

        List<ChunkResult> children = service.children(parents.get(0));

        assertThat(parents).hasSize(1);
        assertThat(children).hasSize(3);
        // 小节标题行(不足 40 字)并入第一个条款块，不单独成块
        assertThat(children.get(0).getContent()).contains("6.3 灌溉与排水工程", "6.3.1");
        assertThat(children.get(1).getContent()).startsWith("6.3.2");
        assertThat(children.get(2).getContent()).startsWith("6.3.3");
    }

    /** 条款内的 ——— 分项与 (1)(2) 子项不能拆散，仍属同一个子块。 */
    @Test
    void keepsClauseSubItemsInSameChild() {
        List<ParentChunkService.ParentBlock> parents = service.split(document("""
                6.3 灌溉与排水工程
                6.3.5 水源工程应根据水源条件、取水方式、灌溉规模及综合利用要求,选用经济合理的工程形式。水源工程建设应符合下列要求。
                ———井灌工程的泵、动力输变电设备和井房等配套率应达到100%。
                ———塘堰(坝)容量应小于100000 立方米,挡水、泄水和放水建筑物等应配套齐全。
                6.3.6 渠(沟)道、管道工程应按灌溉与排水规模、地形条件、宜机作业和耕作要求合理布置。"""));

        List<ChunkResult> children = service.children(parents.get(0));

        assertThat(children).hasSize(2);
        assertThat(children.get(0).getContent())
                .contains("6.3.5", "井灌工程的泵", "塘堰(坝)容量")
                .doesNotContain("6.3.6");
        assertThat(children.get(1).getContent()).startsWith("6.3.6");
    }

    /** 跨页条款保留为一块，页码取条款首行所在页。 */
    @Test
    void keepsCrossPageClauseAsOneChild() {
        List<ParentChunkService.ParentBlock> parents = service.split(document(
                "6.2 田块整治工程\n6.2.1 耕作田块是由田间末级固定沟、渠、路、田坎等围成的,满足农业作业需要的基本耕作单元。",
                "应因地制宜进行耕作田块布置,合理规划,提高田块归并程度,实现耕作田块相对集中。\n"
                        + "6.2.2 耕作田块应实现田面平整,田面高差、横向坡度和纵向坡度根据土壤条件和灌溉方式合理确定。"));

        List<ChunkResult> children = service.children(parents.get(0));

        assertThat(children).hasSize(2);
        assertThat(children.get(0).getPageNum()).isEqualTo(1);
        assertThat(children.get(0).getContent()).contains("6.2.1", "实现耕作田块相对集中");
        assertThat(children.get(1).getPageNum()).isEqualTo(2);
        assertThat(children.get(1).getContent()).startsWith("6.2.2");
    }

    /** 短条款与下一条合并；合并只发生在同一小节内，绝不跨到 6.2.1。 */
    @Test
    void mergesShortClauseWithinSectionOnly() {
        List<ParentChunkService.ParentBlock> parents = service.split(document("""
                6.1 一般规定
                6.1.4 鼓励应用绿色材料和工艺。
                6.1.5 田间基础设施占地率指农田中灌溉与排水、田间道路、农田防护与生态环境保护、农田输配电等设施占地面积与建设区农田面积的比例,一般不应高于8%。
                6.2 田块整治工程
                6.2.1 耕作田块应实现田面平整。"""));

        List<ChunkResult> section = service.children(parents.get(0));
        List<ChunkResult> next = service.children(parents.get(1));

        assertThat(section).hasSize(1);
        assertThat(section.get(0).getContent()).contains("6.1.4", "6.1.5");
        assertThat(next).hasSize(1);
        assertThat(next.get(0).getContent()).contains("6.2.1").doesNotContain("6.1.5");
    }

    /** 小节最后一条太短（没有下一条可并）时，回退与前一条合并。 */
    @Test
    void mergesTailShortClauseBackward() {
        List<ParentChunkService.ParentBlock> parents = service.split(document("""
                6.1 一般规定
                6.1.1 应结合各地实际,采取针对性措施开展高标准农田建设,提升耕地质量并稳定或增加有效耕地面积。
                6.1.2 建成后应加强管护。"""));

        List<ChunkResult> children = service.children(parents.get(0));

        assertThat(children).hasSize(1);
        assertThat(children.get(0).getContent()).contains("6.1.1", "6.1.2");
    }

    /** 无编号区（附录/表格）超过子块上限时回退到"页 → 换行 → 句末"切分，且不产生超限块。 */
    @Test
    void fallsBackForOversizedUnnumberedRegion() {
        String appendix = "附录 C 工程量表\n" + "序号 项目名称 单位 数量。\n".repeat(600);
        List<ParentChunkService.ParentBlock> parents = service.split(document(appendix));

        List<ChunkResult> children = service.children(parents.get(0));

        assertThat(children).hasSizeGreaterThan(1);
        assertThat(children).allSatisfy(child ->
                assertThat(child.getContent().length()).isLessThanOrEqualTo(8500));
    }
    /**
     * 回归：整页最后一行的裸数字是页脚页码，不是编号标题。
     * 旧实现会把它当成"未写标题的编号"，凭空造出一个父块并把后续正文吞进去
     * （实测 6.3 项目区概况、6.6 项目工程设计的正文被页脚数字切开）。
     */
    @Test
    void treatsTrailingPageNumberAsFurniture() {
        List<ParentChunkService.ParentBlock> parents = service.split(document(
                "6 初步设计报告规定\n6.3 项目区概况\n6.3.1 主要包括自然条件。\n6",
                "6.3.5 制约因素分析应从地形地貌、土壤等方面分析。\n6.6 项目工程设计\n设计内容。"));

        assertThat(parents).extracting(ParentChunkService.ParentBlock::sectionTitle)
                .containsExactly(
                        "6 初步设计报告规定",
                        "6 初步设计报告规定 / 6.3 项目区概况",
                        "6 初步设计报告规定 / 6.6 项目工程设计");
        assertThat(parents.get(1).content()).contains("6.3.1").contains("6.3.5");
    }

    private ParseResult document(String... pages) {
        List<ParseResult.PageContent> contents = new java.util.ArrayList<>();
        for (int index = 0; index < pages.length; index++) {
            contents.add(ParseResult.PageContent.builder().pageNum(index + 1).text(pages[index]).build());
        }
        return ParseResult.builder().success(true).pages(contents).totalPages(pages.length).build();
    }
}
