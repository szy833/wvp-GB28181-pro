package com.genersoft.iot.vmp.vmanager;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.gb28181.service.impl.InviteRepository;
import com.genersoft.iot.vmp.media.event.hook.Hook;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/test")
public class TestController {
    private final HookSubscribe subscribe;
    private final InviteRepository invites;
    public TestController(HookSubscribe subscribe, InviteRepository invites) {
        this.subscribe = subscribe;
        this.invites = invites;
    }
    @GetMapping("/hook/list")
    public List<Hook> all() { return subscribe.getAll(); }
    @GetMapping("/redis")
    public List<InviteInfo> redis() {
        return invites.activeIds(200).stream().map(invites::get).filter(Objects::nonNull).toList();
    }
}
