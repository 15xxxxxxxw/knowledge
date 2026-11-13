package com.knowledge.base.document.service.impl;
import com.knowledge.base.document.entity.Document;
import com.knowledge.base.document.entity.mongodb.DocumentContent;
import com.knowledge.base.document.mapper.DocumentMapper;
import com.knowledge.base.document.repository.mongodb.DocumentContentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ContentPointerTest {
    @Test void readerUsesCommittedPointerInsteadOfNewestMongoRecord() {
        var service=new DocumentContentServiceImpl();var docs=mock(DocumentMapper.class);var mongo=mock(DocumentContentRepository.class);
        ReflectionTestUtils.setField(service,"documentMapper",docs);ReflectionTestUtils.setField(service,"documentContentRepository",mongo);
        var doc=new Document();doc.setContentId("committed");when(docs.selectById(1L)).thenReturn(doc);
        var old=DocumentContent.builder().id("committed").content("old body").build();when(mongo.findById("committed")).thenReturn(Optional.of(old));
        assertEquals("old body",service.getContentByDocumentId(1L).getContent());
        verify(mongo,never()).findByDocumentId(1L);
    }

    @Test void rollbackDeletesOnlyNewUncommittedBody() { completeSave(false); }
    @Test void commitKeepsNewBody() { completeSave(true); }
    private void completeSave(boolean commit) {
        var service=spy(new DocumentContentServiceImpl());var docs=mock(DocumentMapper.class);var mongo=mock(DocumentContentRepository.class);
        var markdown=mock(com.knowledge.base.document.service.MarkdownProcessService.class);
        ReflectionTestUtils.setField(service,"documentMapper",docs);ReflectionTestUtils.setField(service,"documentContentRepository",mongo);
        ReflectionTestUtils.setField(service,"markdownProcessService",markdown);
        var doc=new Document();doc.setContentId("old");doc.setStatus(0);when(docs.lockDocument(1L)).thenReturn(doc);
        doReturn("body").when(service).processContent("body");
        when(mongo.save(any(DocumentContent.class))).thenAnswer(inv->{DocumentContent c=inv.getArgument(0);c.setId("new");return c;});
        var synchronization=org.springframework.transaction.support.TransactionSynchronizationManager.class;
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals("new",service.saveContent(1L,"body"));
            for(var callback:org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations())
                callback.afterCompletion(commit?0:1);
            if(commit) verify(mongo,never()).deleteById(anyString());
            else verify(mongo).deleteById("new");
            verify(mongo,never()).deleteById("old");
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
    }
}
