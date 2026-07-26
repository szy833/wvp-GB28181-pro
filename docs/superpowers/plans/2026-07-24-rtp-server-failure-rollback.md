# RTP Server Failure Rollback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 RTP Server 创建返回 `<= 0` 或抛异常时的任务、Hook、鉴权和 SSRC 残留，并保证成功、超时、外部关闭、异常路径只产生一个终态结果。

**Architecture:** 将一次 RTP 创建建模为一个带唯一 owner token 的生命周期上下文。上下文持有实际 ZLM stream、业务 stream、任务句柄、Hook 句柄、鉴权写入标识和 SSRC lease；所有终态通过同一个 CAS 状态机进入，清理操作按 owner 条件执行，避免旧请求误删新请求资源。保留现有业务接口的兼容包装，但所有当前调用方迁移到带结果/句柄的内部流程。

**Tech Stack:** Java 17、Spring Boot、JUnit 5、Mockito、ConcurrentHashMap、ScheduledFuture、现有 `IMediaServerService`/`HookSubscribe`/`DynamicTask` 抽象。

## Global Constraints

- 只修改 RTP 创建失败和生命周期清理相关代码，不改 SIP 协议、ZLM HTTP 接口语义和 Redis Key 格式。
- 不回退工作区已有改动，不使用 `git reset --hard` 或 `git checkout --`。
- 所有失败端口统一按 `port <= 0` 处理；`port == 0` 映射资源不足，`port < 0` 或异常映射媒体节点创建失败。
- 任何用户层 callback 最多执行一次；内部清理必须在 callback 前完成，并且 callback 抛异常不能阻止剩余清理。
- 任务、Hook、鉴权、SSRC 只能由创建它们的 owner 清理；禁止按裸 Key 无条件删除可能属于新请求的资源。
- `businessStreamId` 与 `zlmStreamId` 永远分开保存；RTP Server API 使用 `zlmStreamId`，业务流关闭使用 `businessStreamId`。
- 不依赖 5 秒 SSRC 重建或 5 分钟定时清理作为主回滚机制，它们只能作为校正机制。

---

### Task 1: Add lifecycle result, context, and SSRC lease models

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/service/bean/RtpServerOpenResult.java`
- Create: `src/main/java/com/genersoft/iot/vmp/service/bean/RtpResourceContext.java`
- Create: `src/main/java/com/genersoft/iot/vmp/service/bean/RtpResourceState.java`
- Create: `src/main/java/com/genersoft/iot/vmp/gb28181/session/SsrcLease.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/service/bean/SSRCInfo.java`

**Interfaces:**
- `RtpServerOpenResult` exposes `getPort()`, `getResourceId()`, `getBusinessStreamId()`, `getZlmStreamId()`, `isSuccess()` and `isFailure()`.
- `RtpResourceState` contains exactly `REGISTERING`, `WAITING_MEDIA`, `SUCCESS`, `FAILED`, `TIMED_OUT`, `CLOSED`.
- `RtpResourceContext` exposes `transitionTo(RtpResourceState)`, `completeSuccess(HookData)`, `completeFailure(int, String, HookData)`, `close(String)`, `markAuthWritten(String, Object)`, `getState()`, and `getResourceId()`.
- Allowed state transitions are `REGISTERING -> WAITING_MEDIA|FAILED|CLOSED`, `WAITING_MEDIA -> SUCCESS|FAILED|TIMED_OUT|CLOSED`, and `SUCCESS -> CLOSED`; terminal failure states cannot transition again.
- `SsrcLease` exposes `getMediaServerId()`, `getSsrc()`, `isOwned()`, `getLeaseId()`; it is immutable.
- `SSRCInfo` gains `resourceId` and `zlmStream` fields with getters/setters. Existing `stream` remains the business stream for SIP/session code.

- [ ] **Step 1: Write model tests first**

Create `src/test/java/com/genersoft/iot/vmp/service/bean/RtpResourceContextTest.java` and assert:

```java
assertTrue(context.transitionTo(RtpResourceState.WAITING_MEDIA));
assertTrue(context.transitionTo(RtpResourceState.SUCCESS));
assertFalse(context.transitionTo(RtpResourceState.FAILED));
assertEquals(RtpResourceState.SUCCESS, context.getState());
```

Also assert that a context marked `FAILED` cannot later become `SUCCESS`, and that `completeFailure` invoked twice calls the supplied callback once.

- [ ] **Step 2: Run the model test and verify it fails**

Run:

```bash
mvn -Dtest=RtpResourceContextTest test
```

Expected: compilation failure because the new model classes do not exist.

- [ ] **Step 3: Implement immutable result/lease and CAS state model**

Use `AtomicReference<RtpResourceState>` in `RtpResourceContext`. Terminal transitions must use `compareAndSet` and return immediately when the state is already terminal. Do not put user callback execution inside the state lock.

- [ ] **Step 4: Run the model test**

Run the same Maven command. Expected: PASS.

- [ ] **Step 5: Commit the isolated model change**

```bash
git add src/main/java/com/genersoft/iot/vmp/service/bean/RtpServerOpenResult.java \
  src/main/java/com/genersoft/iot/vmp/service/bean/RtpResourceContext.java \
  src/main/java/com/genersoft/iot/vmp/service/bean/RtpResourceState.java \
  src/main/java/com/genersoft/iot/vmp/gb28181/session/SsrcLease.java \
  src/main/java/com/genersoft/iot/vmp/service/bean/SSRCInfo.java \
  src/test/java/com/genersoft/iot/vmp/service/bean/RtpResourceContextTest.java
