package edu.lcl.rag.service;

import edu.lcl.rag.entity.DocParentChunk;
import edu.lcl.rag.repository.DocParentChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 将排序后的检索子块提升为父块，按父块去重并控制生成上下文预算。
 * @author Codex
 * @since 2026-10-12
 */
@Service
@RequiredArgsConstructor
public class ParentContextService {
    private static final Pattern SECTION_REFERENCE = Pattern.compile(
            "(?<![\\d.])(\\d{1,2}(?:\\.\\d{1,3}){1,4})(?![\\d.])");
    private static final Pattern SECTION_TITLE = Pattern.compile(
            "^(\\d{1,2}(?:\\.\\d{1,3}){0,4})\\s+(.+)$");
    /** 父块因超限被切开时的接续标记，与 ParentChunkService 保持一致 */
    private static final String CONTINUE_MARK = ParentChunkService.PARENT_CONTINUE_MARK;
    private static final String RESUME_MARK = ParentChunkService.PARENT_RESUME_MARK;
    /** 标题文字短于此长度不参与"同名小节"匹配，避免"范围""总则"这类词误命中 */
    private static final int MIN_TITLE_LENGTH = 4;

    private final DocParentChunkRepository parentRepository;
    private final ContextTrimmerService tokenCounter;

    @Value("${rag.context.max-tokens:3000}")
    private int maxContextTokens;

    @Value("${rag.retrieval.return-top-n:5}")
    private int maxParents;

