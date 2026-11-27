package com.knowledge.base.search.feign;

import com.knowledge.base.common.acl.DocumentAclProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档级权限数据源实现（kb-search 侧）
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Component
public class DocumentAclProviderImpl implements DocumentAclProvider {

    @Resource
    private DocumentAclFeignClient documentAclFeignClient;

    @Override
    public List<Long> fetchInaccessibleDocumentIds(Long userId) {
        Map<String, Object> response = documentAclFeignClient.getInaccessibleDocumentIds(userId);
        if (response == null) {
            throw new IllegalStateException("kb-document 权限接口返回为空");
        }
        if (!String.valueOf(com.knowledge.base.common.result.ResultCode.SUCCESS.getCode()).equals(String.valueOf(response.get("code")))
                || !(response.get("data") instanceof List<?> rawList)) {
            throw new IllegalStateException("文档权限接口响应异常");
        }
        List<Long> ids = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            ids.add(Long.parseLong(String.valueOf(item)));
        }
        return ids;
    }
}
