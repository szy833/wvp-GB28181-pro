# SSRC Allocation Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with review checkpoints.

**Goal:** Make internally generated SSRCs owner-aware across voice talk and random mode, and fail closed when ZLM occupancy reconciliation is not trusted.

**Architecture:** Extend `SSRCFactory` with a per-media-server reconciliation gate and make random candidates reserve the existing ten-thousand-slot pool under the existing lock. Carry an owned `SsrcLease` through the talk lifecycle and release it from one idempotent terminal helper. Preserve preset SSRCs, wire formats, SIP commands, ZLM API signatures, and existing business stream identities.

**Tech Stack:** Java 21, Spring, JUnit 5, Mockito, Maven.

## Global Constraints

- Only change files inside `/data/shizy/wvp-GB28181-pro`.
- Preserve all existing user modifications and never modify `t.cap`.
- Do not change SIP payloads, ZLM REST method signatures, Redis key formats, or non-RTP SSRC factories.
- Automatic allocation is unavailable until a media server has a trusted ZLM reconciliation; every blocked allocation logs the media server id and state.
- Preset SSRCs remain externally owned and never enter the internal lease pool.

### Task 1: Add failing SSRC allocator tests

**Files:**
- Modify: `src/test/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactoryTest.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactory.java` only after the tests fail

**Interfaces:**
- Tests consume `allocatePlayLease(String)`, `allocatePlayLease(MediaServer)`, `release(SsrcLease)`, and package-visible reconciliation helpers exposed only if needed for deterministic tests.
- Tests produce the expected behavior contract for random uniqueness, release reuse, and fail-closed allocation.

- [ ] **Step 1: Add a test that random leases reserve unique SSRCs and can be reused after release.**

```java
@Test
void randomLeases_areUniqueAndReusableAfterRelease() {
    ReflectionTestUtils.setField(ssrcFactory, "userSetting", randomSettings(true));
    MediaServer server = rtpServer(SERVER_ID);
    markReconciled(server);

    Set<String> allocated = new HashSet<>();
    List<SsrcLease> leases = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
        SsrcLease lease = ssrcFactory.allocatePlayLease(server);
        assertNotNull(lease);
        assertTrue(lease.isOwned());
        assertTrue(allocated.add(lease.getSsrc()));
        leases.add(lease);
    }
    leases.forEach(ssrcFactory::release);
    assertNotNull(ssrcFactory.allocatePlayLease(server));
}
```

- [ ] **Step 2: Add a test that an untrusted reconciliation blocks allocation and logs/recovers after success.**

```java
@Test
void allocation_isBlockedUntilReconciliationSucceeds() {
    ReflectionTestUtils.setField(ssrcFactory, "userSetting", randomSettings(false));
    MediaServer server = rtpServer(SERVER_ID);
    markReconciliationFailed(server);

    assertNull(ssrcFactory.allocatePlayLease(server));

    markReconciled(server);
    assertNotNull(ssrcFactory.allocatePlayLease(server));
}
```

- [ ] **Step 3: Run only the new tests and confirm they fail for the missing behavior, not for setup errors.**

Run: `mvn -DskipTests=false -Dtest=SSRCFactoryTest test`

Expected: the new random uniqueness and fail-closed assertions fail before production changes. If Maven stops at the known `RedisUtil2.java:900` encoding failure, record that blocker and use the existing project test harness or compile workaround without modifying `RedisUtil2.java`.

### Task 2: Implement owner-aware random allocation and reconciliation gate

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactory.java`
- Modify: `src/test/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactoryTest.java`

**Interfaces:**
- `allocatePlayLease(MediaServer)` and `allocatePlaybackLease(MediaServer)` return owned leases in both deterministic and random modes.
- `release(SsrcLease)` remains idempotent and clears only the matching owned reservation.
- Reconciliation updates per-server state and logs blocked allocation/recovery transitions.

- [ ] **Step 1: Add per-server reconciliation state initialized as unknown and a helper that rejects allocation unless state is ready.**

```java
private enum ReconciliationState { UNKNOWN, READY, FAILED }
private final ConcurrentHashMap<String, ReconciliationState> reconciliationStates = new ConcurrentHashMap<>();