    /**
     * 一个父块只保留得分最高的命中子块，用于引用高亮。
     * @param rankedChildren 经过精排/置信度过滤的子块
     * @return 去重后的完整父块及代表性子块
     */
    public List<ParentHit> restore(List<HybridRetrieverService.ScoredChunk> rankedChildren) {
        if (rankedChildren.isEmpty()) {
            return List.of();
        }
        Map<Long, HybridRetrieverService.ScoredChunk> best = new HashMap<>();
        for (var hit : rankedChildren) {
            Long parentId = hit.chunk().getParentId();
            if (parentId != null) {
                best.merge(parentId, hit, (left, right) -> right.score() > left.score() ? right : left);
            }
        }
        if (best.isEmpty()) {
            return List.of();
        }
        Map<Long, DocParentChunk> parents = parentRepository.findAllById(best.keySet()).stream()
                .collect(Collectors.toMap(DocParentChunk::getId, parent -> parent));
        return best.entrySet().stream()
                .filter(entry -> parents.containsKey(entry.getKey()))
                .filter(entry -> entry.getValue().chunk().getDocId().equals(parents.get(entry.getKey()).getDocId())
                        && entry.getValue().chunk().getDocVersion().equals(parents.get(entry.getKey()).getDocVersion()))
                .map(entry -> new ParentHit(parents.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparingDouble((ParentHit hit) -> hit.child().score()).reversed()
                        .thenComparing(hit -> hit.parent().getId()))
                .toList();
    }

    /**
     * 优先限定到问题明确提及的小节，再按上下文预算选取完整父块。
     * 命中 6.7.1 等条款时，选择 6.7 父块，不混入同章其他小节。
     * @param question 用户原始问题
     * @param parents 已按命中子块分数排序的父块
     * @return 选中的父块
     */
    public List<ParentHit> selectForContext(String question, List<ParentHit> parents) {
        Map<String, List<DocParentChunk>> siblingCache = new HashMap<>();
        List<ParentHit> scoped = scopeToQuestion(question, parents);
        // 方案 A：问题里只说标题、没写编号时，把库里"同名小节"的父块全部带上。
        // 例如"施工组织设计"同时对应 3.6 术语条目（定义）与 6.7 正文小节（6.7.1~6.7.6），
        // 检索通常只召回其中一个，缺了正文就会答得过于简单。
        if (!mentionsSectionNumber(question)) {
            scoped = expandSameTitle(question, scoped, siblingCache);
        }
        List<ParentHit> expanded = expandContinuations(scoped, siblingCache);
        List<ParentHit> selected = new ArrayList<>();
        int usedTokens = 0;
        for (ParentHit hit : expanded) {
            if (selected.size() >= maxParents) {
                break;
            }
            int tokens = tokenCounter.countTokens(formatReference(hit));
            if (usedTokens + tokens > maxContextTokens && !selected.isEmpty()) {
                continue;
            }
            selected.add(hit);
            usedTokens += tokens;
        }
        return selected;
    }

    /**
     * 问题里提到了标题、且库里存在**多个同名小节**时，把它们全部补进上下文。
     * <p>
     * 实测郑处附件全库有 5 组同名：施工组织设计（3.6 术语条目 78 字 / 6.7 正文小节 320 字）、
     * 主要内容（6.1 / 7.1）、农田地力提升工程、竣工验收、变压器 台。术语条目给出定义、
     * 正文小节给出条款，缺一个就会"答得过于简单"。
     * <p>
     * 追加的块 continuation=true：没有独立命中位置，只提供全文。
     *
     * @param question      用户问题
     * @param hits          已定界的父块
     * @param siblingCache  同文档父块缓存（与接续块共用，避免重复查库）
     * @return 补全同名小节后的父块列表
     */
    private List<ParentHit> expandSameTitle(String question, List<ParentHit> hits,
                                            Map<String, List<DocParentChunk>> siblingCache) {
        if (hits.isEmpty() || question == null || question.isBlank()) {
            return hits;
        }
        Set<String> matchedTitles = new LinkedHashSet<>();
        for (ParentHit hit : hits) {
            String title = leafTitle(hit.parent().getSectionTitle());
            if (title.length() >= MIN_TITLE_LENGTH && question.contains(title)) {
                matchedTitles.add(title);
            }
        }
        if (matchedTitles.isEmpty()) {
            return hits;
        }
        List<ParentHit> result = new ArrayList<>(hits);
        Set<Long> seen = new LinkedHashSet<>();
        hits.forEach(hit -> seen.add(hit.parent().getId()));
        for (ParentHit hit : hits) {
            if (!matchedTitles.contains(leafTitle(hit.parent().getSectionTitle()))) {
                continue;
            }
            for (DocParentChunk candidate : siblingsOf(hit.parent(), siblingCache)) {
                if (!matchedTitles.contains(leafTitle(candidate.getSectionTitle())) || !seen.add(candidate.getId())) {
                    continue;
                }
                result.add(new ParentHit(candidate, hit.child(), true));
            }
        }
        return result;
    }

    /** 问题里是否写了带点的小节编号（如 6.7、6.7.1）——写了就按编号精确取，不做同名扩展 */
    private boolean mentionsSectionNumber(String question) {
        return question != null && SECTION_REFERENCE.matcher(question).find();
    }

    /** 标题路径最后一段、去掉编号后的标题文字（"… / 3.6 施工组织设计" → "施工组织设计"） */
    private String leafTitle(String path) {
        if (path == null) {
            return "";
        }
        String leaf = path.substring(path.lastIndexOf('/') + 1).strip();
        Matcher matcher = SECTION_TITLE.matcher(leaf);
        return matcher.matches() ? matcher.group(2).strip() : leaf;
    }

    /**
     * 父块因超限被切开时（尾部带【本节未完，续见后一块】或头部带【本节续前】），
     * 按 parent_index 相邻性把接续的那一块一并装入上下文，保证条款一条不丢。
     * 接续块不参与引用编号定位（continuation=true，没有独立命中位置）。
     *
     * @param hits 已按问题定界的父块
     * @param siblingCache 同文档父块缓存
     * @return 补全接续块后的父块列表
     */
    private List<ParentHit> expandContinuations(List<ParentHit> hits,
                                                Map<String, List<DocParentChunk>> siblingCache) {
        if (hits.isEmpty()) {
            return hits;
        }
        List<ParentHit> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (ParentHit hit : hits) {
            if (!seen.add(hit.parent().getId())) {
                continue;
            }
            String content = hit.parent().getContent();
            if (!containsMark(content, CONTINUE_MARK) && !containsMark(content, RESUME_MARK)) {
                result.add(hit);
                continue;
            }
            List<DocParentChunk> siblings = siblingsOf(hit.parent(), siblingCache);
            int position = indexOfParent(siblings, hit.parent().getId());
            if (position < 0) {
                result.add(hit);
                continue;
            }
            // 向前接：保持原文顺序插入到本块之前
            List<ParentHit> before = new ArrayList<>();
            for (int i = position - 1; i >= 0; i--) {
                DocParentChunk previous = siblings.get(i);
                if (!seen.add(previous.getId())) {
                    break;
                }
                before.add(new ParentHit(previous, hit.child(), true));
                if (!containsMark(previous.getContent(), RESUME_MARK)) {
                    break;
                }
            }
            for (int i = before.size() - 1; i >= 0; i--) {
                result.add(before.get(i));
            }
            result.add(hit);
            // 向后接：直到遇到一块不再带"未完"标记为止
            for (int i = position + 1; i < siblings.size(); i++) {
                DocParentChunk next = siblings.get(i);
                if (!seen.add(next.getId())) {
                    break;
                }
                result.add(new ParentHit(next, hit.child(), true));
                if (!containsMark(next.getContent(), CONTINUE_MARK)) {
                    break;
                }
            }
        }
        return result;
    }

    private boolean containsMark(String content, String mark) {
        return content != null && content.contains(mark);
    }

    /** 同一文档同一版本的父块（按 parent_index 升序），用于找相邻接续块 */
    private List<DocParentChunk> siblingsOf(DocParentChunk parent, Map<String, List<DocParentChunk>> cache) {
        String key = parent.getDocId() + "-" + parent.getDocVersion();
        return cache.computeIfAbsent(key, ignored ->
                parentRepository.findByDocIdAndDocVersionOrderByParentIndexAsc(
                        parent.getDocId(), parent.getDocVersion()));
    }

    private int indexOfParent(List<DocParentChunk> parents, Long parentId) {
        for (int i = 0; i < parents.size(); i++) {
            if (parents.get(i).getId().equals(parentId)) {
                return i;
            }
        }
        return -1;
    }

    private List<ParentHit> scopeToQuestion(String question, List<ParentHit> parents) {
        if (parents.isEmpty() || question == null || question.isBlank()) {
            return parents;
        }
        Set<String> requestedSections = new LinkedHashSet<>();
        Matcher references = SECTION_REFERENCE.matcher(question);
        while (references.find()) {
            String number = references.group(1);
            int lastDot = number.lastIndexOf('.');
            // 三级及更深条款所属的二级小节，一级/二级编号保持原样。
            requestedSections.add(lastDot == number.indexOf('.')
                    ? number : number.substring(0, number.indexOf('.', number.indexOf('.') + 1)));
        }
        if (!requestedSections.isEmpty()) {
            return parents.stream()
                    .filter(hit -> requestedSections.contains(sectionNumber(hit.parent().getSectionTitle())))
                    .toList();
        }
        List<ParentHit> matchingTitles = parents.stream()
                .filter(hit -> questionContainsTitle(question, hit.parent().getSectionTitle()))
                .toList();
        return matchingTitles.isEmpty() ? parents : matchingTitles;
    }

    private String sectionNumber(String path) {
        if (path == null) {
            return "";
        }
        String leaf = path.substring(path.lastIndexOf('/') + 1).strip();
        Matcher matcher = SECTION_TITLE.matcher(leaf);
        return matcher.matches() ? matcher.group(1) : "";
    }

    private boolean questionContainsTitle(String question, String path) {
        String title = leafTitle(path);
        return title.length() >= MIN_TITLE_LENGTH && question.contains(title);
    }

    /**
     * 每个参考编号提供完整父块，并在其中标记直接命中的子块。
     * @param hits 已选中的父块
     * @return 提供给模型的编号文本
     */
    public String buildContext(List<ParentHit> hits) {
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < hits.size(); index++) {
            context.append("[参考").append(index + 1).append("] ")
                    .append(formatReference(hits.get(index))).append("\n\n");
        }
        return context.toString().strip();
    }