git commit -m "feat: add RTP resource lifecycle models"
```

---

### Task 2: Make SSRC allocation and release owner-aware

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactory.java`
- Modify: `src/test/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactoryTest.java`

**Interfaces:**
- Add `SsrcLease allocatePlayLease(String mediaServerId)` and `SsrcLease allocatePlayLease(MediaServer mediaServer)`.
- Add `SsrcLease allocatePlaybackLease(String mediaServerId)` and `SsrcLease allocatePlaybackLease(MediaServer mediaServer)`.
- Add `void release(SsrcLease lease)`.
- Keep `getPlaySsrc(...)` and `getPlayBackSsrc(...)` as compatibility methods delegating to allocation and intentionally not exposing a release token.

- [ ] **Step 1: Add failing lease tests**

Add tests with these exact behaviors:

```java
SsrcLease lease = ssrcFactory.allocatePlayLease("test-server");
assertTrue(lease.isOwned());
ssrcFactory.release(lease);
assertNotNull(ssrcFactory.allocatePlayLease("test-server"));
```

Add a test that releasing the same lease twice is harmless, and a test that a non-owned lease (random/preset path) does not clear a BitSet entry.

- [ ] **Step 2: Run the focused tests and verify failure**

```bash
mvn -Dtest=SSRCFactoryTest test
```

Expected: compilation or assertion failure for the missing lease API.

- [ ] **Step 3: Implement lease ownership**

For deterministic allocation, keep the existing per-media-server lock and BitSet, create a unique lease ID, and record active leases in a per-server map. For random and preset SSRCs, return `owned=false` and never clear a BitSet entry during release.

`rebuild()` must OR active local leases into the BitSet reconstructed from ZLM media-list data. A failed ZLM query must leave the previous BitSet unchanged. Do not replace a live reservation with an empty BitSet merely because the query returned no data.

- [ ] **Step 4: Run all SSRC tests**

```bash
mvn -Dtest=SSRCFactoryTest test
```

Expected: PASS, including existing exhaustion and two-prefix tests.

- [ ] **Step 5: Commit the SSRC change**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactory.java \
  src/test/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactoryTest.java
git commit -m "fix: make SSRC allocation explicitly releasable"
```

---

### Task 3: Add owner-safe DynamicTask cleanup

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/conf/DynamicTask.java`
- Create: `src/test/java/com/genersoft/iot/vmp/conf/DynamicTaskTest.java`

