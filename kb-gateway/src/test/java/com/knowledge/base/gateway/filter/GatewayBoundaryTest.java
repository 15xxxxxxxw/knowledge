package com.knowledge.base.gateway.filter;

import com.knowledge.base.gateway.config.GatewayRateLimitProperties;
import com.knowledge.base.common.acl.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class GatewayBoundaryTest {
    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/docs")
            .header("x-user-id","1","2").header("inner-request","1").header("Authorization","Bearer example"));
    }
    @Test void removesBothExternalHeadersAndPreservesCredential() {
        new InternalHeaderStripFilter().filter(exchange(), forwarded -> {
            var headers=forwarded.getRequest().getHeaders();
            assertNull(headers.get("X-User-Id")); assertNull(headers.get("INNER-REQUEST"));
            assertEquals("Bearer example",headers.getFirst("Authorization"));
            return Mono.empty();
        }).block();
    }
    private GatewayRateLimitFilter limiter(Flux<Long> decision, boolean failOpen) {
        var filter=new GatewayRateLimitFilter();
        var properties=new GatewayRateLimitProperties();
        properties.setDefaultCount(1); properties.setFailOpen(failOpen);
        var redis=mock(ReactiveStringRedisTemplate.class);
        doReturn(decision).when(redis).execute(any(RedisScript.class),anyList(),anyList());
        ReflectionTestUtils.setField(filter,"properties",properties);
        ReflectionTestUtils.setField(filter,"reactiveStringRedisTemplate",redis);
        return filter;
    }
    private void checkDispatch(Flux<Long> decision,boolean failOpen,int expectedCalls) {
        var calls=new AtomicInteger(); var exchange=exchange();
        limiter(decision,failOpen).filter(exchange,e -> Mono.fromRunnable(calls::incrementAndGet)).block();
        assertEquals(expectedCalls,calls.get());
        if(expectedCalls==0) assertEquals(429,exchange.getResponse().getStatusCode().value());
    }
    @Test void allowedRunsOnce(){checkDispatch(Flux.just(1L),true,1);}
    @Test void deniedNeverRuns(){checkDispatch(Flux.just(-1L),true,0);}
    @Test void redisFailureUsesConfiguredPolicy(){
        checkDispatch(Flux.error(new IllegalStateException()),true,1);
        checkDispatch(Flux.empty(),false,0);
    }
    @Test void downstreamFailurePropagatesWithoutRetry(){
        var calls=new AtomicInteger();var error=new IllegalStateException();
        assertSame(error,assertThrows(IllegalStateException.class,()->limiter(Flux.just(1L),true)
            .filter(exchange(),e->Mono.defer(()->{calls.incrementAndGet();return Mono.error(error);})).block()));
        assertEquals(1,calls.get());
    }
    @Test void incompleteAclCannotBecomeAnEmptyExclusionList(){
        var acl=new DocumentAclService();
        ObjectProvider<DocumentAclProvider> holder=mock(ObjectProvider.class);
        ReflectionTestUtils.setField(acl,"providerHolder",holder);ReflectionTestUtils.setField(acl,"enabled",true);
        assertFalse(acl.getInaccessible(5L).isResolved());
        var provider=mock(DocumentAclProvider.class);when(holder.getIfAvailable()).thenReturn(provider);
        var redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);when(values.get(anyString())).thenReturn("10,broken");
        ReflectionTestUtils.setField(acl,"stringRedisTemplate",redis);
        when(provider.fetchInaccessibleDocumentIds(5L)).thenReturn(List.of(10L,20L));
        assertEquals(Set.of(10L,20L),acl.getInaccessible(5L).getInaccessibleIds());
        when(provider.fetchInaccessibleDocumentIds(5L)).thenReturn(null);
        assertFalse(acl.getInaccessible(5L).isResolved());
    }
}
