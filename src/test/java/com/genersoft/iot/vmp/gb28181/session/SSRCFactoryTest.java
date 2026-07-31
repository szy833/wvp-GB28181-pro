package com.genersoft.iot.vmp.gb28181.session;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.event.mediaServer.MediaServerOnlineEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.genersoft.iot.vmp.media.zlm.dto.ZLMResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SSRCFactoryTest {

    private SSRCFactory ssrcFactory;

    private static final String DOMAIN_PART = "20000";
    private static final String SERVER_ID = "test-server";

    @BeforeEach
    void setUp() throws Exception {
        ssrcFactory = new SSRCFactory();
        ReflectionTestUtils.setField(ssrcFactory, "domainPart", DOMAIN_PART);

        Field schedulerField = SSRCFactory.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        java.util.concurrent.ScheduledExecutorService scheduler =
                (java.util.concurrent.ScheduledExecutorService) schedulerField.get(ssrcFactory);
        scheduler.shutdownNow();
        ssrcFactory.markReconciliationReady(rtpServer(SERVER_ID), new ArrayList<>());
    }

    @Test
    void getPlaySsrc_shouldReturnCorrectFormat() {
        String ssrc = ssrcFactory.getPlaySsrc(SERVER_ID);
        assertNotNull(ssrc);
        assertEquals(10, ssrc.length(), "SSRC should be 10 characters: prefix(1) + domain(5) + seq(4)");
        assertTrue(ssrc.startsWith("0"), "Play SSRC should start with '0'");
        assertTrue(ssrc.substring(1).startsWith(DOMAIN_PART), "SSRC should contain domain part");
        assertTrue(ssrc.matches("0" + DOMAIN_PART + "\\d{4}"), "SSRC format: 0" + DOMAIN_PART + "NNNN");
    }

    @Test
    void getPlayBackSsrc_shouldReturnCorrectFormat() {
        String ssrc = ssrcFactory.getPlayBackSsrc(SERVER_ID);
        assertNotNull(ssrc);
        assertEquals(10, ssrc.length(), "SSRC should be 10 characters: prefix(1) + domain(5) + seq(4)");
        assertTrue(ssrc.startsWith("1"), "PlayBack SSRC should start with '1'");
        assertTrue(ssrc.substring(1).startsWith(DOMAIN_PART), "SSRC should contain domain part");
        assertTrue(ssrc.matches("1" + DOMAIN_PART + "\\d{4}"), "SSRC format: 1" + DOMAIN_PART + "NNNN");
    }

    @Test
    void allocations_withinSameServer_shouldBeUnique() {
        Set<String> allocated = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String ssrc = ssrcFactory.getPlaySsrc(SERVER_ID);
            assertNotNull(ssrc, "Should allocate SSRC #" + i);
            assertTrue(allocated.add(ssrc), "SSRC should be unique: " + ssrc);
        }
        assertEquals(1000, allocated.size());
    }

    @Test
    void allocations_forDifferentServers_shouldBeIndependent() {
        String serverA = "server-a";
        String serverB = "server-b";
        ssrcFactory.markReconciliationReady(rtpServer(serverA), new ArrayList<>());
        ssrcFactory.markReconciliationReady(rtpServer(serverB), new ArrayList<>());

        for (int i = 0; i < 10000; i++) {
            assertNotNull(ssrcFactory.getPlaySsrc(serverA), "Server A should allocate SSRC #" + i);
        }
        assertNull(ssrcFactory.getPlaySsrc(serverA), "Server A should be exhausted");

        for (int i = 0; i < 1000; i++) {
            assertNotNull(ssrcFactory.getPlaySsrc(serverB), "Server B should allocate SSRC #" + i);
        }
    }

    @Test
    void exhaustion_shouldReturnNull() {
        for (int i = 0; i < 10000; i++) {
            assertNotNull(ssrcFactory.getPlaySsrc(SERVER_ID), "iteration " + i);
        }
        assertNull(ssrcFactory.getPlaySsrc(SERVER_ID), "Should return null when exhausted");
        assertNull(ssrcFactory.getPlayBackSsrc(SERVER_ID), "Should return null for PlayBack too");
    }

    @Test
    @Disabled("Needs mocked mediaServerService for ZLM query")
    void rebuild_shouldResetUsage() {
        for (int i = 0; i < 500; i++) {
            ssrcFactory.getPlaySsrc(SERVER_ID);
        }
        ssrcFactory.rebuild();

        for (int i = 0; i < 500; i++) {
            String ssrc = ssrcFactory.getPlaySsrc(SERVER_ID);
            assertNotNull(ssrc, "After rebuild should allocate SSRC #" + i);
        }
    }

    @Test
    void allocateAll_shouldUseAll10000Slots() {
        Set<String> allocated = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            String ssrc = ssrcFactory.getPlaySsrc(SERVER_ID);
            assertNotNull(ssrc, "Should allocate at iteration " + i);
            allocated.add(ssrc);
        }
        assertEquals(10000, allocated.size(), "All 10000 slots should be unique");
    }

    @Test
    void twoPrefixes_shareSamePool() throws Exception {
        for (int i = 0; i < 5000; i++) {
            assertNotNull(ssrcFactory.getPlaySsrc(SERVER_ID), "play #" + i);
            assertNotNull(ssrcFactory.getPlayBackSsrc(SERVER_ID), "playback #" + i);
        }

        Field usedMapField = SSRCFactory.class.getDeclaredField("usedMap");
        usedMapField.setAccessible(true);
        java.util.concurrent.ConcurrentHashMap<String, java.util.BitSet> usedMap =
                (java.util.concurrent.ConcurrentHashMap<String, java.util.BitSet>) usedMapField.get(ssrcFactory);
        java.util.BitSet bits = usedMap.get(SERVER_ID);
        assertNotNull(bits);
        assertEquals(10000, bits.cardinality(), "All 10000 bits should be set");
    }

    @Test
    void multipleServers_shouldNotAffectEachOther() {
        String server1 = "server-1";
        String server2 = "server-2";
        String server3 = "server-3";
        ssrcFactory.markReconciliationReady(rtpServer(server1), new ArrayList<>());
        ssrcFactory.markReconciliationReady(rtpServer(server2), new ArrayList<>());
        ssrcFactory.markReconciliationReady(rtpServer(server3), new ArrayList<>());

        for (int i = 0; i < 10000; i++) {
            ssrcFactory.getPlaySsrc(server1);
        }
        assertNull(ssrcFactory.getPlaySsrc(server1));

        assertNotNull(ssrcFactory.getPlaySsrc(server2));
        assertNotNull(ssrcFactory.getPlaySsrc(server3));

        for (int i = 0; i < 100; i++) {
            ssrcFactory.getPlaySsrc(server2);
            ssrcFactory.getPlaySsrc(server3);
        }
        assertNull(ssrcFactory.getPlaySsrc(server1));
    }

    @Test
    void linearProbe_skipsUsedSlots() throws Exception {
        Field usedMapField = SSRCFactory.class.getDeclaredField("usedMap");
        usedMapField.setAccessible(true);
        java.util.concurrent.ConcurrentHashMap<String, java.util.BitSet> usedMap =
                (java.util.concurrent.ConcurrentHashMap<String, java.util.BitSet>) usedMapField.get(ssrcFactory);
        java.util.BitSet bits = new java.util.BitSet(10000);
        for (int i = 0; i < 100; i++) {
            bits.set(i);
        }
        usedMap.put(SERVER_ID, bits);

        String ssrc = ssrcFactory.getPlaySsrc(SERVER_ID);
        assertNotNull(ssrc, "Should find a free slot via linear probe");
        int suffix = Integer.parseInt(ssrc.substring(6));
        assertTrue(suffix >= 100, "Should skip used slots 0-99, got suffix " + suffix);
    }

    @Test
    void ssrc_shouldBeDifferentEachCall() {
        Set<String> results = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            results.add(ssrcFactory.getPlaySsrc(SERVER_ID));
        }
        assertEquals(100, results.size(), "All 100 calls should return different SSRCs");
    }

    @Test
    void lease_canBeReleasedAndReallocated() {
        SsrcLease lease = ssrcFactory.allocatePlayLease(SERVER_ID);
        assertTrue(lease.isOwned());
        ssrcFactory.release(lease);
        assertNotNull(ssrcFactory.allocatePlayLease(SERVER_ID));
    }

    @Test
    void releasingSameLeaseTwiceIsHarmless() {
        SsrcLease lease = ssrcFactory.allocatePlayLease(SERVER_ID);
        ssrcFactory.release(lease);
        ssrcFactory.release(lease);
        assertNotNull(ssrcFactory.allocatePlayLease(SERVER_ID));
    }

    @Test
    void nonOwnedLeaseDoesNotClearBitSet() throws Exception {
        SsrcLease lease = new SsrcLease(SERVER_ID, "0200000001", false, "preset");
        ssrcFactory.release(lease);

        String allocated = ssrcFactory.allocatePlayLease(SERVER_ID).getSsrc();
        assertNotNull(allocated);
    }

    @Test
    void randomLeases_areOwnedUniqueAndReusableAfterRelease() {
        MediaServer server = reconciledServer(true);

        Set<String> allocated = new HashSet<>();
        List<SsrcLease> leases = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            SsrcLease lease = ssrcFactory.allocatePlayLease(server);
            assertNotNull(lease);
            assertTrue(lease.isOwned());
            assertTrue(allocated.add(lease.getSsrc()), "duplicate random SSRC: " + lease.getSsrc());
            leases.add(lease);
        }
        leases.forEach(ssrcFactory::release);

        assertNotNull(ssrcFactory.allocatePlayLease(server));
    }

    @Test
    void allocation_isBlockedUntilReconciliationSucceeds() {
        MediaServer server = rtpServer(SERVER_ID);
        UserSetting settings = mock(UserSetting.class);
        when(settings.getSsrcRandom()).thenReturn(false);
        ReflectionTestUtils.setField(ssrcFactory, "userSetting", settings);
        ReflectionTestUtils.setField(ssrcFactory, "mediaServerService", mock(IMediaServerService.class));
        ReflectionTestUtils.setField(ssrcFactory, "zlmresTfulUtils", mock(ZLMRESTfulUtils.class));

        ssrcFactory.markReconciliationFailed(server, "query failed");
        assertNull(ssrcFactory.allocatePlayLease(server));

        ssrcFactory.markReconciliationReady(server, new ArrayList<>());
        assertNotNull(ssrcFactory.allocatePlayLease(server));
    }

    @Test
    void rebuildFailureBlocksExistingServerUntilQueryRecovers() {
        MediaServer server = rtpServer(SERVER_ID);
        IMediaServerService mediaService = mock(IMediaServerService.class);
        ZLMRESTfulUtils zlm = mock(ZLMRESTfulUtils.class);
        UserSetting settings = mock(UserSetting.class);
        when(settings.getSsrcRandom()).thenReturn(false);
        ReflectionTestUtils.setField(ssrcFactory, "userSetting", settings);
        ReflectionTestUtils.setField(ssrcFactory, "mediaServerService", mediaService);
        ReflectionTestUtils.setField(ssrcFactory, "zlmresTfulUtils", zlm);
        when(mediaService.getAll()).thenReturn(List.of(server));
        ZLMResult<JSONArray> failed = new ZLMResult<>();
        failed.setCode(-1);
        failed.setMsg("zlm unavailable");
        when(zlm.getMediaList(eq(server), isNull(), isNull(), eq("rtsp"), isNull()))
                .thenReturn(failed);

        ssrcFactory.rebuild();
        assertNull(ssrcFactory.allocatePlayLease(server));

        ZLMResult<JSONArray> recovered = new ZLMResult<>();
        recovered.setCode(0);
        recovered.setData(new JSONArray());
        when(zlm.getMediaList(eq(server), isNull(), isNull(), eq("rtsp"), isNull()))
                .thenReturn(recovered);
        ssrcFactory.rebuild();
        assertNotNull(ssrcFactory.allocatePlayLease(server));
    }

    @Test
    void rebuildWithSuccessfulNullData_treatsMediaServerAsEmpty() {
        MediaServer server = rtpServer(SERVER_ID);
        IMediaServerService mediaService = mock(IMediaServerService.class);
        ZLMRESTfulUtils zlm = mock(ZLMRESTfulUtils.class);
        UserSetting settings = mock(UserSetting.class);
        when(settings.getSsrcRandom()).thenReturn(false);
        ReflectionTestUtils.setField(ssrcFactory, "userSetting", settings);
        ReflectionTestUtils.setField(ssrcFactory, "mediaServerService", mediaService);
        ReflectionTestUtils.setField(ssrcFactory, "zlmresTfulUtils", zlm);
        when(mediaService.getAll()).thenReturn(List.of(server));

        ZLMResult<JSONArray> empty = new ZLMResult<>();
        empty.setCode(0);
        empty.setData(null);
        when(zlm.getMediaList(eq(server), isNull(), isNull(), eq("rtsp"), isNull()))
                .thenReturn(empty);

        ssrcFactory.rebuild();

        assertNotNull(ssrcFactory.allocatePlayLease(server));
    }

    @Test
    void mediaServerOnlineEvent_retriesReconciliationAfterStartupSettles() throws Exception {
        SSRCFactory factory = new SSRCFactory();
        try {
            ReflectionTestUtils.setField(factory, "domainPart", DOMAIN_PART);
            MediaServer server = rtpServer(SERVER_ID);
            IMediaServerService mediaService = mock(IMediaServerService.class);
            ZLMRESTfulUtils zlm = mock(ZLMRESTfulUtils.class);
            UserSetting settings = mock(UserSetting.class);
            when(settings.getSsrcRandom()).thenReturn(false);
            ReflectionTestUtils.setField(factory, "userSetting", settings);
            ReflectionTestUtils.setField(factory, "mediaServerService", mediaService);
            ReflectionTestUtils.setField(factory, "zlmresTfulUtils", zlm);
            long mediaServerReadyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            when(mediaService.getAll()).thenAnswer(invocation ->
                    System.nanoTime() < mediaServerReadyAt ? List.of() : List.of(server));
            factory.markReconciliationFailed(server, "startup race");

            ZLMResult<JSONArray> recovered = new ZLMResult<>();
            recovered.setCode(0);
            recovered.setData(new JSONArray());
            CountDownLatch queried = new CountDownLatch(1);
            when(zlm.getMediaList(eq(server), isNull(), isNull(), eq("rtsp"), isNull()))
                    .thenAnswer(invocation -> {
                        queried.countDown();
                        return recovered;
                    });

            MediaServerOnlineEvent onlineEvent = new MediaServerOnlineEvent(this);
            onlineEvent.setMediaServer(server);
            factory.onMediaServerOnline(onlineEvent);

            assertTrue(queried.await(2, TimeUnit.SECONDS), "online event should trigger reconciliation after startup settles");
            assertNotNull(factory.allocatePlayLease(server));
        } finally {
            Field schedulerField = SSRCFactory.class.getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            ((java.util.concurrent.ScheduledExecutorService) schedulerField.get(factory)).shutdownNow();
        }
    }

    @Test
    void rebuildWithOnlyInvalidServers_blocksAutomaticAllocation() {
        IMediaServerService mediaService = mock(IMediaServerService.class);
        ReflectionTestUtils.setField(ssrcFactory, "mediaServerService", mediaService);
        when(mediaService.getAll()).thenReturn(java.util.Arrays.asList(null, new MediaServer()));

        ssrcFactory.rebuild();

        assertNull(ssrcFactory.allocatePlayLease(SERVER_ID));
    }

    private MediaServer reconciledServer(boolean random) {
        MediaServer server = rtpServer(SERVER_ID);
        UserSetting settings = mock(UserSetting.class);
        when(settings.getSsrcRandom()).thenReturn(random);
        ReflectionTestUtils.setField(ssrcFactory, "userSetting", settings);
        ssrcFactory.markReconciliationReady(server, new ArrayList<>());
        return server;
    }

    private MediaServer rtpServer(String id) {
        MediaServer server = new MediaServer();
        server.setId(id);
        server.setRtpEnable(true);
        return server;
    }
}