**Interfaces:**
- Add `ScheduledFuture<?> startDelayWithHandle(String key, Runnable task, int delay)`.
- Add `boolean stop(String key, ScheduledFuture<?> expectedFuture)`.
- Keep existing `startDelay` and `stop(String)` for unrelated callers; they must delegate without weakening existing behavior.

- [ ] **Step 1: Write race-oriented failing tests**

Test that stopping a completed future removes both `futureMap` and `runnableMap`. Test that stopping an old future does not remove a newer future stored under the same key:

```java
assertFalse(dynamicTask.stop("same-key", oldFuture));
assertTrue(dynamicTask.contains("same-key"));
assertSame(newFuture, futureMap.get("same-key"));
```

- [ ] **Step 2: Run the test and verify failure**

```bash
mvn -Dtest=DynamicTaskTest test
```

- [ ] **Step 3: Implement conditional removal**

Use `futureMap.remove(key, expectedFuture)` and remove the corresponding runnable only when the future is still the expected owner. The scheduled cleanup method must use the same conditional removal pattern. A completed or cancelled future must be removed even when `cancel(false)` returns false.

- [ ] **Step 4: Run the focused test**

```bash
mvn -Dtest=DynamicTaskTest test
```

Expected: PASS.

- [ ] **Step 5: Commit the task utility change**

```bash
git add src/main/java/com/genersoft/iot/vmp/conf/DynamicTask.java \
  src/test/java/com/genersoft/iot/vmp/conf/DynamicTaskTest.java
git commit -m "fix: make dynamic task cleanup owner-safe"
```

---

### Task 4: Make Hook identity media-server aware and removable by owner

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/media/event/hook/Hook.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/media/event/hook/HookSubscribe.java`
- Create: `src/main/java/com/genersoft/iot/vmp/media/event/hook/HookSubscriptionHandle.java`
- Create: `src/test/java/com/genersoft/iot/vmp/media/event/hook/HookSubscribeTest.java`

**Interfaces:**
- The four-argument `Hook.getInstance(...)` must retain `mediaServerId`.
- `Hook.toString()` must include `hookType`, `mediaServerId`, `app`, and `stream`.
- Add `HookSubscriptionHandle addSubscribeWithHandle(Hook hook, Event event)`; the handle contains the Hook key and the exact Event owner.
- Add `boolean removeSubscribe(HookSubscriptionHandle handle)` using conditional map removal.
- Add `boolean removeSubscribe(Hook hook, Event expectedEvent)` using conditional map removal.
- Keep `removeSubscribe(Hook hook)` as a compatibility method for legacy callers.

- [ ] **Step 1: Write failing identity tests**

Register the same hook type/app/stream on two different media servers. Assert both subscriptions remain present and each media arrival event invokes only its own callback. Register an old and new callback with the same key and assert removing the old callback does not remove the new one.

- [ ] **Step 2: Run the focused test and verify failure**

```bash
mvn -Dtest=HookSubscribeTest test
```

- [ ] **Step 3: Implement media-aware lookup**

`sendNotify` must derive the media server ID from `MediaEvent.getMediaServer()` and construct the same four-part Hook key. If the event has no media server, retain the existing three-part fallback for non-RTP legacy events.

- [ ] **Step 4: Run the focused test**

```bash
mvn -Dtest=HookSubscribeTest test
```

Expected: PASS.

- [ ] **Step 5: Commit the Hook change**

```bash
git add src/main/java/com/genersoft/iot/vmp/media/event/hook/Hook.java \
  src/main/java/com/genersoft/iot/vmp/media/event/hook/HookSubscribe.java \
  src/test/java/com/genersoft/iot/vmp/media/event/hook/HookSubscribeTest.java
git commit -m "fix: isolate media hook subscriptions by server"
```

---

### Task 5: Fix ZLM RTP creation retry and sentinel handling

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/media/zlm/ZLMServerFactory.java`
- Create: `src/test/java/com/genersoft/iot/vmp/media/zlm/ZLMServerFactoryTest.java`

**Interfaces:**
- Preserve `createRTPServer(...)` signature and return contract.

- [ ] **Step 1: Write failing retry tests**

