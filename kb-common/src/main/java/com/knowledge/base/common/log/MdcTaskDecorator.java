package com.knowledge.base.common.log;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步任务 MDC 传递装饰器
 *
 * <p>问题：{@code @Async} / 线程池执行的任务运行在<b>新的线程</b>上，
 * 而 MDC 基于 ThreadLocal，新线程拿不到提交任务时的 traceId，
 * 导致异步日志丢失链路信息（典型现象：{@code [async-task-1]} 线程的日志 traceId 为空）。</p>
 *
 * <p>本装饰器在任务提交时快照当前线程的 MDC，在异步线程执行前恢复、执行后清理，
 * 让异步任务里的日志也能带上同一个 traceId。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 提交任务时（调用线程）快照 MDC
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            }
            try {
                runnable.run();
            } finally {
                // 异步线程会被复用，必须清理，避免污染下一个任务
                MDC.clear();
            }
        };
    }
}
