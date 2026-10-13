package com.genersoft.iot.vmp.gb28181.task;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.gb28181.service.impl.InviteRepository;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InviteInfoCleanupTaskTest {
    @Test void confirmedAbsenceTriggersFullOwnerStopOnDedicatedWorker() throws Exception {
        run(false,true);
    }
    @Test void unknownProbeNeverStopsMedia() throws Exception { run(null,false); }
    @Test void presentMediaNeverStopsOwner() throws Exception { run(true,false); }
    private void run(Boolean ready,boolean shouldStop)throws Exception {
        var repository=mock(InviteRepository.class);var media=mock(IMediaServerService.class);var play=mock(IPlayService.class);
        var invite=InviteInfo.prepare("device",1,InviteSessionType.PLAY,"PLAY:1","node","UDP",60000);invite.setLifecycle(InviteLifecycle.ACTIVE);
        when(repository.reconciliationBatch(100)).thenReturn(List.of(invite));
        var node=new MediaServer();node.setId("node");node.setStatus(true);when(media.getOne("node")).thenReturn(node);
        var probed=new CountDownLatch(1);
        when(media.probeStream(node,"rtp",invite.getStream())).thenAnswer(i->{probed.countDown();return ready;});
        var task=new InviteInfoCleanupTask(repository,media,play);
        try {
            task.execute();assertTrue(probed.await(3,TimeUnit.SECONDS));
            if(shouldStop)verify(play,timeout(1000)).stopIfOwner(invite);
        } finally {task.close();}
        if(!shouldStop)verifyNoInteractions(play);
        verify(repository,never()).find(anyString(),anyString());
    }
}
