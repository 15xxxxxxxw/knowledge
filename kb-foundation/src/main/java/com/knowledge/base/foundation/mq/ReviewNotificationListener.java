package com.knowledge.base.foundation.mq;

import com.knowledge.base.common.config.SystemConfigCache;
import com.knowledge.base.common.event.ReviewEventDTO;
import com.knowledge.base.common.result.Result;
import com.knowledge.base.foundation.dto.NotificationDTO;
import com.knowledge.base.foundation.feign.UserAuthFeignClient;
import com.knowledge.base.foundation.service.NotificationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核通知消息监听器
 *
 * <p>消费 RabbitMQ 中的审核事件，完成两件事：
 * <ol>
 *   <li>持久化通知到数据库（确保离线用户也能看到）</li>
 *   <li>通过 WebSocket 实时推送给在线用户</li>
 * </ol>
 * </p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Component
public class ReviewNotificationListener {

    @Resource
    private NotificationService notificationService;

    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Resource
    private SimpMessagingTemplate messagingTemplate;

    @Resource
    private UserAuthFeignClient userAuthFeignClient;

    @Resource
    private SystemConfigCache systemConfigCache;

    @RabbitListener(queues = "#{@reviewNotificationQueue.name}")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public void handleReviewEvent(ReviewEventDTO event) {
        if (event == null || event.getEventType() == null) {
            log.warn("收到空的审核事件，忽略");
            return;
        }

        if (event.getEventId() == null) throw new IllegalArgumentException("审核事件缺少 eventId");
        if (jdbcTemplate.update("INSERT IGNORE INTO kb_review_inbox(event_id) VALUES(?)", event.getEventId()) == 0) return;

        String notificationType = "review";
        String link = "/review/documents/" + event.getDocumentId();

        try {
            switch (event.getEventType()) {
                case "SUBMITTED" -> handleSubmitted(event, notificationType, link);
                case "APPROVED"  -> handleApproved(event, notificationType, link);
                case "REJECTED"  -> handleRejected(event, notificationType, link);
                default -> log.warn("未知的审核事件类型：{}", event.getEventType());
            }
        } catch (Exception e) {
            // 异常继续向外抛，回滚 Inbox 与通知并交还消息；不能确认一个未落库的事件。
            log.error("处理审核事件失败：eventType={}, documentId={}, error={}",
                    event.getEventType(), event.getDocumentId(), e.getMessage(), e);
            throw new IllegalStateException("审核通知处理失败，等待重试", e);
        }
    }

    /**
     * 处理文档提交审核事件 → 通知审核员，找不到审核员时兜底通知作者
     */
    private void handleSubmitted(ReviewEventDTO event, String type, String link) {
        log.info("收到文档提交审核事件：documentId={}, title={}, authorId={}",
                event.getDocumentId(), event.getDocumentTitle(), event.getAuthorId());

        // 1. 查询审核员
        List<Long> reviewerIds;
        try {
            reviewerIds = findReviewerUserIds();
        } catch (Exception e) {
            log.error("查询审核员失败：documentId={}, error={}", event.getDocumentId(), e.getMessage());
            throw new IllegalStateException("审核员查询失败", e);
        }

        if (reviewerIds.isEmpty()) {
            // 兜底：没有审核员时通知作者
            log.warn("未找到审核员，通知作者代替：documentId={}, authorId={}",
                    event.getDocumentId(), event.getAuthorId());
            if (event.getAuthorId() != null) {
                String fallbackTitle = "文档已提交审核";
                String fallbackContent = "您的文档《" + event.getDocumentTitle() + "》已提交审核，请等待审核员处理。";
                persistAndPush(event.getAuthorId(), type, fallbackTitle, fallbackContent, link, event.getDocumentId());
            } else {
                log.warn("提交事件缺少作者ID，无法发送兜底通知：documentId={}", event.getDocumentId());
            }
            return;
        }

        // 2. 持久化通知 + WebSocket点对点推送给每个审核员
        String title = "新文档待审核";
        String content = String.format("用户「%s」提交了文档《%s》，请及时审核。",
                event.getAuthorName(), event.getDocumentTitle());
        for (Long reviewerId : reviewerIds) {
            persistAndPush(reviewerId, type, title, content, link, event.getDocumentId());
        }
    }

    /**
     * 处理审核通过事件 → 通知文档作者
     */
    private void handleApproved(ReviewEventDTO event, String type, String link) {
        String title = "文档审核通过";
        String content = String.format("您的文档《%s》已通过审核，正式发布。",
                event.getDocumentTitle());

        if (event.getAuthorId() != null) {
            persistAndPush(event.getAuthorId(), type, title, content, link, event.getDocumentId());
        } else {
            log.warn("审核通过事件缺少作者ID，跳过通知推送：documentId={}", event.getDocumentId());
        }
    }

