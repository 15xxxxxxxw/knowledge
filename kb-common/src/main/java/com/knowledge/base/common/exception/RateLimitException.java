package com.knowledge.base.common.exception;

import com.knowledge.base.common.result.ResultCode;

/**
 * 限流异常
 *
 * <p>请求触发限流时抛出，由 {@code GlobalExceptionHandler} 统一转换为
 * {@code Result(code=429, message=...)} 返回给前端。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
public class RateLimitException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * 触发限流的限流 key（用于日志排查）
     */
    private final String limitKey;

    public RateLimitException(String message) {
        super(ResultCode.TOO_MANY_REQUESTS.getCode(), message);
        this.limitKey = null;
    }

    public RateLimitException(String message, String limitKey) {
        super(ResultCode.TOO_MANY_REQUESTS.getCode(), message);
        this.limitKey = limitKey;
    }

    public String getLimitKey() {
        return limitKey;
    }
}
