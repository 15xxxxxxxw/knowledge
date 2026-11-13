package com.knowledge.base.document.service.impl;

import com.knowledge.base.common.config.JwtConfig;
import com.knowledge.base.common.exception.BusinessException;
import com.knowledge.base.common.utils.JwtTokenUtil;
import com.knowledge.base.common.utils.JwtUtil;
import com.knowledge.base.document.dto.DocumentDTO;
import com.knowledge.base.document.dto.DocumentReviewDTO;
import com.knowledge.base.document.entity.Document;
import com.knowledge.base.document.entity.DocumentReview;
import com.knowledge.base.document.entity.DocumentVersion;
import com.knowledge.base.document.mapper.DocumentMapper;
import com.knowledge.base.document.mapper.DocumentReviewMapper;
import com.knowledge.base.document.mapper.DocumentVersionMapper;
import com.knowledge.base.document.service.DocumentContentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 不启动应用、不连接项目配置中的数据库或消息服务。 */
class BackendCorrectnessTest {
    private JwtTokenUtil tokens() {
        JwtTokenUtil util = new JwtTokenUtil();
        JwtConfig config = new JwtConfig();
        config.setSecret("test-only-signing-key-0CHANGE_ME_LOCAL_SECRET_012345678901234567890123456789789-0CHANGE_ME_LOCAL_SECRET_012345678901234567890123456789789-0CHANGE_ME_LOCAL_SECRET_012345678901234567890123456789789");
        ReflectionTestUtils.setField(util, "jwtConfig", config);
        return util;
    }

    @Test void accessAndRefreshCannotBeInterchanged() {
        JwtTokenUtil util = tokens();
        String access = util.generateAccessToken(7L);
        String refresh = util.generateRefreshToken(7L);
        assertNotNull(util.parseToken(access));
        assertNotNull(util.parseRefreshToken(refresh));
        assertNull(util.parseToken(refresh));
        assertNull(util.getUserIdFromToken(refresh));
        assertFalse(util.validateToken(refresh));
        assertNull(util.parseRefreshToken(access));
        assertThrows(RuntimeException.class, () -> util.refreshToken(access));
        assertEquals(7L, util.getUserIdFromToken(util.refreshToken(refresh)));
        assertNotNull(util.parseAnyToken(refresh)); // 退出可解析凭证，但不建立登录身份。
    }

    @Test void expiredAndMalformedTokensAreRejected() {
        JwtTokenUtil util = tokens();
        assertNull(util.parseToken("not-a-jwt"));
        assertNull(util.parseToken(util.generateAccessToken(7L, null, null, -10L)));
    }

    @Test void websocketValidatorRejectsRefreshTokens() {
        JwtTokenUtil util = tokens();
        JwtUtil websocketUtil = new JwtUtil();
        ReflectionTestUtils.setField(websocketUtil, "secret", "test-only-signing-key-0CHANGE_ME_LOCAL_SECRET_012345678901234567890123456789789-0CHANGE_ME_LOCAL_SECRET_012345678901234567890123456789789-0CHANGE_ME_LOCAL_SECRET_012345678901234567890123456789789");
        assertTrue(websocketUtil.validateToken(util.generateAccessToken(7L)));
        assertFalse(websocketUtil.validateToken(util.generateRefreshToken(7L)));
    }

    @Test void losingApprovalStopsBeforeDocumentUpdateAndPublishing() {
        assertLosingReview(false);
    }

    @Test void losingRejectionStopsBeforeDocumentUpdateAndPublishing() {
        assertLosingReview(true);
    }

    private void assertLosingReview(boolean reject) {
        DocumentReviewServiceImpl service = new DocumentReviewServiceImpl();
        DocumentReviewMapper reviews = mock(DocumentReviewMapper.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        ReflectionTestUtils.setField(service, "documentReviewMapper", reviews);
        ReflectionTestUtils.setField(service, "documentMapper", documents);
        DocumentReview review = new DocumentReview();
        review.setId(20L);
        review.setDocumentId(100L);
        when(reviews.selectById(20L)).thenReturn(review);
        when(reviews.update(any(DocumentReview.class), any())).thenReturn(0);
        DocumentReviewDTO dto = new DocumentReviewDTO();
        dto.setReviewId(20L);
        dto.setReviewComment("test rejection");
        assertThrows(BusinessException.class, () -> {
            if (reject) service.rejectReview(dto); else service.approveReview(dto);
        });
        verifyNoInteractions(documents);
    }

    @Test void documentStateConflictAbortsReviewBeforeEvents() {
        DocumentReviewServiceImpl service = new DocumentReviewServiceImpl();
        DocumentReviewMapper reviews = mock(DocumentReviewMapper.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        ReflectionTestUtils.setField(service, "documentReviewMapper", reviews);
        ReflectionTestUtils.setField(service, "documentMapper", documents);
        DocumentReview review = new DocumentReview();
        review.setId(20L); review.setDocumentId(100L);
        Document document = new Document(); document.setId(100L); document.setStatus(0);
        when(reviews.selectById(20L)).thenReturn(review);
        when(reviews.update(any(DocumentReview.class), any())).thenReturn(1);
        when(documents.selectById(100L)).thenReturn(document);
        when(documents.update(any(Document.class), any())).thenReturn(0);
        DocumentReviewDTO dto = new DocumentReviewDTO(); dto.setReviewId(20L);
        assertThrows(BusinessException.class, () -> service.approveReview(dto));
    }

    @Test void failedContentWriteDoesNotUpdateMetadata() {
        DocumentServiceImpl service = new DocumentServiceImpl();
        DocumentMapper documents = mock(DocumentMapper.class);
        DocumentContentService contents = mock(DocumentContentService.class);
        ReflectionTestUtils.setField(service, "documentMapper", documents);
        ReflectionTestUtils.setField(service, "documentContentService", contents);
        Document old = new Document(); old.setId(100L); old.setContentId("mongo-old");
        when(documents.selectById(100L)).thenReturn(old);
        when(contents.updateContent(100L, "new content")).thenThrow(new IllegalStateException("storage down"));
        DocumentDTO dto = new DocumentDTO(); dto.setId(100L); dto.setContent("new content");
        assertThrows(BusinessException.class, () -> service.updateDocument(dto));
        verify(documents, never()).updateById(any(Document.class));
    }

    @Test void snapshotUsesAllocatedSequenceRatherThanRecordCount() {
        DocumentVersionServiceImpl service = new DocumentVersionServiceImpl();
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        ReflectionTestUtils.setField(service, "documentVersionMapper", versions);
        Document document = new Document(); document.setId(100L); document.setTitle("title");
        when(versions.lockDocument(100L)).thenReturn(document);
        when(versions.incrementSequence(100L)).thenReturn(1);
        when(versions.currentSequence(100L)).thenReturn(12);
        when(versions.insert(any(DocumentVersion.class))).thenReturn(1);
        assertTrue(service.createVersion(100L, "edit", 7L));
        ArgumentCaptor<DocumentVersion> captured = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(versions).insert(captured.capture());
        assertEquals(12, captured.getValue().getVersion());
        verify(versions, never()).selectCount(any());
    }
}
