package edu.lcl.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.lcl.rag.dto.RagResponse;
import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.repository.KbDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        // 模型未标注引用、或标注编号全部越界时，把所有 chunk 都列为来源
        if (validIndices.isEmpty()) {
            log.debug("[SourceBuilder] 无有效引用标注，使用所有 chunk 作为来源");
            for (int i = 1; i <= hits.size(); i++) validIndices.add(i);
        }
        citedIndices = validIndices;

        // 批量查询文档信息（避免 N+1 查询）
        Set<Long> docIds = hits.stream()
                .map(hit -> hit.parent().getDocId())
                .collect(Collectors.toSet());
        Map<Long, KbDocument> docMap = documentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, d -> d));

        // 组装来源信息
        List<RagResponse.Source> sources = new ArrayList<>();
        for (int idx : citedIndices) {
            if (idx < 1 || idx > hits.size()) continue;

            ParentContextService.ParentHit hit = hits.get(idx - 1);
            KbDocument doc = docMap.get(hit.parent().getDocId());

            sources.add(RagResponse.Source.builder()
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
                    .build());
        }

        return sources;
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
