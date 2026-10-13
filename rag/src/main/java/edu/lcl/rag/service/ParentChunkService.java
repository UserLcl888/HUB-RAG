package edu.lcl.rag.service;

import edu.lcl.rag.service.loader.ParseResult;
import edu.lcl.rag.service.splitter.ChunkResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据文档标题划分父块，仅对超长章节按段落拆分用于检索的子块。
 *
 * @author Codex
 * @since 2026-10-12
 */
@Service
public class ParentChunkService {

    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(\\d{1,2}(?:\\.\\d{1,3}){0,4})\\.?(?:(?:\\s+|(?=[\\p{IsHan}（(]))(.+))?$");
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十百\\d]+[章节条]|[一二三四五六七八九十]+、)\\s*(.{1,80})$");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.{1,80})$");
    private static final Pattern APPENDIX_HEADING = Pattern.compile(
            "^(附录\\s*[A-Z])(?:\\s+(.{1,80}))?$", Pattern.CASE_INSENSITIVE);
    /** 行尾出现这些符号 → 该行是被排版换行截断的正文（顿号/逗号/左括号都表示"未完待续"），一定不是标题 */
    private static final Pattern END_PUNCTUATION = Pattern.compile("[，,、。；;：:（(？！?!]$");
    /** 标题正文里不应出现句末标点：条款正文通常是一句完整的话 */
    private static final Pattern SENTENCE_PUNCTUATION = Pattern.compile("[。；;？！?!]");
    /** 编号标题的正文长度上限："3.2 年假、病假与事假"=9 通过，跨行的条款正文远长于此 */
    private static final int NUMBERED_TITLE_MAX_LENGTH = 20;
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;])");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^(?:[-—–]\\s*)?\\d{1,3}(?:\\s*[-—–])?$");
    private static final Pattern SECTION_PARENT = Pattern.compile("^\\d{1,2}\\.\\d{1,3}\\s+.+$");
    /**
     * 行首三级及更深条款编号（6.3.1 / 6.3.10 / 6.7.1）：**子块的切分锚点**。
     * 兼容两种排版——编号后跟空格，或直接跟汉字（"6.7.1主要包括施工条件…"）。
     */
    private static final Pattern CLAUSE_START =
            Pattern.compile("^\\d{1,2}(?:\\.\\d{1,3}){2,4}(?=\\s|[\\p{IsHan}（(])");
    /**
     * 子块"太短"阈值（字符）。实测两份标准文本条款长度中位数 53~65 字、&lt;40 字占 21%~24%，
     * 取 40 只合并真正的碎片，不会动到中位数条款。
     */
    private static final int MIN_CHILD_CHARACTERS = 40;
    /** 短条款合并后的软上限：实测 400 与 600 结果一致（合并只发生在短块之间），取 400 更安全 */
    private static final int MAX_MERGED_CHILD_CHARACTERS = 400;
    /**
   /** 父块因超限被切开时的接续标记；会进入模型上下文，务必简短 */
    public static final String PARENT_CONTINUE_MARK = "【本节未完，续见后一块】";
    /** 父块因超限被切开时的续前标记 */
    public static final String PARENT_RESUME_MARK = "【本节续前】";

    private final int maxChildCharacters;
    private final int maxParentCharacters;

    /**
     * @param maxChildCharacters 单条向量输入的字符上限
     * @param maxParentCharacters 章节父块的字符上限，超过时优先按自然段拆分
     */
    public ParentChunkService(@Value("${rag.chunk.max-child-characters:6000}") int maxChildCharacters,
                              @Value("${rag.chunk.max-parent-characters:8500}") int maxParentCharacters) {
        if (maxChildCharacters < 100 || maxParentCharacters < maxChildCharacters) {
            throw new IllegalArgumentException("父块上限必须大于或等于子块上限，且均不得小于 100 字符");
        }
        this.maxChildCharacters = maxChildCharacters;
        this.maxParentCharacters = maxParentCharacters;
    }

    /**
     * 根据正文标题形成章节父块；内容可以跨页，保留每行的真实 PDF 页码。
     *
     * @param parsedDocument 已解析文档
     * @return 依正文顺序排列的章节
     */
    public List<ParentBlock> split(ParseResult parsedDocument) {
        if (parsedDocument == null || !parsedDocument.isSuccess() || parsedDocument.getPages() == null) {
            return List.of();
        }
        List<ParentBlock> parents = new ArrayList<>();
        List<PageLine> current = new ArrayList<>();
        NavigableMap<Integer, String> titlePath = new TreeMap<>();
        String currentTitle = null;
        Heading pendingHeading = null;
        String pendingNumber = null;
        int pendingPage = 0;
        for (ParseResult.PageContent page : parsedDocument.getPages()) {
            if (page.getText() == null || page.getText().isBlank()) {
                continue;
            }
            List<String> pageLines = new ArrayList<>();
            for (String raw : page.getText().split("\\R")) {
                String line = raw.strip();
                if (!line.isEmpty()) {
                    pageLines.add(line);
                }
            }
            for (int index = 0; index < pageLines.size(); index++) {
                String line = pageLines.get(index);
                if (isPageFurniture(line, index + 1, index == pageLines.size() - 1)) {
                    continue;
                }
                Heading heading = detectHeading(line);
                if (pendingNumber != null) {
                    if (pendingHeading.level() > 2) {
                        // 独占一行的三级及以下编号是父小节正文，保留编号并继续处理当前行。
                        current.add(new PageLine(pendingPage, pendingNumber + "\n"));
                        pendingNumber = null;
                    } else if (heading == null && isHeadingText(line)) {
                        heading = detectHeading(pendingNumber + " " + line);
                        if (heading != null) {
                            addParent(parents, current, currentTitle);
                            current = new ArrayList<>();
                            titlePath.tailMap(heading.level()).clear();
                            titlePath.put(heading.level(), heading.title());
                            currentTitle = String.join(" / ", titlePath.values());
                            current.add(new PageLine(pendingPage, pendingNumber + "\n"));
                            current.add(new PageLine(page.getPageNum(), line + "\n"));
                            pendingNumber = null;
                            continue;
                        }
                    }
                    addParent(parents, current, currentTitle);
                    current = new ArrayList<>();
                    titlePath.tailMap(pendingHeading.level()).clear();
                    titlePath.put(pendingHeading.level(), pendingNumber);
                    currentTitle = String.join(" / ", titlePath.values());
                    current.add(new PageLine(pendingPage, pendingNumber + "\n"));
                    pendingNumber = null;
                }
                if (heading != null && heading.level() > 2) {
                    // 条款属于最近的二级小节：命中 6.7.1 时要保留 6.7 下全部条款。
                    heading = null;
                }
                if (heading != null && heading.title().isEmpty()) {
                    pendingHeading = heading;
                    pendingNumber = line;
                    pendingPage = page.getPageNum();
                    continue;
                }
                if (heading != null) {
                    addParent(parents, current, currentTitle);
                    current = new ArrayList<>();
                    int level = heading.level();
                    titlePath.tailMap(level).clear();
                    titlePath.put(level, heading.title());
                    currentTitle = String.join(" / ", titlePath.values());
                }
                current.add(new PageLine(page.getPageNum(), line + "\n"));
            }
        }
        if (pendingNumber != null) {
            addParent(parents, current, currentTitle);
            current = new ArrayList<>();
            titlePath.tailMap(pendingHeading.level()).clear();
            titlePath.put(pendingHeading.level(), pendingNumber);
            currentTitle = String.join(" / ", titlePath.values());
            current.add(new PageLine(pendingPage, pendingNumber + "\n"));
        }
        addParent(parents, current, currentTitle);
        markContinuation(parents);
        return parents;
    }

    /**
     * 子块 = 以**三级条款起点**切分：6.3.1 一块、6.3.2 一块……
     * <p>
     * 规则：
     * <ol>
     *   <li>行首三级及更深编号处切开；条款内的 ——— 分项与 (1)(2) 子项留在同一块；</li>
     *   <li>块内容不足 {@link #MIN_CHILD_CHARACTERS} 字时与**下一条款**合并
     *       （合并后不超过 {@link #MAX_MERGED_CHILD_CHARACTERS} 字）。合并只在同一个父块内部进行，
     *       而父块就是二级小节，因此不可能把 6.1.5 与 6.2.1 并到一起；</li>
     *   <li>小节最后一条太短时没有"下一条"可并，改与**前一条**合并；</li>
     *   <li>无编号区（附录、表格）整段累积，超过上限再按"页 → 换行 → 句末"回退切分。</li>
     * </ol>
     * 跨页条款不拆分，页码取条款首行所在页。
     *
     * @param parent 章节父块
     * @return 供检索的子块
     */
    public List<ChunkResult> children(ParentBlock parent) {
        List<ChunkResult> children = new ArrayList<>();
        List<List<PageLine>> segments = new ArrayList<>();
        List<PageLine> current = new ArrayList<>();
        for (PageLine line : parent.lines()) {
            if (CLAUSE_START.matcher(line.text().strip()).find() && !current.isEmpty()) {
                segments.add(current);
                current = new ArrayList<>();
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            segments.add(current);
        }
        for (List<PageLine> block : mergeShortSegments(segments)) {
            emitChildBlock(children, parent.sectionTitle(), block);
        }
        return children;
    }

    /** 短块先向后合并；末尾短块再回退与前一块合并（始终在本父块内，受软上限约束） */
    private List<List<PageLine>> mergeShortSegments(List<List<PageLine>> segments) {
        List<List<PageLine>> merged = new ArrayList<>();
        int index = 0;
        while (index < segments.size()) {
            List<PageLine> block = new ArrayList<>(segments.get(index));
            while (blockLength(block) < MIN_CHILD_CHARACTERS
                    && index + 1 < segments.size()
                    && blockLength(block) + blockLength(segments.get(index + 1)) <= MAX_MERGED_CHILD_CHARACTERS) {
                index++;
                block.addAll(segments.get(index));
            }
            merged.add(block);
            index++;
        }
        if (merged.size() > 1) {
            int last = merged.size() - 1;
            if (blockLength(merged.get(last)) < MIN_CHILD_CHARACTERS
                    && blockLength(merged.get(last - 1)) + blockLength(merged.get(last)) <= MAX_MERGED_CHILD_CHARACTERS) {
                merged.get(last - 1).addAll(merged.remove(last));
            }
        }
        return merged;
    }

    private int blockLength(List<PageLine> lines) {
        int total = 0;
        for (PageLine line : lines) {
            total += line.text().length();
        }
        return total;
    }

    /**
     * 输出一个子块：不超上限就整块输出（跨页也保留为一块，页码取首行页）；
     * 超上限（超长条款或无编号区）再按"页 → 换行 → 句末标点"回退切分。
     */
    private void emitChildBlock(List<ChunkResult> children, String title, List<PageLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        if (blockLength(lines) <= maxChildCharacters) {
            StringBuilder text = new StringBuilder();
            lines.forEach(line -> text.append(line.text()));
            addChild(children, title, lines.get(0).pageNum(), text.toString().strip());
            return;
        }
        int pageNum = -1;
        StringBuilder text = new StringBuilder();
        for (PageLine line : lines) {
            if (pageNum != line.pageNum() && text.length() > 0) {
                addPageChildren(children, title, pageNum, text.toString());
                text = new StringBuilder();
            }
            pageNum = line.pageNum();
            text.append(line.text());
        }
        if (text.length() > 0) {
            addPageChildren(children, title, pageNum, text.toString());
        }
    }

    private void addPageChildren(List<ChunkResult> children, String title, int pageNum, String text) {
        String remaining = text.strip();
        while (!remaining.isEmpty()) {
            if (remaining.length() <= maxChildCharacters) {
                addChild(children, title, pageNum, remaining);
                return;
            }
            int boundary = remaining.lastIndexOf('\n', maxChildCharacters);
            if (boundary < maxChildCharacters / 3) {
                Matcher matcher = SENTENCE_END.matcher(remaining.substring(0, maxChildCharacters));
                boundary = -1;
                while (matcher.find()) {
                    boundary = matcher.end();
                }
            }
            if (boundary < maxChildCharacters / 3) {
                boundary = maxChildCharacters;
            }
            addChild(children, title, pageNum, remaining.substring(0, boundary).strip());
            remaining = remaining.substring(boundary).strip();
        }
    }

    private void addChild(List<ChunkResult> children, String title, int pageNum, String content) {
        if (!content.isEmpty()) {
            children.add(ChunkResult.builder()
                    .chunkIndex(children.size())
                    .content(content)
                    .pageNum(pageNum)
                    .sectionTitle(title)
                    .estimatedTokens(content.length())
                    .build());
        }
    }

    private Heading detectHeading(String raw) {
        String line = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
        if (line.contains("…") || line.matches(".*\\.{3,}\\s*\\d+\\s*$")) {
            return null;
        }
        Matcher appendix = APPENDIX_HEADING.matcher(line);
        if (appendix.matches()) {
            String title = appendix.group(2);
            return new Heading(1, appendix.group(1) + (title == null ? "" : " " + title));
        }
        Matcher markdown = MARKDOWN_HEADING.matcher(line);
        if (markdown.matches()) {
            return new Heading(markdown.group(1).length(), markdown.group(2));
        }
        Matcher chapter = CHAPTER_HEADING.matcher(line);
        if (chapter.matches()) {
            return new Heading(1, chapter.group(1) + " " + chapter.group(2));
        }
        Matcher numbered = NUMBERED_HEADING.matcher(line);
        if (!numbered.matches()) {
            return null;
        }
        String title = numbered.group(2) == null ? "" : numbered.group(2).strip();
        if (!title.isEmpty() && !isHeadingText(title)) {
            return null;
        }
        String number = numbered.group(1);
        int level = (int) number.chars().filter(character -> character == '.').count() + 1;
        return new Heading(level, title.isEmpty() ? "" : number + " " + title);
    }

    /**
     * 判断编号后面的文字是否像"标题正文"。
     * <p>
     * 关键约束：条款正文（如"6.5.1 项目区建设规划布局应包括指导思想及基本原则、目标任务…"）
     * 会被 PDF 排版换行切成多行，而换行处往往没有句末标点。若只看行尾标点，
     * 这些条款会被误判为标题，把"一节"切成"一条一块"——实测 6.5 建设规划布局被切成 4 块，
     * 其中一块只剩标题文本，导致"这一节讲了什么"永远答不全。
     * <p>
     * 因此这里再加两道闸门（长度上限 + 禁止句末标点），同时保留
     * "3.2 年假、病假与事假"这类含顿号的短标题。
     */
    private boolean isHeadingText(String text) {
        return !text.isEmpty() && text.length() <= NUMBERED_TITLE_MAX_LENGTH
                && !END_PUNCTUATION.matcher(text).find()
                && !SENTENCE_PUNCTUATION.matcher(text).find()
                && (Character.isLetter(text.codePointAt(0)) || text.startsWith("("));
    }

    /**
     * 页眉/页脚等排版噪声，不参与正文与标题判定。
     *
     * @param lineIndex 该行在本页非空行中的序号（从 1 开始）
     * @param lastLine  是否为本页最后一行
     */
    private boolean isPageFurniture(String line, int lineIndex, boolean lastLine) {
        String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC);
        boolean pageNumber = PAGE_NUMBER.matcher(normalized.strip()).matches();
        if (pageNumber && (lineIndex <= 2 || lastLine)) {
            return true;
        }
        // 页脚页码若不剔除，会被当成"编号标题"，凭空造出一个父块并把后续正文吞进去
        // （实测 6.3 项目区概况、6.6 项目工程设计 被页脚数字切开）
        return lineIndex <= 2 && normalized.matches(
                "(?i)^(GB|NY|SL|DB|TD)(?:/T)?\\s*[\\dX]+\\s*[-—–]\\s*[\\dX]{4}$");
    }

    private void addParent(List<ParentBlock> parents, List<PageLine> lines, String title) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder content = new StringBuilder();
        lines.forEach(line -> content.append(line.text()));
        if (content.toString().isBlank()) {
            return;
        }
        // 二级小节优先整块保留：只要不超过 maxParentCharacters，下面这段循环自然不会切它。
        // 一旦超限（实测极少：GB/T 30600 最大小节 2351 字）就切开，并由 markContinuation()
        // 补【本节未完，续见后一块】/【本节续前】标记——内容一条不丢，检索侧据此把两块一起装入上下文。
        List<PageLine> part = new ArrayList<>();
        int length = 0;
        for (PageLine line : lines) {
            if (length + line.text().length() > maxParentCharacters && !part.isEmpty()) {
                addBoundedParent(parents, part, title);
                part = new ArrayList<>();
                length = 0;
            }
            part.add(line);
            length += line.text().length();
        }
        if (!part.isEmpty()) {
            addBoundedParent(parents, part, title);
        }
    }

    private void addBoundedParent(List<ParentBlock> parents, List<PageLine> lines, String title) {
        List<PageLine> part = new ArrayList<>();
        int length = 0;
        for (PageLine line : lines) {
            String remaining = line.text();
            while (!remaining.isEmpty()) {
                if (length == maxParentCharacters) {
                    storeParent(parents, part, title);
                    part = new ArrayList<>();
                    length = 0;
                }
                int end = Math.min(remaining.length(), maxParentCharacters - length);
                if (end < remaining.length()) {
                    int sentence = -1;
                    Matcher matcher = SENTENCE_END.matcher(remaining.substring(0, end));
                    while (matcher.find()) {
                        sentence = matcher.end();
                    }
                    if (sentence >= end / 2) {
                        end = sentence;
                    }
                }
                part.add(new PageLine(line.pageNum(), remaining.substring(0, end)));
                length += end;
                remaining = remaining.substring(end);
            }
        }
        storeParent(parents, part, title);
    }

    private void storeParent(List<ParentBlock> parents, List<PageLine> lines, String title) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder content = new StringBuilder();
        lines.forEach(line -> content.append(line.text()));
        if (!content.toString().isBlank()) {
            parents.add(new ParentBlock(content.toString().strip(), title, lines.get(0).pageNum(), List.copyOf(lines)));
        }
    }

    /**
     * 父块因超限被切开时打接续标记：同一二级小节的连续块，
     * 前块尾部加【本节未完，续见后一块】、后块头部加【本节续前】。
     * <p>
     * 只有"二级小节"会被标记；无题块（title=null）与章节块即使相邻同名也不标记，避免误伤。
     * 标记会让内容比你设定的上限多出十几个字符，属于有意为之（换取"不丢内容 + 可续接"）。
     */
    private void markContinuation(List<ParentBlock> parents) {
        int index = 0;
        while (index < parents.size()) {
            int end = index;
            while (end + 1 < parents.size() && sameSection(parents.get(end), parents.get(end + 1))) {
                end++;
            }
            if (end > index && isSectionParent(parents.get(index).sectionTitle())) {
                for (int i = index; i <= end; i++) {
                    ParentBlock block = parents.get(i);
                    String content = block.content();
                    if (i > index) {
                        content = PARENT_RESUME_MARK + "\n" + content;
                    }
                    if (i < end) {
                        content = content + "\n" + PARENT_CONTINUE_MARK;
                    }
                    parents.set(i, new ParentBlock(content, block.sectionTitle(), block.pageNum(), block.lines()));
                }
            }
            index = end + 1;
        }
    }

    private boolean sameSection(ParentBlock left, ParentBlock right) {
        return left.sectionTitle() != null && left.sectionTitle().equals(right.sectionTitle());
    }

    private boolean isSectionParent(String title) {
        if (title == null) {
            return false;
        }
        String leaf = title.substring(title.lastIndexOf('/') + 1).strip();
        return SECTION_PARENT.matcher(leaf).matches();
    }

    private record Heading(int level, String title) {}

    /** 完整章节及其原始文字位置。 */
    public record ParentBlock(String content, String sectionTitle, Integer pageNum,
                              List<PageLine> lines) {}

    /** PDF 中的一行及其页号。 */
    public record PageLine(int pageNum, String text) {}
}
