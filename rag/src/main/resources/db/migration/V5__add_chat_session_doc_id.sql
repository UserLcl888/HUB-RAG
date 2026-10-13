-- 历史会话固定检索文档。
-- 旧会话允许为空，应用首次继续提问时会自动补齐最近上传的已解析文档。
ALTER TABLE kb_chat_session
    ADD COLUMN IF NOT EXISTS doc_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_session_doc_id
    ON kb_chat_session (doc_id)
    WHERE is_deleted = FALSE;
