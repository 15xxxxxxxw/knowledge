-- 上线前在文档库执行；先确认下面查询没有重复，再创建唯一约束。
-- SELECT document_id, version, COUNT(*) FROM tb_document_version GROUP BY document_id, version HAVING COUNT(*) > 1;
CREATE TABLE IF NOT EXISTS kb_document_version_sequence (
    document_id BIGINT NOT NULL PRIMARY KEY,
    last_version INT NOT NULL
) ENGINE=InnoDB;
INSERT INTO kb_document_version_sequence(document_id, last_version)
SELECT document_id, MAX(version) FROM tb_document_version GROUP BY document_id
ON DUPLICATE KEY UPDATE last_version = GREATEST(last_version, VALUES(last_version));
-- 若已有同等唯一约束，不重复执行下一条。请先处理历史重复版本，不自动删除历史。
ALTER TABLE tb_document_version ADD UNIQUE KEY uk_document_version (document_id, version);
