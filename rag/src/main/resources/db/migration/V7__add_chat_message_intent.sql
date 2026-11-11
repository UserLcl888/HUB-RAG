-- 一次性迁移；不重复运行，避免覆盖以后产生的真实分类。
-- 用户确认：迁移前的全部消息（含 USER、ASSISTANT 及已软删会话的消息）统一标记为 RAG。
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '15s';

ALTER TABLE public.kb_chat_message
    ADD COLUMN intent VARCHAR(32);

UPDATE public.kb_chat_message
SET intent = 'RAG';

COMMENT ON COLUMN public.kb_chat_message.intent IS
    '回答意图：RAG、BUSINESS_QUERY、OTHER；新用户消息留空，旧消息统一回填 RAG';

-- SQL 校验：出现遗漏即报错，整个事务不提交。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.kb_chat_message
        WHERE intent IS DISTINCT FROM 'RAG'
    ) THEN
        RAISE EXCEPTION 'intent 回填校验失败';
    END IF;
END;
$$;

SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'kb_chat_message'
  AND column_name = 'intent';

SELECT role, intent, COUNT(*) AS message_count
FROM public.kb_chat_message
GROUP BY role, intent
ORDER BY role, intent;

COMMIT;