    /**
     * 处理审核驳回事件 → 通知文档作者
     */
    private void handleRejected(ReviewEventDTO event, String type, String link) {
        String title = "文档审核驳回";
        String reason = event.getReviewComment() != null && !event.getReviewComment().isBlank()
                ? event.getReviewComment() : "未填写原因";
        String content = String.format("您的文档《%s》未通过审核，驳回原因：%s",
                event.getDocumentTitle(), reason);

        if (event.getAuthorId() != null) {
            persistAndPush(event.getAuthorId(), type, title, content, link, event.getDocumentId());
        } else {
            log.warn("审核驳回事件缺少作者ID，跳过通知推送：documentId={}", event.getDocumentId());
        }
    }

    /**
     * 持久化通知到数据库 + WebSocket 点对点推送（如果启用）
     */
    private void persistAndPush(Long userId, String type, String title,
                                 String content, String link, Long documentId) {
        // 1. 持久化到数据库（始终执行）
        NotificationDTO dto = new NotificationDTO();
        dto.setUserId(userId);
        dto.setNotificationType(type);
        dto.setTitle(title);
        dto.setContent(content);
        dto.setLink(link);
        dto.setRelatedType("document");
        dto.setRelatedId(documentId);
        dto.setIsRead(0);
        try {
            notificationService.sendNotification(dto);
            log.info("通知已持久化：userId={}, type={}, documentId={}", userId, type, documentId);
        } catch (Exception e) {
            log.error("通知持久化失败：userId={}, type={}, documentId={}, error={}",
                    userId, type, documentId, e.getMessage(), e);
            throw new IllegalStateException("通知落库失败", e);
        }

        // 2. WebSocket 点对点推送给目标用户（仅当WebSocket启用时）
        if (!isWebSocketEnabled()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationType", type);
        payload.put("title", title);
        payload.put("content", content);
        payload.put("link", link);
        payload.put("documentId", documentId);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                public void afterCommit() {
                    try { messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/queue/notifications", payload); }
                    catch (Exception ex) { log.warn("实时推送失败，请从通知列表读取：userId={}", userId); }
                }
            });
    }

    /**
     * 检查WebSocket推送是否启用
     */
    private boolean isWebSocketEnabled() {
        String value = systemConfigCache.getConfig("websocket.enabled");
        return value == null || "true".equals(value);
    }

    /**
     * 查询所有可能的审核通知接收人ID
     *
     * <p>按优先级依次查询：ROLE_REVIEWER → ROLE_ADMIN → ROLE_SUPER_ADMIN</p>
     */
    private void requireSuccessful(Result<?> result) {
        if (result == null || !com.knowledge.base.common.result.ResultCode.SUCCESS.getCode().equals(result.getCode())) {
            throw new IllegalStateException("查询审核人接口失败");
        }
    }

    private List<Long> findReviewerUserIds() {
        try {
            // 1. 优先查询审核员
            Result<List<Long>> result = userAuthFeignClient.getUserIdsByRole("ROLE_REVIEWER");
            requireSuccessful(result);
            if (result != null && result.getData() != null && !result.getData().isEmpty()) {
                List<Long> ids = result.getData();
                log.info("查询到审核员 {} 人：ids={}", ids.size(), ids);
                return ids;
            }

            // 2. 降级：查询 ROLE_ADMIN 角色
            log.warn("未找到审核员，降级使用管理员角色");
            Result<List<Long>> adminResult = userAuthFeignClient.getUserIdsByRole("ROLE_ADMIN");
            requireSuccessful(adminResult);
            if (adminResult != null && adminResult.getData() != null && !adminResult.getData().isEmpty()) {
                List<Long> adminIds = adminResult.getData();
                log.info("使用管理员 {} 人作为审核通知接收人：ids={}", adminIds.size(), adminIds);
                return adminIds;
            }

            // 3. 最终降级：查询 ROLE_SUPER_ADMIN（admin 账号默认角色）
            log.warn("未找到审核员和管理员，降级使用超级管理员角色");
            Result<List<Long>> superAdminResult = userAuthFeignClient.getUserIdsByRole("ROLE_SUPER_ADMIN");
            requireSuccessful(superAdminResult);
            if (superAdminResult != null && superAdminResult.getData() != null && !superAdminResult.getData().isEmpty()) {
                List<Long> superAdminIds = superAdminResult.getData();
                log.info("使用超级管理员 {} 人作为审核通知接收人：ids={}", superAdminIds.size(), superAdminIds);
                return superAdminIds;
            }

            log.warn("未找到任何审核通知接收人（REVIEWER/ADMIN/SUPER_ADMIN 都为空）");
            return List.of();
        } catch (Exception e) {
            log.error("Feign查询审核员失败：{}", e.getMessage());
            throw new IllegalStateException("审核员查询失败", e);
        }
    }

    /**
     * 构建 WebSocket 广播推送数据
     */
    private Map<String, Object> buildPushPayload(ReviewEventDTO event,
            String type, String title, String content, String link) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", event.getEventType());
        payload.put("notificationType", type);
        payload.put("title", title);
        payload.put("content", content);
        payload.put("link", link);
        payload.put("documentId", event.getDocumentId());
        payload.put("documentTitle", event.getDocumentTitle());
        payload.put("timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : "");
        return payload;
    }
}
