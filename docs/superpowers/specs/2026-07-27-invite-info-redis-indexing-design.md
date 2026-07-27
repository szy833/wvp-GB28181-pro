# InviteInfo Redis Indexing Design

**Status:** Approved design

## Goal

Eliminate global `HSCAN` and `HVALS` work from InviteInfo request hot paths and periodic lifecycle work, while preserving the existing `VMP_INVITE` primary records, RTP owner isolation, and GB28181 business behavior.

## Current State

InviteInfo is stored in one Redis Hash, `VMP_INVITE`, with a field formatted as:

```text
{type}:{channelId}:{stream}
```

`InviteStreamServiceImpl.getInviteInfo(...)` currently runs `HSCAN MATCH` for all query shapes, including queries with all three field components. Calls that know only a channel or stream therefore traverse the global Hash until a match is found. `HVALS` is also used for expiration, active RTP reconciliation, startup reconciliation, and media-server receive counts.

This couples the latency and Redis traffic of common point operations to the total number of active InviteInfo records.

## Chosen Approach

Keep `VMP_INVITE` as the sole primary source of truth and introduce narrowly scoped Redis indexes owned by `InviteStreamServiceImpl`:

```text
VMP_INVITE
  Hash: primary field -> InviteInfo

VMP_INVITE_INDEX_CHANNEL:{type}:{channelId}
  Set: primary field members

VMP_INVITE_INDEX_STREAM:{stream}
  Set: primary field members

VMP_INVITE_INDEX_SSRC:{ssrc}
  Set: primary field members

VMP_INVITE_ACTIVE_BY_MEDIA:{mediaServerId}
  Set: primary field members that count as active GB receive streams

VMP_INVITE_EXPIRE_AT
  ZSet: primary field member -> pending or completed-download cleanup deadline
```

The primary field, not a serialized InviteInfo copy, is the index member. Every index lookup must read the primary Hash field and validate the expected type, channel, stream, or SSRC before returning a result. The primary record remains authoritative if an index is stale.

## Query Rules

1. `type`, `channelId`, and `stream` all present: build the exact field and use one `HGET`.
2. `type + channelId`: scan only `VMP_INVITE_INDEX_CHANNEL:{type}:{channelId}` and validate candidates through `HGET`.
3. `type/null + stream`: scan only `VMP_INVITE_INDEX_STREAM:{stream}` and validate candidates through `HGET`; apply the supplied type filter after reading the primary record.
4. `ssrc`: scan only `VMP_INVITE_INDEX_SSRC:{ssrc}` and validate candidates through `HGET`.
5. `getStreamInfoCount(mediaServerId)`: use `SCARD(VMP_INVITE_ACTIVE_BY_MEDIA:{mediaServerId})`.

Index scans may be proportional to colliding candidates for one channel, stream, or SSRC, but must not be proportional to all fields in `VMP_INVITE`. Existing behavior returns one valid matching record when a partial query is ambiguous; this behavior is retained rather than introducing a new external uniqueness constraint.

## Lifecycle State Rules

The existing `InviteInfo` fields remain unchanged. Index membership is derived from the current primary value:

- A `ready` record is placed in `VMP_INVITE_EXPIRE_AT` with `createTime + expirationTime`.
- A record with an active `streamInfo` is removed from the pending deadline set.
- A completed download with `cleanupAt` is placed in `VMP_INVITE_EXPIRE_AT` using `cleanupAt`.
- A record counts as an active media receive stream exactly when it satisfies the current `getStreamInfoCount(...)` semantics: it has stream information, belongs to the target media server, and is not a completed download.
- Deleting a primary record removes all of its secondary-index, active-set, and deadline-set membership.

The scheduled pending/download cleaner obtains only expired ZSet members in bounded batches. Active RTP reconciliation reads the active set for the relevant media server instead of first materializing every InviteInfo record.

## Consistency and Owner Safety

All primary-record changes and derived-index changes must be performed in one Redis transaction. The implementation may use the existing `WATCH/MULTI/EXEC` pattern, but it must not leave a committed primary update without its corresponding index changes.

`removeInviteInfoIfSame(...)` keeps its current owner contract:

1. Read the exact primary field under `WATCH`.
2. Compare `ssrcInfo.resourceId` when available; otherwise compare the existing legacy snapshot.
3. In the same `MULTI/EXEC`, remove the primary field and every membership derived from the verified value.
4. If the owner changed or `EXEC` fails, remove nothing and do not retry against the replacement owner.

Index cleanup after a lookup must also be conditional. A stale index member must not be removed based only on a previously observed missing primary field, because a new owner could have been inserted in the meantime.

The normal upsert, stream migration, SSRC update, unconditional explicit removal, and `restoreInviteInfoIfAbsent(...)` paths must all route through the same internal persistence helpers. No controller, PlayService, RTP service, or SIP processor may write an InviteInfo index directly.