    private String formatReference(ParentHit hit) {
        StringBuilder reference = new StringBuilder();
        String title = hit.parent().getSectionTitle();
        if (title != null && !title.isBlank()) {
            reference.append("所属章节：").append(title).append("\n");
        }
        if (hit.continuation()) {
            // 接续块没有独立命中位置：直接给全文，避免与命中子块重复输出、白耗 token
            reference.append("本节接续内容（与相邻参考同属一节）：\n")
                    .append(hit.parent().getContent().strip());
            return reference.toString();
        }
        String parentContent = hit.parent().getContent().strip();
        String matchedContent = hit.child().content().strip();
        int matchIndex = parentContent.indexOf(matchedContent);
        reference.append("所属父块的完整内容（命中位置已标记）：\n");
        if (matchIndex >= 0 && !matchedContent.isBlank()) {
            reference.append(parentContent, 0, matchIndex)
                    .append("【命中子块开始】")
                    .append(matchedContent)
                    .append("【命中子块结束】")
                    .append(parentContent.substring(matchIndex + matchedContent.length()));
        } else {
            reference.append(parentContent)
                    .append("\n直接命中的子块：\n")
                    .append(matchedContent);
        }
        return reference.toString();
    }

    /**
     * 一个参考条目：父块全文 + 命中的子块。
     *
     * @param parent       父块
     * @param child        命中的子块（接续块复用来源块的子块）
     * @param continuation 是否为"父块超限被切开后的接续块"（无独立命中位置）
     */
    public record ParentHit(DocParentChunk parent, HybridRetrieverService.ScoredChunk child,
                            boolean continuation) {
        public ParentHit(DocParentChunk parent, HybridRetrieverService.ScoredChunk child) {
            this(parent, child, false);
        }
    }
}
