# InviteInfo Redis Risk Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with review checkpoints.

**Goal:** Remove the identified InviteInfo indexing correctness and concurrency risks, then document the formal online load-test gate without inventing production measurements.

**Architecture:** Keep `VMP_GB_INVITE_INFO` authoritative. Add per-primary-field version keys so ready-mode transactions watch only the affected record; retain primary-hash watching before migration is activated. Make primary scans report completion, validate every index member against its primary field, reconcile stream-migration destinations, and make backfill scheduling recover from executor rejection.

**Tech Stack:** Java 21, Spring Data Redis, Redis transactions, JUnit 5, Mockito, Testcontainers Redis 7.4.

## Global Constraints

- Do not change GB28181 signaling, InviteInfo JSON schema, or controller contracts.
- Do not enable index-only reads before the existing operator-controlled `ready` marker.
- Preserve owner-safe deletion and primary Hash authority.
- Do not generate `.graphify_detect.json`.
- Do not fabricate online load-test results; record commands, metrics, gates, and owner fields only.

### Task 1: Add failing regression tests

**Files:**
- Modify: `src/test/java/com/genersoft/iot/vmp/gb28181/service/impl/InviteStreamServiceImplTest.java`
- Modify: `src/test/java/com/genersoft/iot/vmp/gb28181/task/InviteInfoIndexBackfillTaskTest.java`

- [ ] Add tests for version-key watching in ready mode, incomplete primary scans not being consumed by destructive callers, malformed index members being rejected, stream migration cleaning a pre-existing destination, and executor rejection resetting the backfill guard.
- [ ] Run the focused tests and verify each new test fails for the intended missing behavior.

### Task 2: Implement transaction and scan safety

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/common/VideoManagerConstants.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/InviteStreamServiceImpl.java`

- [ ] Add the version-key prefix and helper methods for watching/incrementing/deleting per-field versions.
- [ ] Use version keys for ready-mode update, SSRC update, restore, remove, refresh, and stream migration transactions; keep primary-hash watching before ready.
- [ ] Make primary scans return a completion-aware result and make cleanup/clear/full-removal paths skip destructive actions when the scan is incomplete.
- [ ] Run the new focused tests and the existing InviteStream test class.

### Task 3: Implement index and scheduler safety

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/InviteStreamServiceImpl.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/task/InviteInfoIndexBackfillTask.java`

- [ ] Require every indexed member to match `InviteInfoRedisIndex.primaryField()` before returning or retaining it.
- [ ] Read and remove destination-derived memberships during stream migration before overwriting the destination field.
- [ ] Reset the backfill `running` flag when task submission itself fails.
- [ ] Run all focused tests and Redis integration tests.

### Task 4: Record formal online load-test gate

**Files:**
- Modify: `docs/superpowers/specs/2026-07-27-invite-info-redis-indexing-design.md`

- [ ] Add a production-test record section with 10k/100k fixtures, 1k exact/partial lookup runs, concurrent write scenarios, Redis command-count and latency metrics, resource metrics, rollback/stop criteria, and explicit owner/date/result fields.
- [ ] State that local verification is not an online benchmark and leave result fields unfilled.

### Task 5: Verify delivery

- [ ] Run 59 focused unit tests and 3 Redis integration tests.
- [ ] Run production compilation/package with the known temporary exclusion of `RedisUtil2.java`, restoring it afterward.
- [ ] Run `git diff --check`, inspect status, and confirm `.graphify_detect.json` is absent.
