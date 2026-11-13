package com.knowledge.base.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowledge.base.search.entity.SearchHistory;
import com.knowledge.base.search.mapper.SearchHistoryMapper;
import com.knowledge.base.search.service.SearchHistoryService;
import com.knowledge.base.search.vo.SearchHistoryVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 搜索历史Service实现
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Service
public class SearchHistoryServiceImpl implements SearchHistoryService {

    @Resource
    private SearchHistoryMapper searchHistoryMapper;

    @Resource
    private ThreadPoolTaskExecutor asyncTaskExecutor;

    /** {@inheritDoc} */
    @Override
    public List<SearchHistoryVO> getSearchHistory(Long userId) {
        LambdaQueryWrapper<SearchHistory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SearchHistory::getUserId, userId);
        queryWrapper.orderByDesc(SearchHistory::getCreatedAt);

        List<SearchHistory> histories = searchHistoryMapper.selectList(queryWrapper);

        // 按关键词分组，取最新的记录
        Map<String, SearchHistory> keywordMap = histories.stream()
            .collect(Collectors.toMap(
                SearchHistory::getKeyword,
                h -> h,
                (h1, h2) -> h1.getCreatedAt().isAfter(h2.getCreatedAt()) ? h1 : h2
            ));

        return keywordMap.values().stream()
            .sorted(Comparator.comparing(SearchHistory::getCreatedAt).reversed())
            .limit(20)
            .map(this::convertToVO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean clearSearchHistory(Long userId) {
        LambdaQueryWrapper<SearchHistory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SearchHistory::getUserId, userId);
        return searchHistoryMapper.delete(queryWrapper) >= 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteSearchHistory(Long historyId, Long userId) {
        LambdaQueryWrapper<SearchHistory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SearchHistory::getId, historyId);
        queryWrapper.eq(SearchHistory::getUserId, userId);
        return searchHistoryMapper.delete(queryWrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getHotSearch() {
        // 获取最近7天的热门搜索
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

        LambdaQueryWrapper<SearchHistory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.ge(SearchHistory::getCreatedAt, sevenDaysAgo);
        queryWrapper.orderByDesc(SearchHistory::getSearchCount);
        // 同一关键词可能存在多行（见 saveSearchHistory 的说明），
        // 先多取一些再按关键词去重，避免热门搜索里出现重复项。
        queryWrapper.last("LIMIT 50");

        return searchHistoryMapper.selectList(queryWrapper).stream()
            .map(SearchHistory::getKeyword)
            .distinct()
            .limit(10)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSearchHistory(Long userId, String keyword) {
        if (userId == null || keyword == null || keyword.isBlank()) {
            return;
        }
        String normalizedKeyword = keyword.trim();
        LocalDateTime now = LocalDateTime.now();

        // ⚠️ 这里不能用 selectOne()。
        // kb_search_history 上并没有 (user_id, keyword) 唯一索引，且该表存在第二个写入方
        // —— Python 搜索服务是按「每次搜索插一条」的日志语义写入的（search_type='all'、
        // search_count=0），所以同一用户同一关键词存在多行是正常现象。
        // selectOne() 匹配到多行会抛 TooManyResultsException，导致搜索历史整体保存失败。
        // 改为取「最新一条」再自增，天然容忍历史脏数据。
        SearchHistory existingHistory = searchHistoryMapper.selectList(
                new LambdaQueryWrapper<SearchHistory>()
                        .eq(SearchHistory::getUserId, userId)
                        .eq(SearchHistory::getKeyword, normalizedKeyword)
                        .orderByDesc(SearchHistory::getCreatedAt)
                        .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);

        if (existingHistory != null) {
            // 更新搜索次数和时间
            int currentCount = existingHistory.getSearchCount() == null
                    ? 0 : existingHistory.getSearchCount();
            existingHistory.setSearchCount(currentCount + 1);
            existingHistory.setCreatedAt(now);
            searchHistoryMapper.updateById(existingHistory);
        } else {
            // 创建新的搜索历史记录
            SearchHistory newHistory = new SearchHistory();
            newHistory.preInsert(); // 雪花算法生成ID
            newHistory.setUserId(userId);
            newHistory.setKeyword(normalizedKeyword);
            newHistory.setSearchCount(1);
            newHistory.setCreatedAt(now);
            searchHistoryMapper.insert(newHistory);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void saveSearchHistoryAsync(Long userId, String keyword) {
        CompletableFuture.runAsync(() -> {
            try {
                saveSearchHistory(userId, keyword);
            } catch (Exception e) {
                log.warn("保存搜索历史失败：keyword={}, error={}", keyword, e.getMessage());
            }
        }, asyncTaskExecutor);
    }

    /**
     * 转换为VO
     *
     * @param history 搜索历史实体
     * @return 搜索历史VO
     */
    private SearchHistoryVO convertToVO(SearchHistory history) {
        SearchHistoryVO vo = new SearchHistoryVO();
        BeanUtils.copyProperties(history, vo);
        return vo;
    }
}
