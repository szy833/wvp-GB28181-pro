package com.genersoft.iot.vmp.conf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicTaskTest {

    private DynamicTask dynamicTask;
    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        dynamicTask = new DynamicTask();
        scheduler = mock(TaskScheduler.class);
        ReflectionTestUtils.setField(dynamicTask, "taskScheduler", scheduler);
    }

    @Test
    void stopRemovesCompletedOwner() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        when(future.isDone()).thenReturn(true);
        dynamicTask.startDelayWithHandle("key", () -> {}, 1000);

        assertTrue(dynamicTask.stop("key", future));
        assertFalse(dynamicTask.contains("key"));
        assertNull(dynamicTask.get("key"));
    }

    @Test
    void oldOwnerCannotRemoveNewOwner() {
        ScheduledFuture<?> oldFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> newFuture = mock(ScheduledFuture.class);
        doAnswer(invocation -> oldFuture)
                .doAnswer(invocation -> newFuture)
                .when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        dynamicTask.startDelayWithHandle("same", () -> {}, 1);
        dynamicTask.startDelayWithHandle("same", () -> {}, 1);

        assertFalse(dynamicTask.stop("same", oldFuture));
        assertTrue(dynamicTask.contains("same"));
        assertTrue(dynamicTask.stop("same", newFuture));
        assertFalse(dynamicTask.contains("same"));
    }
}
