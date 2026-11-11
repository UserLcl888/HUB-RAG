package edu.lcl.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.lcl.rag.dto.RagResponse;
import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.repository.KbDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SourceBuilder {

    private final CitationParser citationParser;
    private final KbDocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    /**
     * 是否把"进了上下文、但模型没标注引用"的文档也补成来源。
     * 模型经常只写一个（来源：[参考1]），于是答案明明综合了知识库里多份资料，界面上却只有一个来源。
     * 打开后：上下文里有几份文档，至少就有几条来源（每份取分数最高的一条）。
     */
    @Value("${rag.source.include-uncited-documents:true}")
    private boolean includeUncitedDocuments;

    /**
     * 从模型回答中解析引用，关联到文档信息，构建来源列表。
     *
     * @param answer 模型回答（含引用标注）
     * @param hits 传入模型的父块及最高分命中子块（顺序对应 [参考N] 编号）
     * @return 被引用的来源列表（前端展示用）
     */
    public List<RagResponse.Source> buildSources(
            String answer,
            List<ParentContextService.ParentHit> hits) {

        // 解析被引用的 chunk 索引（1-based）
        Set<Integer> citedIndices = citationParser.extractCitedIndices(answer);

        // 过滤越界引用：模型有时会给出超出上下文范围的编号（如上下文仅 1 条却标注 [参考3]）
        Set<Integer> validIndices = new LinkedHashSet<>();
        for (int idx : citedIndices) {
            if (idx >= 1 && idx <= hits.size()) {
                validIndices.add(idx);
            } else {
                log.warn("[SourceBuilder] 引用编号越界已忽略：参考{}，上下文共{}条", idx, hits.size());
            }
        }

        // 拒答（"在知识库中未找到…"）时不应给出任何来源：
        // 否则会出现"回答未找到、下面却挂着一个某页来源"的自相矛盾（实测「1+1=?」给出了第 15 页来源）
        if (isRefusal(answer)) {
            log.debug("[SourceBuilder] 回答为未找到/无法回答，不返回来源");
            return List.of();
        }

        // 模型未标注引用、或标注编号全部越界时：默认不再"把所有块都当来源"（那等于挂了一堆与回答无关的出处）。
        // 开启 include-uncited-documents 后改为"每份参与了上下文的文档补一条"，见下方 appendUncitedDocuments。
        citedIndices = validIndices;
        if (citedIndices.isEmpty() && !includeUncitedDocuments) {
            log.debug("[SourceBuilder] 无有效引用标注，不返回来源");
            return List.of();
        }

        // 批量查询文档信息（避免 N+1 查询）
        Set<Long> docIds = hits.stream()
                .map(hit -> hit.parent().getDocId())
                .collect(Collectors.toSet());
        Map<Long, KbDocument> docMap = documentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, d -> d));

        // 1) 模型明确标注的引用，按标注顺序
        List<RagResponse.Source> sources = new ArrayList<>();
        for (int idx : citedIndices) {
            if (idx < 1 || idx > hits.size()) continue;
            sources.add(toSource(hits.get(idx - 1), docMap));
        }

        // 2) 兜底：进了上下文的每份文档，若还没出现在来源里就补一条分数最高的。
        //    这样"一个知识库里多份文档 → 多个参考来源"是确定性行为，不取决于模型标了几个。
        if (includeUncitedDocuments) {
            appendUncitedDocuments(sources, hits, docMap);
        }

        return sources;
    }

    /** 命中父块 + 代表子块 → 前端展示用的来源条目 */
    private RagResponse.Source toSource(ParentContextService.ParentHit hit, Map<Long, KbDocument> docMap) {
        KbDocument doc = docMap.get(hit.parent().getDocId());
        return RagResponse.Source.builder()
                .chunkId(hit.child().id())
                .parentId(hit.parent().getId())
                .docId(hit.parent().getDocId())
                .docName(doc != null ? doc.getFileName() : "未知文档")
                .pageNum(hit.child().chunk().getPageNum())
                .sectionTitle(hit.parent().getSectionTitle())
                .excerpt(hit.child().content().substring(0, Math.min(200, hit.child().content().length())))
                .matchedContent(hit.child().content())
                .parentContent(hit.parent().getContent())
                .score(hit.child().score())
                .build();
    }

    /**
     * 给"参与了上下文、但模型没有标注引用"的文档各补一条来源（取该文档分数最高的命中）。
     * 被引用的文档不动，避免来源列表里同一份文档出现两条。
     */
    private void appendUncitedDocuments(List<RagResponse.Source> sources,
                                        List<ParentContextService.ParentHit> hits,
                                        Map<Long, KbDocument> docMap) {
        Set<Long> covered = sources.stream()
                .map(RagResponse.Source::getDocId)
                .collect(Collectors.toSet());

        Map<Long, ParentContextService.ParentHit> bestPerDoc = new LinkedHashMap<>();
        for (ParentContextService.ParentHit hit : hits) {
            Long docId = hit.parent().getDocId();
            if (docId == null || covered.contains(docId)) {
                continue;
            }
            ParentContextService.ParentHit current = bestPerDoc.get(docId);
            if (current == null || hit.child().score() > current.child().score()) {
                bestPerDoc.put(docId, hit);
            }
        }

        for (ParentContextService.ParentHit hit : bestPerDoc.values()) {
            sources.add(toSource(hit, docMap));
            log.debug("[SourceBuilder] 补齐未被标注的文档来源：docId={}", hit.parent().getDocId());
        }
    }

    /**
     * 判定回答是否为"未找到 / 无法回答"这类拒答。
     * 只在**整段回答很短**时才算拒答，避免把"6.2 未提及，但 6.3 规定…"这种正常回答误判掉。
     *
     * @param answer 模型回答
     * @return true 表示这是一段拒答，不应附带来源
     */
    private boolean isRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        String compact = answer.replaceAll("\\s+", "");
        if (compact.length() > 80) {
            return false;
        }
        return compact.contains("未找到") || compact.contains("没有找到") || compact.contains("未提及")
                || compact.contains("不包含") || compact.contains("无法回答") || compact.contains("无法确定")
                || compact.contains("没有相关") || compact.contains("未收录");
    }

    /**
     * 序列化来源列表为 JSON 字符串，用于存储到 chat_message.sources 字段。
     */
    public String sourcesToJson(List<RagResponse.Source> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            log.error("[SourceBuilder] 来源序列化失败：{}", e.getMessage());
            return "[]";
        }
    }
}
