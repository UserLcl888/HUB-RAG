-- 部署新版后端前执行；旧消息留空，不依据历史回答措辞伪造状态。
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '15s';

ALTER TABLE public.kb_chat_message
    ADD COLUMN IF NOT EXISTS conversation_state JSONB;

COMMENT ON COLUMN public.kb_chat_message.conversation_state IS
    '后端确定的查询意图、待补参数及已知条件；按会话消息保存，旧消息为空';

COMMIT;
