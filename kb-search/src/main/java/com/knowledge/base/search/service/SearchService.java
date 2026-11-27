package com.knowledge.base.search.service;

import com.knowledge.base.common.result.PageResult;
import com.knowledge.base.search.dto.SearchRequestDTO;
import com.knowledge.base.search.vo.SearchResultVO;
import com.knowledge.base.search.vo.SearchSuggestVO;

import java.util.List;
import java.util.Map;

/**
 * 搜索Service接口
 *
 * @author 苏三
 * @since 1.0.0
 */
public interface SearchService {

    /**
     * 全文搜索
     *
     * @param dto 搜索请求
     * @return 搜索结果
     */
    PageResult<SearchResultVO> search(SearchRequestDTO dto);

    /**
     * 搜索（带文档级权限过滤）
     *
     * @param dto    搜索请求
     * @param userId 当前用户ID；null 表示匿名（只能搜到公开文档）
     * @return 分页结果；权限解析失败时返回空结果（fail-closed）
     */
    PageResult<SearchResultVO> search(SearchRequestDTO dto, Long userId);

    /**
     * 高级搜索（带文档级权限过滤）
     */
    PageResult<SearchResultVO> advancedSearch(SearchRequestDTO dto, Long userId);

    /**
     * 高级搜索
     *
     * @param dto 搜索请求
     * @return 搜索结果
     */
    PageResult<SearchResultVO> advancedSearch(SearchRequestDTO dto);

    /**
     * 搜索建议
     *
     * @param keyword 关键词
     * @param size    返回数量
     * @return 建议列表
     */
    List<SearchSuggestVO> suggest(String keyword, Integer size);

    /**
     * 保存文档索引
     *
     * @param documentId 文档ID
     */
    void indexDocument(Long documentId);

    /**
     * 从文档数据直接索引到ES（kb-document服务内部调用）
     *
     * @param docData 文档数据 Map
     */
    void indexDocumentData(Map<String, Object> docData);

    /**
     * 批量保存文档索引
     *
     * @param documentIds 文档ID列表
     */
    void batchIndexDocuments(List<Long> documentIds);

    /**
     * 删除文档索引
     *
     * @param documentId 文档ID
     */
    void deleteDocument(Long documentId);

    /**
     * 批量删除文档索引
     *
     * @param documentIds 文档ID列表
     */
    void batchDeleteDocuments(List<Long> documentIds);

    /**
     * 重建索引
     */
    void rebuildIndex();
}
