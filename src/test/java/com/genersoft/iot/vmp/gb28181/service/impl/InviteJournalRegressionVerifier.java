package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.service.*;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;

/** Orchestration fault tests; the journal itself is independently exercised against real Redis. */
public final class InviteJournalRegressionVerifier {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static InviteInfo invite(String ignored) {
        var invite=InviteInfo.prepare("device",1,InviteSessionType.PLAY,"PLAY:1","media","UDP",60000);
        var resource=new SSRCInfo(41000,"0200000001","rtp",invite.getStream());
        resource.setZlmStream("0BEBC201_"+invite.getInviteId());resource.setResourceId(invite.getInviteId());resource.setInviteId(invite.getInviteId());
        invite.setSsrcInfo(resource);invite.setSipSent(true);invite.setSipFinal(true);invite.setSipAccepted(true);invite.setCallId("call-"+invite.getInviteId());
        var dialog=mock(SipTransactionInfo.class);when(dialog.getCallId()).thenReturn(invite.getCallId());invite.setSipDialog(dialog);
        return invite;
    }
    static final class Journal extends InviteInfoCleanupStore {
        InviteInfo record; Job job; boolean leased; int deferrals;
        static UserSetting settings(){var value=new UserSetting();value.setServerId("test");return value;}
        Journal(InviteInfo record){super(null,settings());this.record=record;}
        private Job snapshot(String token) {
            var completed=job==null?Set.<String>of():job.completed();
            String phase=List.of("BYE","SIP","RTP","STATE").stream().filter(s->!completed.contains(s)).findFirst().orElse("DONE");
            return new Job(record.getInviteId(),record,token,phase,true,record.isSkipBye(),record.getCallId(),completed);
        }
        @Override public Preparation prepare(InviteInfo expected,boolean stop,boolean skipBye) {
            if(record==null||record.getLifecycle()==InviteLifecycle.CLOSED)return new Preparation(InviteInfoDeleteResult.ABSENT,null);
            if(!sameOwner(record,expected))return new Preparation(InviteInfoDeleteResult.OWNER_CHANGED,null);
            record.setLifecycle(InviteLifecycle.STOPPING);record.setSkipBye(record.isSkipBye()||skipBye);
            job=snapshot(null);return new Preparation(null,job);
        }
        @Override public Job claim(String id){if(leased||job==null)return null;leased=true;job=snapshot("lease");return job;}
        @Override public Job refresh(Job value){return job;}
        @Override public boolean ownsResources(Job value){return leased;}
        @Override public boolean completeStep(Job value,String step){
            var flags=new HashSet<>(job.completed());flags.add(step);
            job=new Job(job.id(),record,job.token(),job.phase(),true,record.isSkipBye(),record.getCallId(),flags);
            job=snapshot(job.token());return true;
        }
        @Override public boolean finish(Job value){check(job.completed().size()==4,"closed before all steps");record.setLifecycle(InviteLifecycle.CLOSED);job=null;leased=false;return true;}
        @Override public boolean terminateByeLocally(Job value) {
            if (!leased || record.isByeAcknowledged() || !job.completed().containsAll(Set.of("RTP", "STATE"))
                    || record.getByeFirstSentAt() == null || System.currentTimeMillis()-record.getByeFirstSentAt()<32_000) return false;
            record.setByeTerminatedLocallyAt(System.currentTimeMillis());
            record.setByeLocalTerminationReason("BYE_WAIT_EXPIRED");
            return completeStep(value,"BYE");
        }
        @Override public void deferSafely(Job value){leased=false;deferrals++;}
        @Override public List<String> pending(int limit){return job==null?List.of():List.of(record.getInviteId());}
    }
    static final class Fixture {
        final PlayServiceImpl service=new PlayServiceImpl();
        final InviteInfo expected=invite("owner");
        final Journal journal=new Journal(expected);
        final InviteRepository repository=mock(InviteRepository.class);
        final SipInviteSessionManager sessions=mock(SipInviteSessionManager.class);
        final InvitePlayProjectionStore projection=mock(InvitePlayProjectionStore.class);
        final IMediaServerService media=mock(IMediaServerService.class);
        final IReceiveRtpServerService rtp=mock(IReceiveRtpServerService.class);
        final ISIPCommander commander=mock(ISIPCommander.class);
        final IInviteStreamService invites=mock(IInviteStreamService.class);
        final Device device=new Device();final DeviceChannel channel=new DeviceChannel();
        int closes,stateResets;
        Fixture() throws Exception {
            device.setDeviceId("device");channel.setId(1);channel.setDeviceId("channel");
            var devices=mock(IDeviceService.class);when(devices.getDeviceByDeviceId("device")).thenReturn(device);
            var channels=mock(IDeviceChannelService.class);when(channels.getOneForSourceById(1)).thenReturn(channel);
            var node=new MediaServer();node.setId("media");node.setRtpEnable(true);
            when(media.getOne("media")).thenReturn(node);
            when(media.listRtpServer(node)).thenReturn(List.of());
            when(media.probeStream(node,"rtp",expected.getStream())).thenReturn(false);
            when(repository.get(expected.getInviteId())).thenReturn(expected);
            when(repository.beginByeAttempt(eq(expected.getInviteId()),anyString())).thenAnswer(i->{
                long now=System.currentTimeMillis();
                if(expected.getByeSentAt()!=null && now-expected.getByeSentAt()<10_000)
                    return new InviteWriteResult(InviteWriteResult.Code.STATE_CONFLICT,expected);
                if(expected.getByeFirstSentAt()==null)expected.setByeFirstSentAt(now);
                expected.setByeSentAt(now);expected.setByeClockSource("REDIS");expected.setByeOperationId(UUID.randomUUID().toString());
                return new InviteWriteResult(InviteWriteResult.Code.APPLIED,expected);
            });
            when(repository.byeAcknowledged(expected.getInviteId())).thenAnswer(i->{expected.setByeAcknowledged(true);return new InviteWriteResult(InviteWriteResult.Code.APPLIED,expected);});
            when(sessions.removeByCallId(expected.getCallId())).thenReturn(true);
            when(projection.close(expected)).thenAnswer(i->{stateResets++;return true;});
            when(rtp.closeRtpResource(any())).thenAnswer(i->{closes++;return true;});
            ReflectionTestUtils.setField(service,"inviteCleanupStore",journal);
            ReflectionTestUtils.setField(service,"inviteRepository",repository);
            ReflectionTestUtils.setField(service,"sessionManager",sessions);
            ReflectionTestUtils.setField(service,"playProjection",projection);
            ReflectionTestUtils.setField(service,"deviceService",devices);
            ReflectionTestUtils.setField(service,"deviceChannelService",channels);
            ReflectionTestUtils.setField(service,"mediaServerService",media);
            ReflectionTestUtils.setField(service,"receiveRtpServerService",rtp);
            ReflectionTestUtils.setField(service,"cmder",commander);
            ReflectionTestUtils.setField(service,"inviteStreamService",invites);
        }
        void finishPeerBye(){expected.setByeAcknowledged(true);service.retryPendingInviteCleanups(10);}
    }
    public static void main(String[] args)throws Exception {
        var f=new Fixture();
        check(!f.service.stopIfOwner(f.expected),"send is not peer acknowledgement");
        check(f.expected.getLifecycle()==InviteLifecycle.STOPPING,"lost stopping primary");
        check(f.closes==1&&f.stateResets==1,"resource cleanup blocked on BYE");
        verify(f.commander).streamByeCmd(eq(f.device),eq("channel"),eq(f.expected.getSipDialog()),any(SipSubscribe.Event.class));
        f.finishPeerBye();check(f.expected.getLifecycle()==InviteLifecycle.CLOSED,"acknowledged stop did not close");

        var failure=new Fixture();
        when(failure.media.probeStream(any(),anyString(),anyString())).thenReturn(null);
        failure.service.stopAfterDeviceBye(failure.expected);
        check(failure.expected.getLifecycle()==InviteLifecycle.STOPPING,"unknown media treated as absent");
        check(failure.stateResets==1,"independent SQL cleanup skipped");
        when(failure.media.probeStream(any(),anyString(),anyString())).thenReturn(false);
        failure.service.retryPendingInviteCleanups(10);
        check(failure.expected.getLifecycle()==InviteLifecycle.CLOSED,"media recovery did not finish");
        check(failure.service.stopAfterDeviceBye(failure.expected),"duplicate stop is not idempotent");

        var sqlFailure=new Fixture();
        when(sqlFailure.projection.close(any())).thenThrow(new IllegalStateException("injected SQL failure"));
        sqlFailure.service.stopAfterDeviceBye(sqlFailure.expected);
        check(sqlFailure.expected.getLifecycle()==InviteLifecycle.STOPPING,"SQL failure lost pending job");
        check(sqlFailure.closes==1,"SQL failure prevented RTP close");
        doReturn(true).when(sqlFailure.projection).close(any());
        sqlFailure.service.retryPendingInviteCleanups(10);
        check(sqlFailure.expected.getLifecycle()==InviteLifecycle.CLOSED,"SQL recovery failed");
        System.out.println("INVITE_JOURNAL_ASSERTIONS=11");
    }
}
