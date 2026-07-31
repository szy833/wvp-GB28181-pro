# Redis RPC Request Correlation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make Redis RPC request correlation collision-safe and ensure synchronous and asynchronous pending requests are cleaned up deterministically.

**Architecture:** Keep the wire-level `long sn` field, but generate IDs from a JVM-local `AtomicLong` and store one `PendingRequest` object per active request. Responses are accepted only for this WVP's `fromId`, atomically remove the pending owner, and then deliver once. Asynchronous requests receive a configurable default TTL and an explicit timeout overload.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, `ConcurrentHashMap`, `ScheduledExecutorService`.

## Global Constraints

- Work only under `/data/shizy/wvp-GB28181-pro`.
- Do not modify, delete, or commit `t.cap`.
- Preserve the existing Redis RPC JSON shape and `long sn` type.
- Do not add compatibility behavior for mixed WVP versions.
- Run `git status --short` before and after changes.

### Task 1: Add failing Redis RPC behavior tests

**Files:**
- Create: `src/test/java/com/genersoft/iot/vmp/conf/redis/RedisRpcConfigTest.java`

**Interfaces:**
- Tests will exercise `RedisRpcConfig.request`, the callback overload, and `response` through reflection-injected `UserSetting` and mocked `RedisTemplate`/`TaskExecutor`.

- [ ] **Step 1: Write tests for unique registration and response ownership**

  Add tests that assert concurrent requests receive distinct `sn` values, a response with a different `fromId` is ignored, and a matching response is delivered once.

- [ ] **Step 2: Write tests for synchronous timeout and interrupt handling**

  Assert a normal poll timeout returns `ErrorCode.ERROR486` and leaves no pending entry; assert an interrupted caller also returns `ERROR486` and preserves `Thread.interrupted()`.

- [ ] **Step 3: Write tests for asynchronous TTL cleanup**

  Inject a short TTL scheduler or invoke the package-visible expiry helper, then assert the callback map is empty after expiry and a late response does not invoke the callback.

- [ ] **Step 4: Run the focused test and verify RED**

  Run `mvn -Dtest=RedisRpcConfigTest test`; it must fail because the current implementation still uses random IDs, separate maps, and no TTL.

### Task 2: Replace request maps with owner-safe pending requests

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/conf/redis/RedisRpcConfig.java`

**Interfaces:**
- Add `request(RedisRpcRequest, long, TimeUnit, CommonCallback<RedisRpcResponse>)` for explicit async TTL.
- Preserve `request(RedisRpcRequest, CommonCallback<RedisRpcResponse>)` and delegate it to the configured default TTL.
- Preserve `removeCallback(long)` as an owner-safe compatibility method.

- [ ] **Step 1: Add the pending request type and ID generator**

  Define a private `PendingRequest` containing either a `SynchronousQueue<RedisRpcResponse>` or callback, an expiry handle, and a timeout flag. Add an `AtomicLong` initialized from a positive random seed above 1000 and a `ConcurrentHashMap<Long, PendingRequest>`.

- [ ] **Step 2: Implement atomic registration**

  Allocate a sequence with `incrementAndGet()` and register with `putIfAbsent`; retry only on the impossible wrapped-ID collision. Do not use `containsKey` followed by `put`.

- [ ] **Step 3: Implement owner-safe synchronous cleanup**

  Register before `sendRequest`, poll the queue, translate `null` and interruption to `ERROR486`, restore the interrupt flag, and call `pendingRequests.remove(sn, owner)` in `finally`.

- [ ] **Step 4: Implement asynchronous TTL registration**

  Register the callback owner before sending, schedule expiry, remove the exact owner on expiry, and invoke the timeout response once. If `sendRequest` throws, remove the owner and cancel its scheduled task.

- [ ] **Step 5: Implement atomic response delivery**

  Reject responses whose `fromId` is not the current server ID. Remove the pending owner before delivering. Offer to a synchronous queue or invoke the callback in a `try/finally` after state removal; ignore late/duplicate responses.

- [ ] **Step 6: Run the focused tests and verify GREEN**

  Run `mvn -Dtest=RedisRpcConfigTest test`; all new tests must pass.

### Task 3: Add configurable default callback TTL

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/conf/UserSetting.java`
- Modify: `src/main/resources/application-dev.yml`
- Modify: `src/main/resources/application-docker.yml`
- Modify: `src/main/resources/配置详情.yml`

**Interfaces:**
- Add `private long redisRpcCallbackTtl = 30000L` with getter/setter through Lombok.

- [ ] **Step 1: Add the default setting**

  Add `redis-rpc-callback-ttl: 30000` under each `user-settings` section and expose `getRedisRpcCallbackTtl()` for `RedisRpcConfig`.

- [ ] **Step 2: Run configuration and focused tests**

  Run `mvn -Dtest=RedisRpcConfigTest test`; verify the default async overload schedules cleanup using 30 seconds when no explicit TTL is supplied.

### Task 4: Verify callers and regression behavior

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/service/redisMsg/service/RedisRpcServiceImpl.java` only if compilation or callback contract requires an explicit TTL.
- Test: `src/test/java/com/genersoft/iot/vmp/conf/redis/RedisRpcConfigTest.java`

- [ ] **Step 1: Verify existing callback callers**

  Confirm `waitePushStreamOnline` and `onStreamOnlineEvent` still return the assigned `sn`; their manual `removeCallback` calls remain harmless because removal is owner-safe.

- [ ] **Step 2: Run related test suites**

  Run `mvn -Dtest=RedisRpcConfigTest,InviteStreamServiceRedisIntegrationTest test`.

- [ ] **Step 3: Run the full test suite**

  Run `mvn test`; record any environment-only integration failures in the final response without changing unrelated code.

### Task 5: Review and commit

- [ ] **Step 1: Inspect the diff**

  Run `git diff --check`, `git diff --stat`, and `git status --short`; ensure `t.cap` is untouched.

- [ ] **Step 2: Commit the implementation**

  Run `git add` only for the Redis RPC source, configuration, and test files, then commit with `fix: make Redis RPC request correlation collision-safe`.
