package edu.lcl.rag.service;

import edu.lcl.rag.dto.RagResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class FullRagPipeline {

    private final EnhancedRetrieverService enhancedRetriever;
    private final RerankerService rerankerService;
    private final ConfidenceFilter confidenceFilter;
    private final ParentContextService parentContextService;
    private final SourceBuilder sourceBuilder;
    private final ChatClient chatClient;
    private final KnowledgeBaseService kbService;

    @Value("${rag.retrieval.child-rerank-top-n:15}")
    private int rerankerTopN;

    public RagResponse query(String question, List<Long> kbIds) {
        long pipelineStart = System.currentTimeMillis();

        kbService.requireKnowledgeBases(kbIds);

        // Step 1：增强检索（混合检索 + HyDE）
        List<HybridRetrieverService.ScoredChunk> candidates =
                enhancedRetriever.retrieveWithHyde(question, kbIds, 40);

        if (candidates.isEmpty()) {
            return RagResponse.notFound();
        }

        // Step 2：Reranker 精排（候选过少或调用失败时不精排，分数保持 RRF 量级）
        var rerankOutcome = rerankerService.rerank(question, candidates, rerankerTopN);

        // Step 3：置信度过滤（阈值只对 Reranker 分数有意义）
        List<HybridRetrieverService.ScoredChunk> filtered =
                confidenceFilter.filter(rerankOutcome.chunks(), rerankOutcome.reranked());

        if (filtered.isEmpty()) {
            return RagResponse.notFound();
        }

        // Step 4：子块命中还原父块；同父块仅保留最高分子块，父块全文参与生成。
        List<ParentContextService.ParentHit> trimmed =
                parentContextService.selectForContext(question, parentContextService.restore(filtered));
        if (trimmed.isEmpty()) {
            return RagResponse.notFound();
        }

        // Step 5：构建带引用编号的 context + 用 RagPromptTemplate 生成 System Prompt
        String context = parentContextService.buildContext(trimmed);
        String answer = generateAnswer(question, context, trimmed.size());// 把编号后的内容 + 问题发给 LLM

        // Step 6：用 SourceBuilder 解析引用标注，关联到文档信息
        List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);

        long elapsed = System.currentTimeMillis() - pipelineStart;
        log.info("[FullRagPipeline] 完成：question={}，elapsed={}ms，sources={}",
                question.substring(0, Math.min(30, question.length())), elapsed, sources.size());

        return RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .latencyMs((int) elapsed)
                .build();
    }

    private String generateAnswer(String question, String context, int chunkCount) {
        String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, chunkCount);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();
    }
}
