package com.genersoft.iot.vmp.service.bean;

import com.genersoft.iot.vmp.media.event.hook.HookData;
import com.genersoft.iot.vmp.media.event.hook.HookSubscriptionHandle;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.conf.DynamicTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CountDownLatch;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RtpResourceContextTest {

    @ParameterizedTest
    @ValueSource(strings = {"close-cleanup", "transition-log", "waiting-log", "late-hook", "late-task"})
    void slowCleanupAndLoggingDoNotStarveVirtualThreadScheduler(String operation) throws Exception {
        Path output = Path.of("target", "rtp-pinning-" + operation + ".log");
        Process process = new ProcessBuilder(
                ProcessHandle.current().info().command().orElseThrow(),
                "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                RtpVirtualThreadProbe.class.getName(), operation)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Probe timed out: " + operation);
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(output); }
                catch (Exception e) { return e.toString(); }
            });
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeCleanupDoesNotHoldContextMonitor() {
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business", "zlm", null, null);
        context.setTerminalCleanup(() -> assertFalse(Thread.holdsLock(context),
                "External cleanup must not pin a Java 21 virtual thread"));
        assertTrue(context.close("test"));
    }

    @Test
    void duplicateCloseWaitsForCleanupAndCleansOnlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch duplicateStarted = new CountDownLatch(1);
        AtomicInteger cleanups = new AtomicInteger();
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business", "zlm", null, () -> {
                    cleanups.incrementAndGet();
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> context.close("first"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<Boolean> duplicate = executor.submit(() -> {
                duplicateStarted.countDown();
                return context.close("duplicate");
            });
            assertTrue(duplicateStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> duplicate.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS));
            assertFalse(duplicate.get(2, TimeUnit.SECONDS));
            assertEquals(1, cleanups.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldAllowOnlyValidLifecycleTransitions() {
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business-stream", "zlm-stream", (code, msg, data) -> {}, () -> {});

        assertTrue(context.transitionTo(RtpResourceState.WAITING_MEDIA));
        assertTrue(context.transitionTo(RtpResourceState.SUCCESS));
        assertFalse(context.transitionTo(RtpResourceState.FAILED));
        assertEquals(RtpResourceState.SUCCESS, context.getState());
    }

    @Test
    void failedContextCannotLaterBecomeSuccessful() {
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business-stream", "zlm-stream", (code, msg, data) -> {}, () -> {});

        assertTrue(context.transitionTo(RtpResourceState.WAITING_MEDIA));
        context.completeFailure(500, "failed", null);
        assertFalse(context.completeSuccess(null));
        assertEquals(RtpResourceState.FAILED, context.getState());
    }

    @Test
    void failureCallbackRunsOnceAfterCleanup() {
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicInteger cleanupCount = new AtomicInteger();
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business-stream", "zlm-stream",
                (code, msg, data) -> {
                    assertEquals(1, cleanupCount.get());
                    callbackCount.incrementAndGet();
                }, cleanupCount::incrementAndGet);

        assertTrue(context.transitionTo(RtpResourceState.WAITING_MEDIA));
        assertTrue(context.completeFailure(500, "failed", new HookData()));
        assertFalse(context.completeFailure(500, "failed-again", null));
        assertEquals(1, callbackCount.get());
        assertEquals(1, cleanupCount.get());
    }

    @Test
    void arrivalBeforeRemoteCreateIsDeliveredAfterWaitingState() {
        AtomicInteger callbackCount = new AtomicInteger();
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business-stream", "zlm-stream",
                (code, msg, data) -> callbackCount.incrementAndGet(), () -> {});

        context.onMediaArrival(new HookData());
        assertTrue(context.markWaitingMedia(1234, "zlm-stream"));
        assertEquals(RtpResourceState.SUCCESS, context.getState());
        assertEquals(1, callbackCount.get());
    }

    @Test
    void closedOwnerIgnoresLateMediaArrival() {
        AtomicInteger callbackCount = new AtomicInteger();
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business-stream", "zlm-stream",
                (code, msg, data) -> callbackCount.incrementAndGet(), () -> {});

        assertTrue(context.transitionTo(RtpResourceState.WAITING_MEDIA));
        assertTrue(context.close("http timeout"));

        context.onMediaArrival(new HookData());

        assertEquals(RtpResourceState.CLOSED, context.getState());
        assertEquals(0, callbackCount.get());
    }

    @Test
    void lateTaskAndHookRegistrationIsImmediatelyRolledBack() {
        DynamicTask dynamicTask = mock(DynamicTask.class);
        HookSubscribe subscribe = mock(HookSubscribe.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        HookSubscribe.Event event = data -> {};
        HookSubscriptionHandle handle = new HookSubscriptionHandle("hook-key", event);
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business-stream", "zlm-stream", (code, msg, data) -> {}, () -> {});

        assertTrue(context.close("race"));
        assertFalse(context.registerTask("task-key", future, dynamicTask));
        assertFalse(context.registerHook(handle, subscribe));
        verify(dynamicTask).stop("task-key", future);
        verify(subscribe).removeSubscribe(handle);
    }

    @Test
    void authKeyMoveRequiresCurrentOwnerAndWritableContext() {
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business-stream", "zlm-stream", (code, msg, data) -> {}, () -> {});
        assertTrue(context.markAuthWritten("old-key", "owner"));
        assertTrue(context.moveAuthKey("old-key", "new-key"));
        assertEquals("new-key", context.getAuthKey());
        assertFalse(context.moveAuthKey("old-key", "other-key"));
        context.close("closed");
        assertFalse(context.moveAuthKey("new-key", "third-key"));
    }

    @Test
    void taskCleanupFailureDoesNotSkipHookCleanup() {
        DynamicTask dynamicTask = mock(DynamicTask.class);
        HookSubscribe subscribe = mock(HookSubscribe.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        HookSubscriptionHandle handle = new HookSubscriptionHandle("hook-key", data -> {});
        doThrow(new IllegalStateException("task cleanup failed"))
                .when(dynamicTask).stop("task-key", future);

        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business-stream", "zlm-stream", (code, msg, data) -> {}, () -> {});
        assertTrue(context.registerTask("task-key", future, dynamicTask));
        assertTrue(context.registerHook(handle, subscribe));

        assertDoesNotThrow(context::cleanupRegisteredResources);
        verify(subscribe).removeSubscribe(handle);
    }

    @Test
    void terminalTransitionWaitsForAuthMigrationLock() throws Exception {
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business-stream", "zlm-stream", (code, msg, data) -> {}, () -> {});
        assertTrue(context.transitionTo(RtpResourceState.WAITING_MEDIA));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.atomic.AtomicReference<Future<Boolean>> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            context.withAuthLock(() -> {
                failure.set(executor.submit(() -> context.completeFailure(500, "failed", null)));
                assertThrows(TimeoutException.class, () -> failure.get().get(100, TimeUnit.MILLISECONDS));
                assertEquals(RtpResourceState.WAITING_MEDIA, context.getState());
            });
            assertTrue(failure.get().get(1, TimeUnit.SECONDS));
            assertEquals(RtpResourceState.FAILED, context.getState());
        } finally {
            executor.shutdownNow();
        }
    }

}
