# Real-Time Play HTTP Timeout Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make a real-time play HTTP timeout release the existing play owner through the normal stop path, so a late SIP/media response cannot leave local RTP, ZLM, SIP-session, or play-state resources alive.

**Architecture:** Keep the fix at the HTTP controller boundary and reuse the already owner-aware `IPlayService.stop(...)` implementation. The timeout callback must stop the play request instead of independently deleting Redis state and resetting the channel. No new RTP lifecycle or Redis storage model is introduced.

**Tech Stack:** Java 21, Spring MVC `DeferredResult`, Mockito/JUnit 5, existing `PlayServiceImpl`/`RtpResourceContext` cleanup path.

## Global Constraints

- Scope is limited to the real-time play endpoint `GET /api/play/start/{deviceId}/{channelId}`.
- Do not change playback, download, talk/broadcast, `DeferredResultHolder`, or global RTP owner architecture in this patch.
- Do not add a second BYE/RTP cleanup implementation in the controller.
- Cleanup must remain idempotent and tolerate BYE, Redis, state-reset, and RTP-close failures.
- A concurrent-request owner-token redesign is explicitly out of scope for this minimal patch and must be tracked separately if required.

---

### Task 1: Route real-time play timeout through the unified stop path

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/controller/PlayController.java:96-108`
- Verify existing implementation: `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java:1672-1835`

**Interfaces:**
- Consumes: `IPlayService.stop(InviteSessionType, Device, DeviceChannel, String)`.
- Produces: A timeout path that invokes the same owner-aware cleanup used by the manual stop endpoint.

- [ ] **Step 1: Remove controller-local resource mutations**

  Delete the timeout callback's direct calls to:

  ```java
  inviteStreamService.removeInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, channel.getId());
  deviceChannelService.stopPlay(channel.getId());
  ```

  The controller must not delete the `InviteInfo` before the service sees it, because the service needs `SSRCInfo.resourceId`, media-server information, and the actual ZLM stream owner for cleanup.

- [ ] **Step 2: Call the existing stop service with the canonical play stream**

  Define the same stream identity used by the stop endpoint:

  ```java
  String streamId = String.format("%s_%s", device.getDeviceId(), channel.getDeviceId());
  ```

  The timeout callback should invoke:

  ```java
  try {
      playService.stop(InviteSessionType.PLAY, device, channel, streamId);
  } catch (RuntimeException e) {
      log.warn("[点播等待超时] 统一停止流程失败，deviceId={}, channelId={}: {}",
              deviceId, channelId, e.getMessage());
  }
  ```

  Keep the existing timeout response construction. The cleanup exception must not prevent the HTTP timeout result from being set.

- [ ] **Step 3: Verify the service path remains the sole cleanup owner**

  Confirm `PlayServiceImpl.stop(...)` continues to perform, in an idempotent order:

  1. best-effort `InviteInfo` removal;
  2. best-effort BYE;
  3. play-state reset;
  4. owned SIP-session removal;
  5. owner-aware RTP/ZLM close.

  Do not add controller calls to `SipInviteSessionManager`, `IReceiveRtpServerService`, or `IInviteStreamService` for this issue.

---

### Task 2: Add focused controller timeout regression coverage

**Files:**
- Create: `src/test/java/com/genersoft/iot/vmp/gb28181/controller/PlayControllerTimeoutTest.java`
- Reuse assertions from: `src/test/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImplStopTest.java`

**Interfaces:**
- Consumes: the timeout cleanup callback in `PlayController` and mocked `IPlayService`.
- Produces: Regression coverage proving the controller delegates cleanup and does not perform partial cleanup itself.

- [ ] **Step 1: Add a testable timeout cleanup seam**

  Extract the callback body into a package-private method with this exact contract:

  ```java
  void stopPlayAfterHttpTimeout(String deviceId, String channelId,
                                Device device, DeviceChannel channel,
                                String streamId)
  ```

  The method must call `playService.stop(InviteSessionType.PLAY, device, channel, streamId)` once, catch `RuntimeException`, and never call the controller's Redis/state services directly.

- [ ] **Step 2: Test normal timeout delegation**

  Test name: `timeoutDelegatesToUnifiedPlayStop`

  Setup a controller with mocked `IPlayService`, a device, a channel, and `streamId = "device-1_channel-1"`. Invoke the timeout seam and verify:

  ```java
  verify(playService).stop(InviteSessionType.PLAY, device, channel, streamId);
  verifyNoInteractions(inviteStreamService);
  verify(deviceChannelService, never()).stopPlay(channel.getId());
  ```

- [ ] **Step 3: Test cleanup failure does not break timeout handling**

  Test name: `timeoutSwallowsStopRuntimeException`

  Configure `playService.stop(...)` to throw `IllegalStateException`. Invoke the timeout seam and assert that it does not throw. The test should verify the stop call happened once.

- [ ] **Step 4: Test the late-media cleanup contract at the resource-context boundary**

  Add `closedOwnerIgnoresLateMediaArrival` to `src/test/java/com/genersoft/iot/vmp/service/bean/RtpResourceContextTest.java` and cover this sequence:

  1. a play owner exists;
  2. HTTP timeout invokes unified stop and closes the context;
  3. a later media-arrival callback cannot transition the closed owner to success;
  4. the success callback is not invoked after the owner is closed.

  This test does not need to start a real SIP stack or ZLM server.

  The existing `PlayServiceImplStopTest` remains responsible for verifying the separate RTP, SIP-session, and play-state cleanup calls.

---

### Task 3: Verify the bounded change

**Files:**
- No additional production files.

- [ ] **Step 1: Run the focused tests**

  ```bash
  env LC_ALL=aa_DJ.utf8 LANG=aa_DJ.utf8 \
  JAVA_HOME=/usr/local/jdk-21.0.2 \
  PATH=/usr/local/jdk-21.0.2/bin:/usr/local/maven/apache-maven-3.9.16/bin:$PATH \
  mvn -Dtest=PlayControllerTimeoutTest,PlayServiceImplStopTest,RtpResourceContextTest test
  ```

  Expected: all selected tests pass.

- [ ] **Step 2: Run the full test suite**

  ```bash
  env LC_ALL=aa_DJ.utf8 LANG=aa_DJ.utf8 \
  JAVA_HOME=/usr/local/jdk-21.0.2 \
  PATH=/usr/local/jdk-21.0.2/bin:/usr/local/maven/apache-maven-3.9.16/bin:$PATH \
  mvn -DskipTests=false test
  ```

  Expected: Maven reports `BUILD SUCCESS`; any unrelated plugin shutdown warning must be recorded separately from test results.

## Explicit Follow-Up Boundary

This plan intentionally does not solve concurrent HTTP requests for the same device/channel. The timeout callback still identifies a play by the existing device/channel/stream key. If an old request must be prevented from stopping a newer owner, a separate plan should add an expected `resourceId`/owner-token stop API and conditional `InviteInfo` deletion; that change affects the play service interface and request lifecycle and is not included here.
