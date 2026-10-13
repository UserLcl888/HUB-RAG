package edu.lcl.rag.service.splitter;

import edu.lcl.rag.service.loader.ParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知分块：优先在标题/段落边界处断开。
 * <p>
 * 适用场景：结构清晰的文档（技术规范、手册等）。
 * 对于流水文字（新闻、小说）效果不如固定窗口。
 * <p>
 * 核心思路：
 * 1. 按标题行切分为若干"节"
 * 2. 节大小 ≤ chunkSize：整节直接作为一块，保留完整语义
 * 3. 节大小 > chunkSize：降级到固定窗口分块器切分
 * （注：节太短不做合并——ChunkService 会统一过滤 < 20 字符的碎片）
 */
@Component("structureAwareSplitter")
@Slf4j
public class StructureAwareChunkSplitter implements ChunkSplitter {

    // 明确的标题：markdown 井号、"第X章/节"、"一、二、"——这类几乎不会和正文混淆
    private static final Pattern EXPLICIT_HEADING = Pattern.compile(
            "^(#{1,3}\\s+|第[一二三四五六七八九十百\\d]+[章节]|[一二三四五六七八九十]+、).{1,60}$"
    );

    // 数字编号行：group(1)=编号(如 "1" / "1.1" / "1.3.1")，group(2)=标题正文
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(\\d+(?:\\.\\d+)*)\\.?\\s+(.{1,40})$"
    );

    // 句子型标点：含这些的多半是正文（有序列表项），不是标题
    private static final Pattern SENTENCE_PUNCT = Pattern.compile("[，。！？；,.!?;]");

    // 单级编号标题的最大字数。章标题通常很短（如"单点登录对接"），
    // 而正文有序列表项往往更长（如"第三方平台应用登录对接完成"），用长度把两者区分开。
    private static final int SINGLE_LEVEL_TITLE_MAX = 8;

    /**
     * 判断一行是否为章节标题。
     * <p>
     * 关键点：文档正文里常有 "1. xxx" "2. xxx" 这样的有序列表项，它们不是标题，
     * 一旦误判会导致这部分正文被当标题丢弃（内容不全）。区分规则：
     * <ul>
     *   <li>多级编号（1.1、1.3.1）几乎一定是小节标题 → 是标题</li>
     *   <li>单级编号（1.、2.）有歧义：只有标题很短且无句子标点时才当章标题，
     *       否则视为正文列表项</li>
     * </ul>
     */
    private boolean isHeading(String line) {
        String s = line.strip();
        if (s.isEmpty()) return false;
        if (EXPLICIT_HEADING.matcher(s).matches()) return true;

        Matcher m = NUMBERED_HEADING.matcher(s);
        if (m.matches()) {
            String number = m.group(1);
            String title = m.group(2).strip();
            boolean multiLevel = number.contains(".");
            if (multiLevel) {
                return true;
            }
            // 单级编号：短标题 + 无句子标点，才认定为章标题；否则是正文列表项
            return title.length() <= SINGLE_LEVEL_TITLE_MAX
                    && !SENTENCE_PUNCT.matcher(title).find();
        }
        return false;
    }

    private final SlidingWindowChunkSplitter slidingSplitter;

    public StructureAwareChunkSplitter(SlidingWindowChunkSplitter slidingSplitter) {
        this.slidingSplitter = slidingSplitter;
    }

    @Override
    public List<ChunkResult> split(ParseResult parseResult, ChunkConfig config) {
        // 先用固定窗口分块，然后按标题边界合并或进一步切分
        List<TextSection> sections = extractSections(parseResult);
        List<ChunkResult> chunks = new ArrayList<>();
        int chunkIndex = 0;

        for (TextSection section : sections) {
            if (section.text().length() <= config.getChunkSize()) {
                // 节大小 ≤ chunkSize：整节直接作为一块，保留完整语义
                // 把标题拼进 content 开头：让标题词进入 Embedding 和全文索引，否则"按标题搜"召回不到正文
                String content = withTitle(section.title(), section.text());
                chunks.add(ChunkResult.builder()
                        .chunkIndex(chunkIndex++)
                        .content(content)
                        .pageNum(section.pageNum())
                        .sectionTitle(section.title())
                        .estimatedTokens(estimateTokens(content))
                        .build());
            } else {
                // 节太大，降级到固定窗口切分
                ParseResult sectionResult = ParseResult.builder()
                        .success(true)
                        .pages(List.of(ParseResult.PageContent.builder()
                                .pageNum(section.pageNum())
                                .text(section.text())
                                .sectionTitle(section.title())
                                .build()))
                        .totalPages(1)
                        .build();

                List<ChunkResult> subChunks = slidingSplitter.split(sectionResult, config);
                for (ChunkResult sub : subChunks) {
                    sub.setChunkIndex(chunkIndex++);
                    if (sub.getSectionTitle() == null) sub.setSectionTitle(section.title());
                    // 每个子块都补上标题，保证拆分后仍能按标题召回
                    sub.setContent(withTitle(section.title(), sub.getContent()));
                    sub.setEstimatedTokens(estimateTokens(sub.getContent()));
                    chunks.add(sub);
                }
            }
        }

        return chunks;
    }

    private List<TextSection> extractSections(ParseResult parseResult) {
        List<TextSection> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentTitle = null;
        int sectionStartPage = 1;   // ★ 当前节的"起始页"——用于引用溯源时给用户翻到对的位置

        for (ParseResult.PageContent page : parseResult.getPages()) {
            // 如果解析层已经识别出 sectionTitle，直接作为当前节的标题（覆盖跨页的延续）
            if (page.getSectionTitle() != null && current.length() == 0) {
                currentTitle = page.getSectionTitle();
                sectionStartPage = page.getPageNum();   // 新节起始页 = 当前页
            }

            String[] lines = page.getText().split("\n");
            for (String line : lines) {
                String stripped = line.strip();
                boolean isHeading = isHeading(stripped);

                if (isHeading) {
                    // 遇到标题——先把已经积累的当前节保存掉，再切换到新标题
                    if (current.length() > 50) {
                        sections.add(new TextSection(currentTitle, current.toString().strip(), sectionStartPage));
                        current = new StringBuilder();
                        sectionStartPage = page.getPageNum();   // ★ 新节起始页 = 标题所在页
                    }
                    // 新节的 title 用这一行；标题行本身不再 append 到 current，避免 Embedding 时重复
                    currentTitle = stripped;
                    continue;
                }

                current.append(line).append("\n");
            }
        }

        if (!current.isEmpty()) {
            sections.add(new TextSection(currentTitle, current.toString().strip(), sectionStartPage));
        }

        return sections;
    }

    /**
     * 把章节标题拼到正文开头。标题为空或正文已以标题开头时不重复拼接。
     */
    private String withTitle(String title, String body) {
        if (title == null || title.isBlank()) return body;
        String t = title.strip();
        if (body != null && body.stripLeading().startsWith(t)) return body;
        return t + "\n" + (body == null ? "" : body);
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        int chinese = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') chinese++;
            else if (!Character.isWhitespace(c)) other++;
        }
        return (int) (chinese * 1.5 + other * 0.3);
    }

    record TextSection(String title, String text, int pageNum) {
    }
}