Mock `getRtpInfo()` to return an existing RTP server with local port `0`, then mock `closeRtpServer()` and `openRtpServer()`. Capture the recursive call and assert all arguments stay in this order:

```text
mediaServer, app, streamId, ssrc, port, onlyAuto, disableAudio, reUsePort, tcpMode
```

Also test `getRtpInfo()` returning `null`; the method must return `-1` instead of throwing NPE.

- [ ] **Step 2: Run the test and verify failure**

```bash
mvn -Dtest=ZLMServerFactoryTest test
```

- [ ] **Step 3: Implement the exact argument fix**

Change the recursive call at `ZLMServerFactory.java:47` so app/stream and `disableAudio`/`reUsePort` are not swapped. Keep `-1` as the failure sentinel and guard every dereference of the ZLM result.

- [ ] **Step 4: Run the focused test**

```bash
mvn -Dtest=ZLMServerFactoryTest test
```

Expected: PASS.

- [ ] **Step 5: Commit the ZLM change**

```bash
git add src/main/java/com/genersoft/iot/vmp/media/zlm/ZLMServerFactory.java \
  src/test/java/com/genersoft/iot/vmp/media/zlm/ZLMServerFactoryTest.java
git commit -m "fix: preserve RTP retry arguments and failure sentinel"
```

---

### Task 6: Implement transactional RTP open/rollback in RtpServerServiceImpl

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/service/IReceiveRtpServerService.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/service/impl/RtpServerServiceImpl.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/service/bean/RTPServerParam.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/service/bean/SSRCInfo.java`
- Create: `src/test/java/com/genersoft/iot/vmp/service/impl/RtpServerServiceImplTest.java`

**Interfaces:**
- Add a handle-returning method:

```java
RtpServerOpenResult openCommonRTPServerWithHandle(
        RTPServerParam param,
        ErrorCallback<HookData> callback);
```

- Keep the existing `int openCommonRTPServer(...)` as a wrapper returning `getPort()` so external callers do not break. All in-repository callers must migrate to the handle method before this task is complete.
- Add `void closeRTPServer(RtpServerOpenResult result)` or an equivalent owner-handle close method. Existing `(MediaServer, app, stream)` close remains a fallback for legacy resources.
- Add an overload `addAuthenticateInfo(RtpServerOpenResult result, String streamId, String streamReplace, Boolean enableAudio, Boolean enableMp4, Integer mp4MaxSecond)` that marks the context as owner of the Redis value.

- [ ] **Step 1: Write failing rollback tests**

Mock all collaborators and cover these exact cases:

1. `createRTPServer()` returns `-1`.
2. `createRTPServer()` returns `0`.
3. `createRTPServer()` throws `RuntimeException`.
4. `dynamicTask.startDelayWithHandle()` throws.
5. `subscribe.addSubscribe()` throws.
6. Hook arrival races with creation failure.
7. User callback throws after a success event.

For cases 1-5 assert:

```text
callback count = 1
task owner removed
Hook owner removed
SSRC lease released when owned
RTP close attempted only when creation may have reached ZLM
no authentication key written
```

For case 6 assert exactly one terminal state and that a failed owner cannot close a newer owner. For case 7 assert internal task/Hook cleanup still occurs.

- [ ] **Step 2: Run the focused test and verify failure**

```bash
mvn -Dtest=RtpServerServiceImplTest test
```

- [ ] **Step 3: Implement context creation and resource registration**

Inside `openCommonRTPServerWithHandle`:

```java
String resourceId = UUID.randomUUID().toString();
String zlmStreamId = resolveZlmStreamId(param);
RtpResourceContext context = createContext(resourceId, param, zlmStreamId, callback);
context.registerTask(dynamicTask.startDelayWithHandle(
        context.getTimeoutTaskKey(),
        () -> context.completeFailure(
                InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode(),
                InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getMsg(),
                null),
        userSetting.getPlayTimeout()));
Hook hook = Hook.getInstance(
        HookType.on_media_arrival,
        param.getApp(),
        param.getStreamId(),
        param.getMediaServer().getId());
