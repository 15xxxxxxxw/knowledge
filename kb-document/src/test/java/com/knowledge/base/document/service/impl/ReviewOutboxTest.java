package com.knowledge.base.document.service.impl;
import com.knowledge.base.document.service.ReviewOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ReviewOutboxTest {
    @Test void nackKeepsEventPendingAndSchedulesRetry() { verifyDelivery(false); }
    @Test void ackMarksEventSent() { verifyDelivery(true); }
    private void verifyDelivery(boolean ack) {
        var service=new ReviewOutboxService();var jdbc=mock(JdbcTemplate.class);var rabbit=mock(RabbitTemplate.class);
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"rabbit",rabbit);
        ReflectionTestUtils.setField(service,"mapper",new ObjectMapper().registerModule(new JavaTimeModule()));
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("event_id","event1","routing_key","notification.review.local.approved","payload","{\"eventId\":\"event1\",\"eventType\":\"APPROVED\"}")));
        when(jdbc.update(startsWith("UPDATE kb_review_outbox SET lease_token"),anyString(),anyString())).thenReturn(1);
        doAnswer(inv->{CorrelationData c=inv.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(ack,null));return null;})
            .when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class),any(CorrelationData.class));
        service.deliver();
        if(ack) verify(jdbc).update(startsWith("UPDATE kb_review_outbox SET sent_at"),eq("event1"),anyString());
        else {
            verify(jdbc,never()).update(startsWith("UPDATE kb_review_outbox SET sent_at"),anyString(),anyString());
            verify(jdbc).update(startsWith("UPDATE kb_review_outbox SET last_error"),anyString(),eq("event1"),anyString());
        }
    }
}
