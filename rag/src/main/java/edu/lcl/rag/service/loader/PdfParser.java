package edu.lcl.rag.service.loader;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
@Slf4j
public class PdfParser implements DocumentParser {

    @Override
    public String supportedType() {
        return "PDF";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            int totalPages = document.getNumberOfPages();
            List<ParseResult.PageContent> pages = new ArrayList<>();

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);  // 按位置排序，处理多栏布局

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                try {
                    stripper.setStartPage(pageNum);
                    stripper.setEndPage(pageNum);
                    String text = stripper.getText(document);
                    text = cleanText(text);

                    if (text.isBlank()) {
                        // 当前版本只索引 PDF 中可提取的纯文本，无法提取文字的页面直接跳过。
                        log.debug("[PDF解析] 第{}页无可提取文本，跳过", pageNum);
                        continue;
                    }

                    // 跳过目录页：目录条目（"标题 …… 页码"）会污染检索，且字面命中标题词，挤掉真正的正文
                    if (isTocPage(text)) {
                        log.info("[PDF解析] 第{}页疑似目录页，跳过索引", pageNum);
                        continue;
                    }

                    pages.add(ParseResult.PageContent.builder()
                            .pageNum(pageNum)
                            .text(text)
                            .build());

                } catch (Exception e) {
                    // 单页解析失败不影响其他页
                    log.warn("[PDF解析] 第{}页解析失败：{}", pageNum, e.getMessage());
                }
            }

            if (pages.isEmpty()) {
                return ParseResult.failure("PDF 解析后无可提取的纯文本内容");
            }

            // 丢掉封面 / 前言 / 引言：只有标准号、发布与修订说明，对问答没有价值却会污染检索
            pages = dropFrontMatter(pages);

            log.info("[PDF解析] 文件={}，总页数={}，有效页数={}", fileName, totalPages, pages.size());

            return ParseResult.builder()
                    .success(true)
                    .pages(pages)
                    .totalPages(totalPages)
                    .title(extractTitle(pages))
                    .build();

        } catch (Exception e) {
            log.error("[PDF解析] 文件={}，解析失败：{}", fileName, e.getMessage(), e);
            return ParseResult.failure("PDF 解析失败：" + e.getMessage());
        }
    }

    // 目录条目行：以"点/空格引导符 + 页码"结尾，如 "整体对接方案 ............ 5"
    private static final Pattern TOC_LINE_PATTERN =
            Pattern.compile(".*[.\\u2026·•\\-\\s]{4,}\\d{1,4}\\s*$");
    /** 仅保留中文文本问答所需内容；英文、英文缩写和常见图片占位标记不参与索引。 */
    private static final Pattern LATIN_TEXT = Pattern.compile("[A-Za-z]+(?:[A-Za-z0-9_./-]*[A-Za-z])?");
    /**
     * 伪汉字：部分 PDF 字体缺少 ToUnicode 映射，ASCII（页眉标准号、英文标题等）会被抽成
     * U+7280~U+72CF 区间的汉字，例如 "犌犅/犜30600—2022" 实际是 "GB/T 30600—2022"、
     * "犠犲犾犳…" 实际是 "Well-facilitated…"。这些字符在 \p{IsHan} 里算汉字，
     * 必须先剔除，否则"必须含汉字"的过滤会全部放行，连页眉都进索引。
     */
    private static final Pattern BROKEN_CMAP_CHAR = Pattern.compile("[\\u7280-\\u72CF]");
    /** 一级章节标题（"1 范围" / "1 总则"），用于定位正文起点、丢掉封面与前言 */
    private static final Pattern FIRST_SECTION = Pattern.compile("^1\\s+[\\p{IsHan}]{2,10}$");
    private static final Pattern IMAGE_PLACEHOLDER = Pattern.compile(
            "(?i)^(?:\\[?(?:image|figure|图|图示|图片|插图|扫描件|签章|印章)\\]?|[-_=·•※]+)$");
    private static final Pattern CHINESE_TEXT = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern SECTION_NUMBER = Pattern.compile("\\d{1,2}(?:\\.\\d{1,3}){1,4}\\.?");

    /**
     * 判断是否为目录页：若页内"目录条目行"占比较高，则认为是目录页。
     * 兼顾显式出现"目录 / 目 录 / Contents"标题的情况。
     */
    private boolean isTocPage(String text) {
        String[] lines = text.split("\n");
        int nonBlank = 0;
        int tocLines = 0;
        boolean hasTocHeading = false;

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            nonBlank++;

            String compact = Normalizer.normalize(line, Normalizer.Form.NFKC).replaceAll("\\s+", "");
            if (compact.equals("目录") || compact.equalsIgnoreCase("contents")
                    || compact.equalsIgnoreCase("tableofcontents")) {
                hasTocHeading = true;
            }
            if (TOC_LINE_PATTERN.matcher(line).matches()) {
                tocLines++;
            }
        }

        if (nonBlank == 0) return false;
        double ratio = (double) tocLines / nonBlank;
        // 有"目录"标题且出现目录条目，或目录条目占比超过 40%，判定为目录页
        return (hasTocHeading && tocLines >= 2) || ratio >= 0.4;
    }

    /**
     * 清理 PDF 解析出的文本：去除多余空白、修复换行
     */
    String cleanText(String raw) {
        if (raw == null) return "";
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n")
                .replaceAll("[ \\t]+", " ")          // 多个空格合并
                .replaceAll("\\n{3,}", "\n\n")        // 多个空行合并为两个
                .strip();

        List<String> retainedLines = new ArrayList<>();
        for (String rawLine : normalized.split("\\R")) {
            String line = rawLine.strip();
            if (line.isBlank() || IMAGE_PLACEHOLDER.matcher(line).matches()) {
                continue;
            }
            // 先剔除"伪汉字"（缺 ToUnicode 的字体把 ASCII 抽成了 U+7280~U+72CF）
            line = BROKEN_CMAP_CHAR.matcher(line).replaceAll(" ");
            // 英文仅作为图标、编号或排版噪声出现时不参与中文问答；中文行中的英文片段也移除。
            line = LATIN_TEXT.matcher(line).replaceAll(" ");
            line = line.replaceAll("[\\s\\u00a0]+", " ").strip();
            // 单独排版的章节编号须保留，供父块将它与下一行中文标题合并。
            if (CHINESE_TEXT.matcher(line).find() || SECTION_NUMBER.matcher(line).matches()) {
                retainedLines.add(line);
            }
        }
        return String.join("\n", retainedLines)
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /**
     * 丢掉正文之前的前置页（封面、前言、引言）。
     * 判据：第一处形如"1 范围""1 总则"的一级章节标题所在页；找不到则不动。
     *
     * @param pages 按页解析结果
     * @return 从正文首页开始的结果（页码仍是原 PDF 页码）
     */
    private List<ParseResult.PageContent> dropFrontMatter(List<ParseResult.PageContent> pages) {
        for (int index = 0; index < pages.size(); index++) {
            for (String raw : pages.get(index).getText().split("\\R")) {
                if (FIRST_SECTION.matcher(raw.strip()).matches()) {
                    return index == 0 ? pages : new ArrayList<>(pages.subList(index, pages.size()));
                }
            }
        }
        return pages;
    }

    /**
     * 取第一页文本的第一行作为文档标题
     */
    private String extractTitle(List<ParseResult.PageContent> pages) {
        if (pages.isEmpty()) return null;
        String firstPageText = pages.get(0).getText();
        String[] lines = firstPageText.split("\n");
        for (String line : lines) {
            line = line.strip();
            if (!line.isBlank() && line.length() < 100) {
                return line;
            }
        }
        return null;
    }
}
