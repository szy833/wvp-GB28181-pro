package com.genersoft.iot.vmp.gb28181.task;

import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class InviteInfoIndexBackfillTaskTest {

    @Test
    void schedulesBackfillWithoutActivatingIndexOnlyReads() {
        IInviteStreamService inviteStreamService = mock(IInviteStreamService.class);
        TaskExecutor taskExecutor = mock(TaskExecutor.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
        when(inviteStreamService.inviteIndexesReady()).thenReturn(false);
        when(inviteStreamService.inviteIndexesBackfilled()).thenReturn(false);
        InviteInfoIndexBackfillTask task = new InviteInfoIndexBackfillTask();
        ReflectionTestUtils.setField(task, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(task, "taskExecutor", taskExecutor);

        task.scheduleBackfill();

        verify(taskExecutor).execute(any(Runnable.class));
        verify(inviteStreamService).rebuildInviteIndexes();
        verify(inviteStreamService, never()).activateInviteIndexes();
    }

    @Test
    void executorRejectionDoesNotPermanentlySetRunningGuard() {
        IInviteStreamService inviteStreamService = mock(IInviteStreamService.class);
        TaskExecutor taskExecutor = mock(TaskExecutor.class);
        when(inviteStreamService.inviteIndexesReady()).thenReturn(false);
        when(inviteStreamService.inviteIndexesBackfilled()).thenReturn(false);
        doThrow(new IllegalStateException("executor rejected"))
                .doAnswer(invocation -> null)
                .when(taskExecutor).execute(any(Runnable.class));
        InviteInfoIndexBackfillTask task = new InviteInfoIndexBackfillTask();
        ReflectionTestUtils.setField(task, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(task, "taskExecutor", taskExecutor);

        assertDoesNotThrow(task::scheduleBackfill);
        task.scheduleBackfill();

        verify(taskExecutor, times(2)).execute(any(Runnable.class));
    }
}
