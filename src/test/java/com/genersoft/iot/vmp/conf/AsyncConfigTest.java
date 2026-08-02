package com.genersoft.iot.vmp.conf;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Async;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AsyncConfigTest {

    @Test
    void exposesBoundedExecutorAndClassBasedAsyncProxy() {
        AsyncConfig config = new AsyncConfig();

        ThreadPoolTaskExecutor executor = config.applicationTaskExecutor();

        assertNotNull(executor);
        assertEquals(8, executor.getCorePoolSize());
        assertEquals(32, executor.getMaxPoolSize());
        assertEquals(2000, executor.getQueueCapacity());
        assertEquals(EnableAsync.class, AsyncConfig.class.getAnnotation(EnableAsync.class).annotationType());
        assertEquals(true, AsyncConfig.class.getAnnotation(EnableAsync.class).proxyTargetClass());
        executor.shutdown();
    }

    @Test
    void asyncMethodRunsOnConfiguredExecutor() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            TestAsyncService service = context.getBean(TestAsyncService.class);
            String caller = Thread.currentThread().getName();

            CompletableFuture<String> worker = service.threadName();

            org.junit.jupiter.api.Assertions.assertNotEquals(caller, worker.get());
            org.junit.jupiter.api.Assertions.assertTrue(worker.get().startsWith("wvp-async-"));
        }
    }

    @Configuration
    @org.springframework.context.annotation.Import(AsyncConfig.class)
    static class TestConfiguration {
        @Bean
        TestAsyncService testAsyncService() {
            return new TestAsyncService();
        }
    }

    public static class TestAsyncService {
        @Async
        public CompletableFuture<String> threadName() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }
}
