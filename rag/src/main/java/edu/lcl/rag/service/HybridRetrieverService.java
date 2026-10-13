package edu.lcl.rag.service;

import edu.lcl.rag.entity.DocChunk;
import edu.lcl.rag.repository.DocChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class HybridRetrieverService {

    private final EmbeddingService embeddingService;
    private final DocChunkRepository chunkRepository;
    private final TsQueryBuilder tsQueryBuilder;
    private final StandardCodeExtractor standardCodeExtractor;

    @Value("${rag.retrieval.vector-top-k:20}")
    private int vectorTopK;

    @Value("${rag.retrieval.fulltext-top-k:20}")
    private int fulltextTopK;

    /** RRF 平滑参数，通常取 60 */
    private static final int RRF_K = 60;

    /**
     * 混合检索：向量检索 + 全文检索，RRF 融合排序。
     *
     * @param question 用户问题（原始，未向量化）
     * @param kbIds    要查询的知识库 ID 列表
     * @param topN     最终返回的 chunk 数量（RRF 排序后取 TopN）
     * @return 按 RRF 分数排序的 chunk 列表（含分数信息）
     */
    public List<ScoredChunk> retrieve(String question, List<Long> kbIds, int topN) {
        return retrieve(question, kbIds, null, topN);
    }

    public List<ScoredChunk> retrieve(String question, List<Long> kbIds, Long docId, int topN) {
        // Step 1：向量检索
        float[] queryEmbedding = embeddingService.embed(question);
        String embeddingStr = toVectorString(queryEmbedding);

        List<DocChunk> vectorResults = kbIds.stream()
                .flatMap(kbId -> (docId == null
                        ? chunkRepository.findByVectorSimilarity(kbId, embeddingStr, vectorTopK)
                        : chunkRepository.findByDocumentVectorSimilarity(kbId, docId, embeddingStr, vectorTopK)).stream())
                .collect(Collectors.toList());

        // Step 2：词法检索（bigram：先严格版，空则宽松版）
        TsQueryBuilder.TsQueries queries = tsQueryBuilder.build(question);
        List<DocChunk> fulltextResults = new ArrayList<>();
        if (!queries.isEmpty()) {
            fulltextResults = lexicalSearch(queries.strict(), kbIds, docId);
            if (fulltextResults.isEmpty() && !queries.loose().isBlank()) {
                fulltextResults = lexicalSearch(queries.loose(), kbIds, docId);
                log.debug("[HybridRetriever] 严格版 0 命中，改用宽松版：{}", queries.loose());
            }
        }

        // Step 2b：标准代号精确检索（纯代号问题只有这一路能召回）
        List<DocChunk> codeResults = codeSearch(question, kbIds, docId);

        log.debug("[HybridRetriever] 向量={}，词法={}，代号={}",
                vectorResults.size(), fulltextResults.size(), codeResults.size());

        // Step 3：RRF 融合
        List<ScoredChunk> merged = rrfMerge(List.of(vectorResults, fulltextResults, codeResults));

        // Step 4：取 TopN
        List<ScoredChunk> topResults = merged.stream()
                .limit(topN)
                .collect(Collectors.toList());

        log.info("[HybridRetriever] RRF 融合后 TopN={}，返回 {} 条", topN, topResults.size());
        return topResults;
    }

    /** 走 content_tsv_bigram 的词法检索 */
    private List<DocChunk> lexicalSearch(String tsQuery, List<Long> kbIds, Long docId) {
        if (tsQuery == null || tsQuery.isBlank()) {
            return List.of();
        }
        return kbIds.stream()
                .flatMap(kbId -> (docId == null
                        ? chunkRepository.findByBigramSearch(kbId, tsQuery, fulltextTopK)
                        : chunkRepository.findByDocumentBigramSearch(kbId, docId, tsQuery, fulltextTopK)).stream())
                .collect(Collectors.toList());
    }

    /**
     * 标准代号精确检索：从问题里抽代号（GB 1023 / GB/T 30600 / 裸数字），归一化后 LIKE 匹配。
     * 这一路的价值是"保召回"——纯代号查询在向量与词法上都几乎没有信号。
     */
    private List<DocChunk> codeSearch(String question, List<Long> kbIds, Long docId) {
        List<String> codes = standardCodeExtractor.extract(question);
        if (codes.isEmpty()) {
            return List.of();
        }
        Map<Long, DocChunk> matched = new LinkedHashMap<>();
        for (String code : codes) {
            String pattern = standardCodeExtractor.likePattern(code);
            kbIds.forEach(kbId -> (docId == null
                    ? chunkRepository.findByStandardCode(kbId, pattern, fulltextTopK)
                    : chunkRepository.findByDocumentStandardCode(kbId, docId, pattern, fulltextTopK))
                    .forEach(chunk -> matched.putIfAbsent(chunk.getId(), chunk)));
        }
        log.debug("[HybridRetriever] 代号检索 codes={} → {} 块", codes, matched.size());
        return new ArrayList<>(matched.values());
    }

    /**
     * RRF 融合多路结果（向量 / 词法 / 标准代号）。
     * 去重：同一个 chunk 出现在多路结果中时，分数累加。
     */
    private List<ScoredChunk> rrfMerge(List<List<DocChunk>> rankedLists) {
        // key: chunkId → RRF 分数
        Map<Long, Double> scoreMap = new LinkedHashMap<>();
        Map<Long, DocChunk> chunkMap = new HashMap<>();

        for (List<DocChunk> ranked : rankedLists) {
            for (int rank = 0; rank < ranked.size(); rank++) {
                DocChunk chunk = ranked.get(rank);
                double rrfScore = 1.0 / (RRF_K + rank + 1);
                scoreMap.merge(chunk.getId(), rrfScore, Double::sum);
                chunkMap.put(chunk.getId(), chunk);
            }
        }

        // 按 RRF 分数降序排列
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> new ScoredChunk(chunkMap.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /** 带分数的 Chunk 包装类 */
    public record ScoredChunk(DocChunk chunk, double score) {
        public Long id() { return chunk.getId(); }
        public String content() { return chunk.getContent(); }
    }
}
