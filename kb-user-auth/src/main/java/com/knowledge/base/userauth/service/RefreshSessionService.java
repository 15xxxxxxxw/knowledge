package com.knowledge.base.userauth.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.knowledge.base.common.exception.BusinessException;
import com.knowledge.base.common.utils.JwtTokenUtil;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;

/** 刷新会话只保存 Token 哈希；Lua 保证轮换与退出串行生效。Redis 故障直接拒绝刷新。 */
@Service
public class RefreshSessionService {
    @Resource private StringRedisTemplate redis;
    @Resource private JwtTokenUtil jwt;
    private String hash(String token) { return DigestUtil.sha256Hex(token); }
    // 使用用户维度 hash tag，保证 Redis Cluster 下脚本涉及的键位于同一个槽。
    private String prefix(Claims c) { return "auth:refresh:{" + c.getSubject() + "}:"; }
    private long ttl(Claims c) { return Math.max(1, (c.getExpiration().getTime()-System.currentTimeMillis())/1000); }
    public void register(String access, String refresh) {
        Claims c=jwt.parseRefreshToken(refresh);
        String family=java.util.UUID.randomUUID().toString();
        redis.execute(new DefaultRedisScript<>("redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[3]); redis.call('SET',KEYS[2],ARGV[2],'EX',ARGV[3]); redis.call('SET',KEYS[3],ARGV[1],'EX',ARGV[3]); return 1",Long.class),
            List.of(prefix(c)+"token:"+hash(refresh),prefix(c)+"family:"+family,prefix(c)+"access:"+hash(access)),family,hash(refresh),String.valueOf(ttl(c)));

    }
    public void rotate(String old, String access, String next) {
        Claims c=jwt.parseRefreshToken(old), n=jwt.parseRefreshToken(next);
        String family=redis.opsForValue().get(prefix(c)+"token:"+hash(old));
        if(family==null) throw new BusinessException("刷新会话已失效，请重新登录");
        Long ok=redis.execute(new DefaultRedisScript<>("if redis.call('GET',KEYS[1]) ~= ARGV[1] then redis.call('DEL',KEYS[1]); return 0 end; redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[4]); redis.call('SET',KEYS[2],ARGV[3],'EX',ARGV[4]); redis.call('SET',KEYS[3],ARGV[3],'EX',ARGV[4]); return 1",Long.class),
            List.of(prefix(c)+"family:"+family,prefix(c)+"token:"+hash(next),prefix(c)+"access:"+hash(access)),hash(old),hash(next),family,String.valueOf(ttl(n)));
        if(!Long.valueOf(1).equals(ok)) throw new BusinessException("刷新令牌重复使用，会话已撤销，请重新登录");

    }
    public void revoke(String token) {
        Claims c=jwt.parseAnyToken(token);
        String kind="refresh".equals(c.get("type"))?"token:":"access:";
        String family=redis.opsForValue().get(prefix(c)+kind+hash(token));
        if(family!=null) redis.delete(prefix(c)+"family:"+family);
    }
}
