-- 分别在 document 和 foundation 使用的数据库执行对应建表；先停写、备份再部署。
-- document 数据库：历史待审核任务需要先退回草稿重新提交，不能盲目绑定当前正文。
ALTER TABLE tb_document_review ADD COLUMN content_revision VARCHAR(64) NULL COMMENT '提交审核时的正文ID';
CREATE TABLE kb_review_outbox (
 event_id VARCHAR(64) PRIMARY KEY, routing_key VARCHAR(255) NOT NULL, payload LONGTEXT NOT NULL,
 attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME NOT NULL,
 lease_token VARCHAR(64), sent_at DATETIME NULL, last_error VARCHAR(255),
 KEY idx_outbox_pending(sent_at,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- foundation 数据库：去重记录与通知写入同一个本地事务。
CREATE TABLE kb_review_inbox (
 event_id VARCHAR(64) PRIMARY KEY, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