## Upgrade and Backfill

Existing Redis records do not initially have indexes. The upgrade must preserve successful lookups during this interval.

1. New-version writes immediately maintain primary records and all derived indexes.
2. A Redis-lock-protected, one-time backfill iterates the existing primary Hash in bounded `HSCAN` batches and idempotently creates indexes, active-set entries, and deadline entries.
3. Before the backfill completion marker exists, an index miss falls back to the legacy primary Hash scan and repairs indexes for any record found.
4. The implementation writes the completion marker only after a complete successful pass. Once the marker exists, point lookups do not fall back to global primary-Hash scans.
5. A Redis error or interrupted backfill leaves the marker absent. The system may be slower in this state but must not report an existing InviteInfo as missing.

The backfill is upgrade-only work. It is rate-limited and is not allowed to run as a foreground request operation.

## Explicit Scope Boundary

### In Scope

- `InviteStreamServiceImpl` persistence and lookup behavior.
- `IInviteStreamService` only where a new internal-facing query is needed for active media records.
- InviteInfo cleanup scheduling, active RTP reconciliation, media-node online reconciliation, and GB receive counting.
- Redis index backfill, conditional deletion, and tests for index consistency.

### Out of Scope

- Changes to the `InviteInfo` JSON schema, GB28181 signaling, SIP INVITE/ACK/BYE/MediaStatus behavior, ZLM Hook behavior, SSRC allocation, RTP owner/context management, or controller response contracts.
- A global Redis TTL or fixed TTL for successful PLAY/PLAYBACK records.
- A storage rewrite or migration away from `VMP_INVITE` as the primary Hash.
- Changes to DeferredResult, device directory business rules, or non-InviteInfo Redis caches.
- Changing partial-query ambiguity into a new uniqueness rule.
- Deleting historical index keys as part of rollback; orphaned index keys are harmless because the primary Hash is authoritative and the new code validates all members.

`getAllInviteInfo()` remains for explicit compatibility/administrative full snapshots, but production hot paths and periodic tasks must stop calling it. Its implementation may use a paged scan when a complete snapshot is genuinely requested; it is not a replacement for indexed point lookup.

## Error Handling

- Redis read, transaction, or backfill failures are fail-safe: preserve the primary record and do not perform destructive cleanup.
- ZLM RTP-list failure remains non-authoritative; active InviteInfo records are not removed.
- An empty successful RTP list remains authoritative only in the existing owner-safe reconciliation path.
- A missing or malformed index member is skipped and logged at an appropriate rate; it cannot cause a different primary record to be returned.

## Acceptance Criteria

### Functional

- Exact lookup returns the same record as before and uses exactly one primary `HGET`.
- Channel, stream, and SSRC lookup return only a primary record that validates against the query condition.
- A stale index, absent primary field, or owner replacement never returns or deletes a new owner.
- Create, successful stream arrival, stream rename, SSRC update, download completion, explicit stop, HTTP timeout, device BYE, media departure, MediaStatus 121, and orphan reconciliation leave primary and derived data consistent.
- Completed downloads retain the existing 15-minute `cleanupAt` behavior; active PLAY/PLAYBACK records do not receive a new fixed expiry.
- Legacy primary records remain queryable before index backfill completes; after completion, no point lookup falls back to a global primary Hash scan.

### Redis Command and Complexity

- Exact lookup executes one `HGET` and zero `HSCAN`/`HVALS` calls.
- Indexed partial lookup executes no `HSCAN`/`HVALS` against `VMP_INVITE`; work is bounded by candidates for that index key, not total InviteInfo count.
- `getStreamInfoCount` executes `SCARD` and does not deserialize all InviteInfo values.
- Each cleanup tick consumes at most the configured batch of due ZSet members; it does not execute `HVALS` on `VMP_INVITE`.
- Each active reconciliation queries ZLM at most once per affected media server per tick and reads only that server's active members.

### Tests and Validation

- Unit tests verify command selection with mocks: exact queries use `HGET`, indexed queries do not invoke primary `scan`/`values`, and the count uses `SCARD`.
- Redis integration tests cover transactions, stale indices, old-owner/new-owner races, stream/SSRC changes, backfill, and timeout/download transitions.
- A 10,000 and 100,000 record integration fixture runs 1,000 exact and partial lookups. Point-lookup Redis command counts must not grow with the primary Hash cardinality.
- Existing InviteInfo lifecycle, PlayService, Playback, Download, RTP lifecycle, and cleanup tests pass, along with `git diff --check`.

## Delivery Shape

Implementation is split into three independently reviewable commits:

1. Exact-field `HGET` lookup.
2. Channel, stream, and SSRC indexes with owner-safe write/delete behavior and backfill.
3. Active-media/deadline indexes and migration of scheduled reconciliation and receive counting.

Each commit leaves `VMP_INVITE` readable by the previous behavior and can be rolled back without deleting primary session state.
