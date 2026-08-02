package com.genersoft.iot.vmp.conf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** Enables @Async with a bounded executor instead of the unbounded default. */
@Slf4j
@Configuration
@EnableAsync(proxyTargetClass = true)
public class AsyncConfig implements AsyncConfigurer {

    @Value("${async.executor.core-pool-size:8}")
    private int corePoolSize = 8;

    @Value("${async.executor.max-pool-size:32}")
    private int maxPoolSize = 32;

    @Value("${async.executor.queue-capacity:2000}")
    private int queueCapacity = 2000;

    @Value("${async.executor.await-termination-seconds:30}")
    private int awaitTerminationSeconds = 30;

    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        return createExecutor(corePoolSize, maxPoolSize, queueCapacity, "wvp-async-");
    }

    @Bean(name = "sipTaskExecutor")
    public ThreadPoolTaskExecutor sipTaskExecutor() {
        return createExecutor(Math.min(4, corePoolSize), Math.min(16, Math.max(corePoolSize, maxPoolSize)),
                Math.min(1000, Math.max(1, queueCapacity)), "wvp-sip-");
    }

    private ThreadPoolTaskExecutor createExecutor(int core, int max, int queue, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, core));
        executor.setMaxPoolSize(Math.max(Math.max(1, core), max));
        executor.setQueueCapacity(Math.max(1, queue));
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(1, awaitTerminationSeconds));
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return applicationTaskExecutor();
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> log.error("[异步任务] 执行失败：{}", method.toGenericString(), throwable);
    }
}
