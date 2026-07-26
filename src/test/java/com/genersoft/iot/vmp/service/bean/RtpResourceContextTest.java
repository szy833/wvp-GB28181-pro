package com.genersoft.iot.vmp.service.bean;

import com.genersoft.iot.vmp.media.event.hook.HookData;
import com.genersoft.iot.vmp.media.event.hook.HookSubscriptionHandle;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.conf.DynamicTask;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RtpResourceContextTest {

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
            final Future<Boolean> failure;
            synchronized (context) {
                failure = executor.submit(() -> context.completeFailure(500, "failed", null));
                assertThrows(TimeoutException.class, () -> failure.get(100, TimeUnit.MILLISECONDS));
                assertEquals(RtpResourceState.WAITING_MEDIA, context.getState());
            }
            assertTrue(failure.get(1, TimeUnit.SECONDS));
            assertEquals(RtpResourceState.FAILED, context.getState());
        } finally {
            executor.shutdownNow();
        }
    }

}
