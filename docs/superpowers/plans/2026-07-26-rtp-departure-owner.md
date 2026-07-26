# RTP Departure Owner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make RTP departure cleanup notify waiting callers and remain owner-safe when business streams are reused.

**Architecture:** Preserve ZLM `originUrl` on departure events, correlate RTP departures to the context's actual ZLM stream, and use the existing lifecycle context to choose failure-versus-close behavior. Uncorrelatable replaced-stream events must not close a current owner.

**Tech Stack:** Java 21, Spring events, JUnit 5, Mockito, existing RTP lifecycle classes.

## Global Constraints

- Only touch the departure event, RTP lifecycle context/service, and focused tests.
- Keep callback-at-most-once and cleanup-before-callback semantics.
- Preserve existing behavior for successful resources and non-replaced streams.
- Do not modify JT1078, Redis migration, BYE handling, or legacy Hook registration in this change.

---

### Task 1: Preserve departure correlation data

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/media/event/media/MediaDepartureEvent.java`
- Test: `src/test/java/com/genersoft/iot/vmp/service/impl/RtpServerServiceImplTest.java`

- [ ] **Step 1: Add a failing event-data test**

Create a ZLM `OnStreamChangedHookParam` with `originUrl = "rtp://127.0.0.1/rtp/0000007B"`, build a `MediaDepartureEvent`, and assert `event.getOriginUrl()` returns that value.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
export JAVA_HOME=/usr/local/jdk-21.0.2
export PATH="$JAVA_HOME/bin:/usr/local/maven/apache-maven-3.9.16/bin:$PATH"
mvn -Dtest=RtpServerServiceImplTest#departureEventPreservesOriginUrl test
```

Expected: compilation failure because `MediaDepartureEvent` has no origin URL accessor.

- [ ] **Step 3: Implement the minimal event field and factory copy**

Add a nullable `originUrl` field with getter/setter and copy `hookParam.getOriginUrl()` in the ZLM factory overload. Leave ABL construction unchanged.

- [ ] **Step 4: Run the focused test**

Run the same Maven command. Expected: the test reaches the existing workspace compilation blocker if it remains; otherwise it passes.

### Task 2: Make departure transitions and lookup owner-safe

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/service/bean/RtpResourceContext.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/service/impl/RtpServerServiceImpl.java`
- Test: `src/test/java/com/genersoft/iot/vmp/service/impl/RtpServerServiceImplTest.java`

- [ ] **Step 1: Add failing lifecycle tests**

Add tests that:

```java
// REGISTERING/WAITING_MEDIA departure invokes one failure callback.
// SUCCESS departure runs terminal cleanup.
// An event for old ZLM stream 0000007B does not close a current owner for 000000C8.
```

Use reflection only to install contexts into the existing `resourceOwners` map; assert the context state and callback/cleanup counters.

- [ ] **Step 2: Run the focused tests and verify the new assertions fail**

Run:

```bash
export JAVA_HOME=/usr/local/jdk-21.0.2
export PATH="$JAVA_HOME/bin:/usr/local/maven/apache-maven-3.9.16/bin:$PATH"
mvn -Dtest=RtpServerServiceImplTest test
```

- [ ] **Step 3: Add an explicit departure transition**

Implement `completeDeparture(int code, String message, HookData data)` in `RtpResourceContext` by reusing the existing single-transition failure path. It must be a no-op after `SUCCESS`, `FAILED`, `TIMED_OUT`, or `CLOSED`.

- [ ] **Step 4: Correlate departures to the actual ZLM stream**

In `RtpServerServiceImpl`, extract the final path segment after `/rtp/` from `event.getOriginUrl()`, normalize it to uppercase, and resolve the ZLM owner key. Validate that the selected context's business stream equals the event stream. Without a parsed RTP origin, only use the business owner key when `context.getZlmStreamId()` equals `event.getStream()`.

- [ ] **Step 5: Choose failure versus close**

For a matched context in `REGISTERING` or `WAITING_MEDIA`, call:

```java
context.completeDeparture(InviteErrorCode.FAIL.getCode(), "媒体流已离开", null);
```

For a matched context in `SUCCESS`, keep calling `context.close("media departure")`.

- [ ] **Step 6: Run the focused tests again**

Run the same Maven command and verify all new assertions pass when compilation is available.
