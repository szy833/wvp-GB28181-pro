package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.conf.UserSetting;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InviteInfoRedisIndexTest {
    @Test void namespaceAndPrimaryAreIndependentOfStreamAndSsrc() {
        UserSetting setting=new UserSetting();setting.setServerId("server");
        InviteRepository repository=new InviteRepository(null,setting);
        var invite=InviteInfo.prepare("device",1,InviteSessionType.PLAY,"PLAY:1","node","UDP",60000);
        assertEquals("WVP:INVITE:server:DATA:"+invite.getInviteId(),repository.recordKey(invite.getInviteId()));
        assertFalse(repository.recordKey(invite.getInviteId()).contains(invite.getStream()));
    }
    @Test void eachGenerationHasIndependentPublishedMediaIdentity() {
        var first=InviteInfo.prepare("device",1,InviteSessionType.PLAY,"PLAY:1","node","UDP",60000);
        var second=InviteInfo.prepare("device",1,InviteSessionType.PLAY,"PLAY:1","node","UDP",60000);
        assertEquals(first.getBusinessSlot(),second.getBusinessSlot());
        assertNotEquals(first.getInviteId(),second.getInviteId());
        assertNotEquals(first.getStream(),second.getStream());
    }
}
