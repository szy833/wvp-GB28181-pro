package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InviteStreamServiceImplTest {
    private final InviteRepository repository = mock(InviteRepository.class);
    private final IPlayService play = mock(IPlayService.class);
    @SuppressWarnings("unchecked")
    private InviteStreamServiceImpl service() {
        ObjectProvider<IPlayService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(play);
        return new InviteStreamServiceImpl(repository, provider);
    }
    static InviteInfo invite() { return InviteInfo.prepare("device", 1, InviteSessionType.PLAY, "PLAY:1", "node", "UDP", 60000); }

    @Test void waiterRegistrationAndCompletionAreAtomic() throws Exception {
        var service = service(); var invite = invite();
        when(repository.get(invite.getInviteId())).thenReturn(invite);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger completed = new AtomicInteger();
        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i=0;i<100;i++) tasks.add(() -> { service.await(invite, (code,msg,data) -> completed.incrementAndGet()); return null; });
            tasks.add(() -> { service.complete(invite,0,"success",new StreamInfo()); return null; });
            for (Future<Void> future : pool.invokeAll(tasks)) future.get();
            assertEquals(100,completed.get());
            service.complete(invite,1,"duplicate",null);
            assertEquals(100,completed.get());
        } finally { pool.shutdownNow(); }
    }
    @Test void oneThrowingCallbackDoesNotPreventOthers() {
        var service=service(); var invite=invite();
        when(repository.get(invite.getInviteId())).thenReturn(invite);
        AtomicInteger delivered=new AtomicInteger();
        service.await(invite,(code,msg,data)->{throw new IllegalStateException("test");});
        service.await(invite,(code,msg,data)->delivered.incrementAndGet());
        assertDoesNotThrow(()->service.complete(invite,0,"ok",null));
        assertEquals(1,delivered.get());
    }
    @Test void generationsNeverShareWaiters() {
        var service=service(); var old=invite(); var next=invite();
        when(repository.get(old.getInviteId())).thenReturn(old);
        when(repository.get(next.getInviteId())).thenReturn(next);
        AtomicInteger delivered=new AtomicInteger();
        service.await(next,(code,msg,data)->delivered.incrementAndGet());
        service.complete(old,-1,"old stopped",null);
        assertEquals(0,delivered.get());
        service.complete(next,0,"ok",null);
        assertEquals(1,delivered.get());
    }
    @Test void completedOwnerIsDeliveredToLateWaiter() {
        var service=service(); var invite=invite(); invite.setLifecycle(InviteLifecycle.ACTIVE);
        when(repository.get(invite.getInviteId())).thenReturn(invite);
        AtomicInteger delivered=new AtomicInteger();
        service.await(invite,(code,msg,data)->{assertEquals(0,code);delivered.incrementAndGet();});
        assertEquals(1,delivered.get());
    }
    @Test void expiredPreparingUsesFullStopNotMetadataDeletion() {
        var service=service(); var invite=invite();
        when(repository.due(anyInt(),anyLong())).thenReturn(List.of(invite.getInviteId()));
        when(repository.get(invite.getInviteId())).thenReturn(invite);
        service.processExpiry();
        verify(play).stopIfOwner(invite);
    }
    @Test void closedResultUsesTerminalPurgeNotStop() {
        var service=service(); var invite=invite(); invite.setLifecycle(InviteLifecycle.CLOSED);
        when(repository.due(anyInt(),anyLong())).thenReturn(List.of(invite.getInviteId()));
        when(repository.get(invite.getInviteId())).thenReturn(invite);
        service.processExpiry();
        verify(repository).purgeTerminal(eq(invite.getInviteId()),anyLong());
        verifyNoInteractions(play);
    }
    @Test void updateNeverCreatesMissingOwner() {
        var service=service(); var invite=invite();
        when(repository.patch(eq(invite.getInviteId()),eq("metadata"),any())).thenReturn(new InviteWriteResult(InviteWriteResult.Code.NOT_FOUND,null));
        assertThrows(IllegalStateException.class,()->service.updateInviteInfo(invite));
        verify(repository,never()).createAndClaim(any());
    }
    @Test void completedDownloadResultIsRetainedAfterClose() {
        var service=service(); var invite=invite(); invite.setType(InviteSessionType.DOWNLOAD);
        invite.setLifecycle(InviteLifecycle.CLOSED); invite.setStreamInfo(new StreamInfo());invite.getStreamInfo().setProgress(1);
        when(repository.downloadResult(invite)).thenReturn(new InviteWriteResult(InviteWriteResult.Code.APPLIED,invite));
        assertTrue(service.updateInviteInfo(invite).applied());
        verifyNoInteractions(play);
    }
}