private boolean allocationReady(String mediaServerId) {
    ReconciliationState state = reconciliationStates.getOrDefault(mediaServerId, ReconciliationState.UNKNOWN);
    if (state != ReconciliationState.READY) {
        log.warn("[SSRC] 媒体节点 {} 的SSRC对账状态为{}，暂停自动分配", mediaServerId, state);
        return false;
    }
    return true;
}
```

- [ ] **Step 2: Replace `randomLease()` with locked random candidate selection over free `BitSet` slots, then register an owned lease in `activeLeases`.**

```java
private SsrcLease randomLease(String mediaServerId, String prefix) {
    synchronized (lockMap.computeIfAbsent(mediaServerId, k -> new Object())) {
        BitSet bits = usedMap.computeIfAbsent(mediaServerId, k -> new BitSet(10000));
        int start = ThreadLocalRandom.current().nextInt(10000);
        for (int offset = 0; offset < 10000; offset++) {
            int index = (start + offset) % 10000;
            if (!bits.get(index)) {
                bits.set(index);
                String ssrc = prefix + domainPart + String.format("%04d", index);
                SsrcLease lease = new SsrcLease(mediaServerId, ssrc, true, UUID.randomUUID().toString());
                activeLeases.computeIfAbsent(mediaServerId, key -> new ConcurrentHashMap<>())
                        .put(lease.getLeaseId(), lease);
                return lease;
            }
        }
        return null;
    }
```

- [ ] **Step 3: Gate all automatic lease allocation and remove the random-node rebuild skip.**

```java
public SsrcLease allocatePlayLease(MediaServer mediaServer) {
    if (!allocationReady(mediaServer.getId())) {
        return null;
    }
    return mediaServer.isRtpEnable() && userSetting.getSsrcRandom()
            ? randomLease(mediaServer.getId(), "0")
            : allocatePlayLease(mediaServer.getId());
}
```

The rebuild path must set `READY` only after a valid snapshot, set `FAILED` on query/parse failure while retaining the previous trusted `usedMap`, merge all active leases, and log recovery from `UNKNOWN`/`FAILED`.

- [ ] **Step 4: Run `SSRCFactoryTest` again and confirm the new behaviors pass.**

Run: `mvn -DskipTests=false -Dtest=SSRCFactoryTest test`

Expected: all SSRC tests pass, or the command remains blocked solely by the pre-existing `RedisUtil2.java:900` encoding error.

### Task 3: Add failing talk lifecycle tests

**Files:**
- Modify: `src/test/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImplTalkTimeoutTest.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java` only after tests fail

**Interfaces:**
- Talk setup stores the generated `SsrcLease` alongside the active `SendRtpInfo` state.
- Every terminal path invokes one idempotent lease-release operation.

- [ ] **Step 1: Add a test that talk setup failure releases the allocated lease.**

```java
@Test
void talkSetupFailure_releasesYssrcLease() {
    SsrcLease lease = new SsrcLease("media-1", "0200000001", true, "lease-1");
    when(ssrcFactory.allocatePlayLease(mediaServer)).thenReturn(lease);
    when(sendRtpServerService.createSendRtpInfo(any(), any(), any(), anyString(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
            .thenReturn(null);

    service.talkCmd(device, channel, mediaServer, "stream-1", event);

    verify(ssrcFactory).release(lease);
}
```

- [ ] **Step 2: Add a test that explicit `stopTalk()` releases the same lease once even when called twice.**

```java
@Test
void stopTalk_isIdempotentAndReleasesLeaseOnce() {
    // Arrange an active SendRtpInfo carrying the talk lease.
    service.stopTalk(device, channel);
    service.stopTalk(device, channel);
    verify(ssrcFactory, times(1)).release(any(SsrcLease.class));
}
```

- [ ] **Step 3: Run the talk tests and confirm they fail because no lease is carried/released.**

Run: `mvn -DskipTests=false -Dtest=PlayServiceImplTalkTimeoutTest test`

Expected: the new release verifications fail before production changes, or the build is blocked by the known compiler encoding issue.

### Task 4: Implement and review the talk lease lifecycle

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/service/bean/SendRtpInfo.java` or add a focused talk state holder only if the existing model cannot carry the lease without changing persistence contracts
- Modify: `src/test/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImplTalkTimeoutTest.java`

**Interfaces:**
- The lease is local lifecycle state; it must not be serialized into existing Redis `SendRtpInfo` payloads unless required by current serialization conventions.
- The release helper must be safe when the lease is null, already released, or cleanup is invoked from multiple callbacks.

- [ ] **Step 1: Add a private in-memory owner map keyed by the talk send identity or channel id, with compare/remove semantics.**

```java
private final ConcurrentHashMap<Integer, SsrcLease> talkSsrcLeases = new ConcurrentHashMap<>();

private void releaseTalkLease(Integer channelId) {
    if (channelId == null) {
        return;
    }
    SsrcLease lease = talkSsrcLeases.remove(channelId);
    if (lease != null) {
        try {
            ssrcFactory.release(lease);
        } catch (RuntimeException e) {
            log.warn("[语音对讲] 释放SSRC lease失败，channelId={}", channelId, e);
        }
    }
}
```

- [ ] **Step 2: Allocate the lease through `allocatePlayLease(mediaServerItem)` and register it before any operation that can return or throw.**

- [ ] **Step 3: Call `releaseTalkLease(channel.getId())` from every post-allocation return/catch/finally path and at the beginning/end of `stopTalk()` according to the existing stop ordering.**

- [ ] **Step 4: Keep the talk SSRC string passed to `talkStreamCmd()` unchanged; only its ownership mechanism changes.**

- [ ] **Step 5: Run talk and SSRC tests and confirm release is exactly once.**

Run: `mvn -DskipTests=false -Dtest=PlayServiceImplTalkTimeoutTest,SSRCFactoryTest test`

Expected: all selected tests pass, subject to the known compiler encoding blocker.

### Task 5: Boundary review and verification

**Files:**
- Review only: all files changed by Tasks 1-4

- [ ] **Step 1: Inspect the diff for scope violations.**

Run: `git diff --stat && git diff --check && git status --short`

Confirm no changes to SIP XML/SDP formats, ZLM REST signatures, Redis keys, `t.cap`, or unrelated factories.

- [ ] **Step 2: Review all allocation and release call sites.**

Run: `rg -n "getPlaySsrc\(|getPlayBackSsrc\(|allocatePlayLease|allocatePlaybackLease|ssrcFactory\.release|talkSsrcLeases" src/main/java src/test/java`

Confirm production lifecycle-managed callers use leases, compatibility methods have no remaining production use except explicitly documented legacy boundaries, and every talk terminal path calls the release helper.

- [ ] **Step 3: Run targeted verification.**

Run: `mvn -DskipTests=false -Dtest=SSRCFactoryTest,PlayServiceImplTalkTimeoutTest,RtpServerServiceImplTest test`

Record the exact result. Do not claim a passing build if `RedisUtil2.java:900` still prevents compilation.

- [ ] **Step 4: Perform code review against the approved design.**

Check: random leases are owned and collision-free; unknown/failed reconciliation logs and blocks allocation; successful reconciliation restores allocation; talk release is independent of remote cleanup; preset SSRCs remain untouched; no new unbounded logging or network calls were added to hot paths.
