package com.knowledge.base.document.service.impl;

import com.knowledge.base.document.entity.DocumentShare;
import com.knowledge.base.document.mapper.DocumentShareMapper;
import com.knowledge.base.common.exception.BusinessException;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareQuotaTest {
    @Test void onlyOneConcurrentVisitorCanConsumeLastVisit() throws Exception {
        String url = "jdbc:h2:mem:share_quota;MODE=MySQL";
        try (Connection setup = DriverManager.getConnection(url); var sql = setup.createStatement()) {
            sql.execute("CREATE TABLE kb_document_share (share_id VARCHAR PRIMARY KEY, deleted INT, access_count INT)");
            sql.execute("INSERT INTO kb_document_share VALUES ('link',0,0)");
            var start = new CyclicBarrier(2);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Callable<Boolean> visit = () -> visit(url,start);
                var first = pool.submit(visit);
                var second = pool.submit(visit);
                assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            }
            try (var rows=sql.executeQuery("SELECT access_count FROM kb_document_share")) {
                assertTrue(rows.next()); assertEquals(1,rows.getInt(1));
            }
        }
    }

    private boolean visit(String url,CyclicBarrier start) throws Exception {
        try (Connection connection=DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            DocumentShareMapper mapper=mock(DocumentShareMapper.class);
            // 使用生产 Mapper 的 SQL，两个访问者分别使用独立连接和事务。
            String query=DocumentShareMapper.class.getMethod("lockForAccess",String.class)
                .getAnnotation(Select.class).value()[0].replace("#{shareId}","?");
            when(mapper.lockForAccess("link")).thenAnswer(invocation -> {
                try(var stmt=connection.prepareStatement(query)) {
                    stmt.setString(1,"link");
                    try(var rows=stmt.executeQuery()) {
                        rows.next(); var link=new DocumentShare();link.setDocumentId(8L);
                        link.setStatus(0);link.setDeleted(0);link.setExpireType(1);link.setRequirePassword(0);
                        link.setAccessLimit(1);link.setAccessCount(rows.getInt("access_count"));return link;
                    }
                }
            });
            when(mapper.updateById(any(DocumentShare.class))).thenAnswer(invocation -> {
                DocumentShare link=invocation.getArgument(0);
                try(var stmt=connection.prepareStatement("UPDATE kb_document_share SET access_count=? WHERE share_id='link'")){
                    stmt.setInt(1,link.getAccessCount());return stmt.executeUpdate();
                }
            });
            var service=new DocumentShareServiceImpl(mock(com.knowledge.base.document.service.DocumentService.class));
            ReflectionTestUtils.setField(service,"baseMapper",mapper);
            start.await(5,TimeUnit.SECONDS);
            try { assertEquals(8L,service.accessShare("link",null));connection.commit();return true; }
            catch(BusinessException denied){connection.rollback();assertTrue(denied.getMessage().contains("上限"));return false;}
        }
    }
}