context.registerHook(subscribe.addSubscribeWithHandle(
        hook,
        hookData -> context.completeSuccess(hookData)));
```

Compute `zlmStreamId` before registering task/Hook. The timeout callback must call `context.completeFailure`, not directly call `closeRTPServer` and `callback` separately.

- [ ] **Step 4: Implement port classification and exception rollback**

Call the media service inside `try/catch`. Set a `mayHaveCreatedInZlm` flag before the remote call. Use this rule:

```java
if (port > 0) {
    context.markWaitingMedia(port, zlmStreamId);
    return successResult;
}
if (port == 0) {
    context.completeFailure(ERROR_FOR_RESOURCE_EXHAUSTION, "开启RTPServer失败", null);
} else {
    context.completeFailure(InviteErrorCode.FAIL.getCode(), "媒体节点创建RTPServer失败", null);
}
return failureResult;
```

On exception, call `completeFailure(InviteErrorCode.FAIL.getCode(), exception message, null)`. Cleanup must execute in a `finally`-safe path and each cleanup operation must catch/log its own exception so one failed remote close does not prevent SSRC release.

- [ ] **Step 5: Implement terminal state methods**

`completeFailure` must CAS only from `REGISTERING` or `WAITING_MEDIA` into the requested terminal failure state. The winning path must:

1. stop its own timeout future;
2. remove its own Hook;
3. close the actual `zlmStreamId` when `mayHaveCreatedInZlm` is true;
4. delete the Redis authentication value only if this context wrote the current value;
5. release its owned SSRC lease;
6. remove itself from active-context indexes;
7. invoke the user callback once, after cleanup.

`completeSuccess` stops/removes the wait resources but keeps the active context indexed for later external close. `close()` performs the full cleanup and transitions to `CLOSED`.

- [ ] **Step 6: Migrate the service's GB28181 open methods**

Update `openGbRTPServer`, `openGbRTPServerForPlay`, `openGbRTPServerForPlayback`, `openGbRTPServerForDownload`, and `openGbRTPServerForBroadcast` to:

- check SSRC for `null` before `Long.parseLong`;
- retain business stream and actual ZLM stream separately;
- attach `resourceId` and `zlmStream` to returned `SSRCInfo`;
- write authentication only when `result.isSuccess()`;
- call the owner handle's failure method if authentication write throws.

- [ ] **Step 7: Run the service tests**

```bash
mvn -Dtest=RtpServerServiceImplTest test
```

Expected: PASS for all rollback, exception, race, and null-SSRC cases.

- [ ] **Step 8: Commit the transactional service change**

```bash
git add src/main/java/com/genersoft/iot/vmp/service/IReceiveRtpServerService.java \
  src/main/java/com/genersoft/iot/vmp/service/impl/RtpServerServiceImpl.java \
  src/main/java/com/genersoft/iot/vmp/service/bean/RTPServerParam.java \
  src/main/java/com/genersoft/iot/vmp/service/bean/SSRCInfo.java \
  src/test/java/com/genersoft/iot/vmp/service/impl/RtpServerServiceImplTest.java
git commit -m "fix: rollback RTP resources on open failure"
```

---

### Task 7: Migrate every direct caller to the single callback contract

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/vmanager/rtp/RtpController.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/vmanager/ps/PsController.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/jt1078/service/impl/jt1078PlayServiceImpl.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlatformServiceImpl.java`
- Create: `src/test/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImplRtpFailureTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/vmanager/rtp/RtpControllerRtpFailureTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/vmanager/ps/PsControllerRtpFailureTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jt1078/service/impl/JT1078PlayServiceImplRtpFailureTest.java`

**Interfaces:**
- Every caller uses `RtpServerOpenResult` or the handle wrapper.
- Immediate failure callback is emitted by exactly one layer. Caller-side `if (port <= 0)` may perform control-flow return and state cleanup, but must not invoke the same user callback a second time.

- [ ] **Step 1: Add caller regression tests**

For Play, Playback, Download, RTP Controller, PS Controller, and JT1078, mock a `-1` result and assert:

```text
user callback count = 1
no negative port is persisted
no authentication write occurs
```

