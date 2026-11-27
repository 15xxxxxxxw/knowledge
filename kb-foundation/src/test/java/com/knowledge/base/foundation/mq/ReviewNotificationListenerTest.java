package com.knowledge.base.foundation.mq;
import com.knowledge.base.common.event.ReviewEventDTO;
import com.knowledge.base.foundation.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ReviewNotificationListenerTest {
    @Test void duplicateEventDoesNotCreateAnotherNotification() {
        var listener=new ReviewNotificationListener();var jdbc=mock(JdbcTemplate.class);var notifications=mock(NotificationService.class);
        ReflectionTestUtils.setField(listener,"jdbcTemplate",jdbc);ReflectionTestUtils.setField(listener,"notificationService",notifications);
        when(jdbc.update(anyString(),anyString())).thenReturn(0);
        listener.handleReviewEvent(event());verifyNoInteractions(notifications);
    }
    @Test void persistenceFailureEscapesListenerSoBrokerCanRetry() {
        var listener=new ReviewNotificationListener();var jdbc=mock(JdbcTemplate.class);var notifications=mock(NotificationService.class);
        ReflectionTestUtils.setField(listener,"jdbcTemplate",jdbc);ReflectionTestUtils.setField(listener,"notificationService",notifications);
        when(jdbc.update(anyString(),anyString())).thenReturn(1);
        when(notifications.sendNotification(any(com.knowledge.base.foundation.dto.NotificationDTO.class))).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,()->listener.handleReviewEvent(event()));
    }
    private ReviewEventDTO event() {
        return ReviewEventDTO.builder().eventId("event1").eventType("APPROVED").documentId(1L).authorId(2L).documentTitle("title").build();
    }
}
