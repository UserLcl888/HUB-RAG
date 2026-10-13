package edu.lcl.rag.service.loader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 PDF 中文正文清洗不引入英文或图片占位内容。
 *
 * @author Codex
 * @since 2026-10-13
 */
class PdfParserTest {

    /** PDF 文本解析器。 */
    private final PdfParser parser = new PdfParser();

    /** 过滤英文行，同时保留中文条款和单独排版的章节编号。 */
    @Test
    void retainsChineseTextAndHeadingNumber() {
        String cleaned = parser.cleanText("""
                English-only heading
                6.6
                项目工程设计
                6.6.1 Irrigation 工程设计应说明布置及衔接。
                [IMAGE]
                8
                """);

        assertThat(cleaned).contains("6.6\n项目工程设计")
                .contains("6.6.1 工程设计应说明布置及衔接。")
                .doesNotContain("English", "Irrigation", "IMAGE", "\n8");
    }

    /** 清洗后的无空格条款编号保留原位，供整节父块归纳使用。 */
    @Test
    void retainsNumberedClausesWithoutSpaces() {
        String cleaned = parser.cleanText("""
                6.7施工组织设计
                6.7.1主要包括施工条件、施工布置和施工进度计划。
                6.7.5施工质量与安全应说明质量控制和施工安全要求。
                """);

        assertThat(cleaned).contains(
                "6.7施工组织设计",
                "6.7.1主要包括施工条件",
                "6.7.5施工质量与安全");
    }
}