- [ ] **Step 2: Migrate failure checks**

Replace all direct `== 0` checks with `<= 0`, including `RtpController.java:144` and `PsController.java:133`. Ensure `RtpController.java:130` and `PsController.java:138` only write authentication after success. The JT1078 playback path at `jt1078PlayServiceImpl.java:516` must also guard authentication on a successful handle.

- [ ] **Step 3: Migrate close calls**

When an `SSRCInfo` has `resourceId`, use the owner-handle close method. Keep the old `(mediaServer, app, stream)` call only when no resource handle is available. This prevents business stream IDs from being sent to the ZLM RTP close API.

- [ ] **Step 4: Run caller tests and compile**

```bash
mvn -Dtest=PlayServiceImplRtpFailureTest,RtpControllerRtpFailureTest,PsControllerRtpFailureTest,JT1078PlayServiceImplRtpFailureTest test
mvn -DskipTests compile
```

Expected: all selected tests pass and compilation succeeds.

- [ ] **Step 5: Commit the caller migration**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java \
  src/main/java/com/genersoft/iot/vmp/vmanager/rtp/RtpController.java \
  src/main/java/com/genersoft/iot/vmp/vmanager/ps/PsController.java \
  src/main/java/com/genersoft/iot/vmp/jt1078/service/impl/jt1078PlayServiceImpl.java \
  src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlatformServiceImpl.java
git commit -m "fix: unify RTP failure handling across callers"
```

---

### Task 8: Verify lifecycle behavior and prepare review evidence

**Files:**
- Modify only test files if a missing regression case is discovered.
- Do not modify unrelated files or generated `graphify-out` artifacts.

**Interfaces:**
- No production interface changes in this task.

- [ ] **Step 1: Run focused regression tests**

```bash
mvn -Dtest=RtpResourceContextTest,SSRCFactoryTest,DynamicTaskTest,HookSubscribeTest,ZLMServerFactoryTest,RtpServerServiceImplTest test
```

- [ ] **Step 2: Run the full Java test suite**

```bash
mvn test
```

If unrelated pre-existing tests fail, record the exact test name and stack trace; do not hide or rewrite those failures.

- [ ] **Step 3: Run static checks**

```bash
mvn -DskipTests compile
git diff --check
git status --short
```

Confirm that only intended RTP lifecycle files and tests changed. Preserve all pre-existing user modifications.

- [ ] **Step 4: Produce implementation handoff evidence**

The implementation report must include:

- changed files;
- callback count evidence for `-1`, `0`, exception, timeout and Hook-race cases;
- evidence that task and Hook maps are empty after failed creation;
- evidence that the correct ZLM stream ID is used for close;
- evidence that Redis authentication is absent after failed creation;
- evidence that an old owner cannot remove a newer task/Hook;
- exact Maven commands and results.

---

## Explicit Non-Goals

- 不重写 SIP Invite、BYE 或会话状态机。
- 不把 ZLM 媒体列表重建删除为唯一 SSRC 回收机制，也不以延长定时清理周期解决生命周期问题。
- 不修改非 RTP 业务的 Redis Key 命名。
- 不通过全局 `clear()` 清空任务、Hook 或 SSRC Map 来“修复”竞态。

## Review Gate For Sol High

提交实现后，必须先回答以下问题再认为完成：

1. `openCommonRTPServer()` 返回 `-1` 时，哪一层调用用户 callback？为什么不会有第二次？
2. 一个旧请求的超时任务如何证明自己不能删除新请求的任务？
3. Hook 到达和创建失败同时发生时，哪个状态获胜？失败方是否可能关闭成功方的 RTP Server？
4. 业务 stream 和 ZLM stream 分别在哪里保存、创建、关闭和更新 SSRC？
5. preset/random SSRC 如何证明不被错误 release？
6. Redis 删除如何证明不会删掉后续请求写入的同名 Key？
7. 任务注册或 Hook 注册本身抛异常时，SSRC、Hook、任务和鉴权分别如何回滚？

任何一个问题无法用测试或代码路径回答，都不能声称该问题已经修复。
