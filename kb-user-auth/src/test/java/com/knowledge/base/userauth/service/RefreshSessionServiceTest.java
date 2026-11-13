package com.knowledge.base.userauth.service;
import com.knowledge.base.common.config.JwtConfig;
import com.knowledge.base.common.utils.JwtTokenUtil;
import com.knowledge.base.common.exception.BusinessException;
import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class RefreshSessionServiceTest {
    @Test void unknownSessionCannotBeRefreshed() {
        var service=new RefreshSessionService(); var redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        var jwt=tokens();ReflectionTestUtils.setField(service,"redis",redis);ReflectionTestUtils.setField(service,"jwt",jwt);
        assertThrows(BusinessException.class,()->service.rotate(jwt.generateRefreshToken(1L),jwt.generateAccessToken(1L),jwt.generateRefreshToken(1L)));
        verify(redis,never()).execute(any(RedisScript.class),anyList(),any(Object[].class));
    }
    @Test void reusedRefreshIsRejectedWhenAtomicCompareFails() {
        var service=new RefreshSessionService();var redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);when(values.get(anyString())).thenReturn("family");
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(0L);
        var jwt=tokens();ReflectionTestUtils.setField(service,"redis",redis);ReflectionTestUtils.setField(service,"jwt",jwt);
        assertThrows(BusinessException.class,()->service.rotate(jwt.generateRefreshToken(1L),jwt.generateAccessToken(1L),jwt.generateRefreshToken(1L)));
    }
    @Test void logoutAccessRevokesCorrespondingRefreshFamily() {
        var service=new RefreshSessionService();var redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        var jwt=tokens();String access=jwt.generateAccessToken(1L);
        when(values.get("auth:refresh:{1}:access:"+DigestUtil.sha256Hex(access))).thenReturn("family");
        ReflectionTestUtils.setField(service,"redis",redis);ReflectionTestUtils.setField(service,"jwt",jwt);
        service.revoke(access);verify(redis).delete("auth:refresh:{1}:family:family");
    }
    private JwtTokenUtil tokens() {
        var util=new JwtTokenUtil();var config=new JwtConfig();config.setSecret("test-only-signing-key-0123456789-0123456789-0123456789");
        ReflectionTestUtils.setField(util,"jwtConfig",config);return util;
    }
}
