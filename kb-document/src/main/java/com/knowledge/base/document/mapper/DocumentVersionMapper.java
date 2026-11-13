package com.knowledge.base.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledge.base.document.entity.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档版本Mapper接口
 *
 * @author 苏三
 * @since 1.0.0
 */
@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {

    // 在调用者事务中锁定文档，所有创建版本请求按文档串行分配编号。
    @org.apache.ibatis.annotations.Select("SELECT * FROM kb_document WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    com.knowledge.base.document.entity.Document lockDocument(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Insert("INSERT INTO kb_document_version_sequence(document_id, last_version) "
            + "SELECT #{id}, COALESCE(MAX(version), 0) FROM tb_document_version WHERE document_id = #{id} "
            + "ON DUPLICATE KEY UPDATE last_version = GREATEST(last_version, VALUES(last_version))")
    int initializeSequence(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Update("UPDATE kb_document_version_sequence SET last_version = last_version + 1 WHERE document_id = #{id}")
    int incrementSequence(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Select("SELECT last_version FROM kb_document_version_sequence WHERE document_id = #{id} FOR UPDATE")
    Integer currentSequence(@org.apache.ibatis.annotations.Param("id") Long id);


}