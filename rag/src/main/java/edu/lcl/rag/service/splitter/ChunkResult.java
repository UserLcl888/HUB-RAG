package edu.lcl.rag.service.splitter;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChunkResult {

    /**
     * 在整个文档中的顺序索引（0-based）
     */
    private int chunkIndex;

    /**
     * 分块内容
     */
    private String content;

    /**
     * 在源文档中的位置：PDF 是真实页码，DOCX/MD 是节序号，TXT 恒为 1
     */
    private Integer pageNum;

    /**
     * 所在章节标题
     */
    private String sectionTitle;

    /**
     * 估算的 Token 数
     */
    private int estimatedTokens;
}