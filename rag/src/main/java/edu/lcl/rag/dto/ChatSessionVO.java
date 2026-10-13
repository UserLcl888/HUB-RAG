package edu.lcl.rag.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史会话展示对象，包含会话固定的检索范围。
 *
 * @author Codex
 * @since 2026-10-13
 */
@Value
@Builder
public class ChatSessionVO {

    String id;
    String title;
    Integer messageCount;
    LocalDateTime lastActiveAt;
    List<Long> kbIds;
    Long docId;
}
