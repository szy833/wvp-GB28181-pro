package com.genersoft.iot.vmp.service.bean;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.slf4j.LoggerFactory;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.media.event.hook.HookSubscriptionHandle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Runs in a single-carrier JVM so a pinned lifecycle operation cannot hang Surefire. */
public final class RtpVirtualThreadProbe {
    public static void main(String[] args) throws Exception {
        ReentrantLock slowDependency = new ReentrantLock();
        CountDownLatch entered = new CountDownLatch(1);
        Runnable block = () -> {
            entered.countDown();
            slowDependency.lock();
            slowDependency.unlock();
        };
        RtpResourceContext context = new RtpResourceContext("probe", "business", "zlm", null, null);
        if ("close-cleanup".equals(args[0])) context.setTerminalCleanup(block);
        if (args[0].startsWith("late-")) context.close("before late registration");
        HookSubscribe hooks = new HookSubscribe() {
            @Override
            public boolean removeSubscribe(HookSubscriptionHandle handle) {
                block.run();
                return true;
            }
        };
        DynamicTask tasks = new DynamicTask() {
            @Override
            public boolean stop(String key, ScheduledFuture<?> future) {
                block.run();
                return true;
            }
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> future = scheduler.schedule(() -> {}, 1, TimeUnit.HOURS);
        Logger logger = (Logger) LoggerFactory.getLogger(RtpResourceContext.class);
        UnsynchronizedAppenderBase<ILoggingEvent> appender = new UnsynchronizedAppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) { block.run(); }
        };
        // Match OutputStreamAppender's unsynchronized base, without adding a test-owned monitor.
        appender.setContext(logger.getLoggerContext());
        appender.start();
        if (args[0].endsWith("-log")) logger.addAppender(appender);

        Thread.ofVirtual().start(() -> {}).join();
        slowDependency.lock();
        Thread operation = null;
        try {
            operation = Thread.ofVirtual().start(() -> {
                switch (args[0]) {
                    case "close-cleanup" -> context.close("probe");
                    case "transition-log" -> context.transitionTo(RtpResourceState.WAITING_MEDIA);
                    case "waiting-log" -> context.markWaitingMedia(1234, "zlm");
                    case "late-hook" -> context.registerHook(new HookSubscriptionHandle("key", data -> {}), hooks);
                    case "late-task" -> context.registerTask("key", future, tasks);
                    default -> throw new IllegalArgumentException(args[0]);
                }
            });
            if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Dependency not entered");
            CountDownLatch healthyRequest = new CountDownLatch(1);
            Thread.ofVirtual().start(healthyRequest::countDown);
            if (!healthyRequest.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Slow " + args[0] + " starved the virtual thread carrier");
            }
        } finally {
            slowDependency.unlock();
            if (operation != null) operation.join(3000);
            logger.detachAppender(appender);
            appender.stop();
            scheduler.shutdownNow();
        }
    }
}
