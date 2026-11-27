package com.knowledge.base.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.base.common.event.ReviewEventDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 本地事务写 Outbox，后台至少一次投递。发送确认后崩溃仍可能重复，由消费端去重。 */
@Slf4j
@Service
public class ReviewOutboxService {
    @Resource private JdbcTemplate jdbc;
    @Resource private ObjectMapper mapper;
    @Resource private RabbitTemplate rabbit;

    @Transactional(propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public void enqueue(String routingKey, ReviewEventDTO event) throws Exception {
        event.setEventId(UUID.randomUUID().toString());
        jdbc.update("INSERT INTO kb_review_outbox(event_id,routing_key,payload,next_attempt_at) VALUES(?,?,?,NOW())",
            event.getEventId(),routingKey,mapper.writeValueAsString(event));
    }

    @Scheduled(fixedDelayString="${review.outbox.delay-ms:5000}")
    public void deliver() {
        var rows=jdbc.queryForList("SELECT event_id,routing_key,payload FROM kb_review_outbox WHERE sent_at IS NULL AND next_attempt_at<=NOW() ORDER BY next_attempt_at LIMIT 50");
        for(var row:rows) {
            String id=(String)row.get("event_id"), lease=UUID.randomUUID().toString();
            // CAS 租约支持多个服务实例；进程退出后一分钟重新领取。
            if(jdbc.update("UPDATE kb_review_outbox SET lease_token=?,next_attempt_at=DATE_ADD(NOW(),INTERVAL 60 SECOND),attempts=attempts+1 WHERE event_id=? AND sent_at IS NULL AND next_attempt_at<=NOW()",lease,id)!=1) continue;
            try {
                var event=mapper.readValue((String)row.get("payload"),ReviewEventDTO.class);
                var correlation=new CorrelationData(id+"-"+lease);
                rabbit.convertAndSend("kb.notification.exchange",(String)row.get("routing_key"),event,correlation);
                var confirm=correlation.getFuture().get(10,TimeUnit.SECONDS);
                if(!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("消息未获确认或无法路由");
                jdbc.update("UPDATE kb_review_outbox SET sent_at=NOW(),last_error=NULL WHERE event_id=? AND lease_token=?",id,lease);
            } catch(Exception ex) {
                log.warn("审核事件等待重试：eventId={}",id,ex);
                jdbc.update("UPDATE kb_review_outbox SET last_error=?,next_attempt_at=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE event_id=? AND lease_token=?",ex.getClass().getSimpleName(),id,lease);
            }
        }
    }
}
