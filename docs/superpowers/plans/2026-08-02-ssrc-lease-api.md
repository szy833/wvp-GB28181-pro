# SSRC 租约接口收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除无法追踪释放责任的字符串 SSRC 分配接口，使自动分配只能通过 `SsrcLease` 完成。

**Architecture:** `SSRCFactory` 仅暴露按媒体节点分配的 Lease API；RTP 和对讲资源继续持有 Lease，并在所有终态释放。测试改为直接验证 Lease 生命周期和并发唯一性，不再依赖旧字符串接口。

**Tech Stack:** Java 21、Spring、JUnit 5、Mockito、Maven。

## Global Constraints

- 只修改 `/data/shizy/wvp-GB28181-pro` 内项目文件。
- 不修改、删除或提交 `t.cap`。
- 使用中文 Git 提交信息。
- 不改变 ZLM 对账失败时暂停自动分配的保护策略。

### Task 1: 将 SSRCFactory 测试迁移到 Lease API

**Files:**
- Modify: `src/test/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactoryTest.java`

**Interfaces:**
- Consumes: `SSRCFactory.allocatePlayLease(String)`, `SSRCFactory.allocatePlaybackLease(String)`, `SSRCFactory.release(SsrcLease)`。
- Produces: 覆盖分配、释放、幂等释放和随机并发唯一性的回归测试。

- [ ] **Step 1: 替换旧字符串分配调用**

将测试中 `getPlaySsrc(...)` 和 `getPlayBackSsrc(...)` 改为 Lease API；需要检查 SSRC 格式时读取 `lease.getSsrc()`，测试结束释放仍持有的 Lease，避免测试之间共享占用状态。

- [ ] **Step 2: 增加旧接口不存在的编译约束**

完成迁移后，测试源码中不得再出现 `getPlaySsrc` 或 `getPlayBackSsrc` 调用：

```bash
rg -n "getPlaySsrc|getPlayBackSsrc" src/test/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactoryTest.java
```

Expected: no output。

- [ ] **Step 3: 运行迁移后的测试确认基线**

```bash
mvn -q -Dtest=SSRCFactoryTest test
```

Expected: PASS；如果因旧生产接口尚未删除而失败，失败应仅来自测试编译或断言迁移问题。

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactoryTest.java
git commit -m "迁移SSRC工厂测试到租约接口"
```

### Task 2: 删除不可追踪的字符串分配接口

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactory.java`

**Interfaces:**
- Consumes: 现有 Lease 分配和释放实现。
- Produces: `SSRCFactory` 只保留 `allocatePlayLease`、`allocatePlaybackLease` 和 `release` 作为自动分配生命周期 API。

- [ ] **Step 1: 删除四个字符串接口**

删除以下方法及其旧 `allocate(...)` 依赖：

```java
getPlaySsrc(String mediaServerId)
getPlayBackSsrc(String mediaServerId)
getPlaySsrc(MediaServer mediaServer)
getPlayBackSsrc(MediaServer mediaServer)
```

保留 `allocateLocked(...)` 作为 Lease 实现内部的槽位分配辅助方法。

- [ ] **Step 2: 确认生产调用点全部使用 Lease**

```bash
rg -n "getPlaySsrc|getPlayBackSsrc" src/main/java src/test/java
```

Expected: no output。

- [ ] **Step 3: 运行 SSRC 和 RTP 相关测试**

```bash
mvn -q -Dtest=SSRCFactoryTest,PlayServiceImplTalkTimeoutTest test
```

Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactory.java
git commit -m "删除不可追踪的SSRC字符串分配接口"
```

### Task 3: 全量回归与工作区检查

**Files:**
- Verify: `src/main/java/com/genersoft/iot/vmp/gb28181/session/SSRCFactory.java`
- Verify: `src/main/java/com/genersoft/iot/vmp/service/impl/RtpServerServiceImpl.java`
- Verify: `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java`

**Interfaces:**
- Consumes: Tasks 1-2 的 Lease-only API。
- Produces: 编译通过、SSRC 租约生命周期回归通过、无意外文件修改。

- [ ] **Step 1: 检查中文提交和工作区状态**

```bash
git status --short
git diff --check
```

Expected: 仅包含本任务计划内的文件；无空白错误；不包含 `t.cap`。

- [ ] **Step 2: 运行完整后端测试**

```bash
mvn -q test
```

Expected: PASS；若环境缺少外部服务导致集成测试无法执行，记录具体测试和原因，不修改生产逻辑绕过。

- [ ] **Step 3: 检查残留字符串接口和旧分配实现**

```bash
rg -n "getPlaySsrc|getPlayBackSsrc|private String allocate\(" src/main/java src/test/java
```

Expected: no output。

