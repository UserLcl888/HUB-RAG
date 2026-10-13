-- ============================================================
-- V6：中文 bigram 全文检索（不依赖任何扩展）+ 标准代号检索支持
--
-- 背景：PostgreSQL 的 simple 配置不切中文，整串汉字会被当成一个词元，
-- 导致中文查询几乎 0 命中（实测「高标准农田」LIKE 59 命中 vs FTS 只有 2）。
-- 方案：索引侧把汉字展开成 2-gram 词元，查询侧用同样规则生成 tsquery
-- （见 TsQueryBuilder），ASCII/数字 token（GB/T、30600、20kv）原样保留。
--
-- 注意：本迁移只重算 content_tsv_bigram 一列，**不需要重新解析 PDF、不需要重算向量**。
-- 旧的 content_tsv 列与索引保留，便于回滚（配置项 rag.retrieval.lexical 可切换）。
-- ============================================================

-- 1) 切分函数：汉字串 → 2-gram；ASCII/数字 token 原样
CREATE OR REPLACE FUNCTION hbnt_tsv(txt text) RETURNS tsvector AS $$
DECLARE
    token text;
    out_text text := '';
    i int;
    len int;
BEGIN
    FOR token IN
        SELECT coalesce(m[1], m[2])
        FROM regexp_matches(coalesce(txt, ''),
             U&'([\4e00-\9fff]+)|([A-Za-z0-9][A-Za-z0-9./-]*)', 'g') AS m
    LOOP
        IF token ~ U&'^[\4e00-\9fff]+$' THEN
            len := char_length(token);
            IF len = 1 THEN
                out_text := out_text || ' ' || token;
            ELSE
                FOR i IN 1..(len - 1) LOOP
                    out_text := out_text || ' ' || substring(token from i for 2);
                END LOOP;
            END IF;
        ELSE
            out_text := out_text || ' ' || token;
        END IF;
    END LOOP;
    RETURN to_tsvector('simple', out_text);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- 2) 新列 + GIN 索引
ALTER TABLE kb_doc_chunk ADD COLUMN IF NOT EXISTS content_tsv_bigram TSVECTOR;
CREATE INDEX IF NOT EXISTS idx_chunk_tsv_bigram ON kb_doc_chunk USING GIN (content_tsv_bigram);

-- 3) 触发器自动维护
CREATE OR REPLACE FUNCTION update_chunk_tsv_bigram() RETURNS TRIGGER AS $$
BEGIN
    NEW.content_tsv_bigram := hbnt_tsv(NEW.content);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_chunk_tsv_bigram ON kb_doc_chunk;
CREATE TRIGGER trigger_chunk_tsv_bigram
    BEFORE INSERT OR UPDATE OF content
    ON kb_doc_chunk
    FOR EACH ROW
EXECUTE FUNCTION update_chunk_tsv_bigram();

-- 4) 回填现有数据
UPDATE kb_doc_chunk SET content_tsv_bigram = hbnt_tsv(content);
