# SSRC Allocation Lifecycle Design

**Date:** 2026-07-30  
**Status:** Approved for implementation

## Goal

Close the remaining SSRC lifecycle gaps without changing SIP payloads, ZLM REST contracts, Redis key formats, or unrelated RTP business behavior:

1. Release the SSRC lease used by GB28181 voice talk on every terminal path.
2. Prevent duplicate SSRCs when random SSRC mode is enabled.
3. Stop automatic allocation while ZLM occupancy state is unknown and log the reason.

## Scope and Boundaries

- Modify `SSRCFactory` allocation, lease, and reconciliation state only as needed for SSRC ownership.
- Modify `PlayServiceImpl.talk()/stopTalk()` and its lifecycle data so the talk SSRC has an owner-aware release path.
- Add focused tests for deterministic release, random collision handling, reconciliation failure, and talk terminal cleanup.
- Do not alter SSRC wire formatting, SIP command structure, ZLM API signatures, business stream naming, or non-RTP allocation factories.
- Preset SSRCs remain externally owned and are not released by `SSRCFactory`.

## Design

### 1. Owner-aware allocation

Every internally generated SSRC, including random-mode SSRCs, is represented by an owned `SsrcLease`. Allocation is serialized per media server. Deterministic mode keeps the existing ten-thousand-slot pool; random mode chooses random free candidates from the same pool and records the lease in `activeLeases`. `release()` removes the lease by token and clears the corresponding suffix exactly once.

Compatibility methods remain available for callers that cannot carry a lease, but in-repository production callers are migrated to lease-returning methods. A compatibility allocation is treated as a deliberate legacy boundary and is not used for new lifecycle-managed flows.

### 2. Talk lifecycle

`PlayServiceImpl.talk()` stores the allocated lease in the per-talk `SendRtpInfo` lifecycle state. All exits after allocation call one idempotent release helper: send-port creation failure, passive-start failure, SIP command failure, timeout, device BYE/media departure, and explicit `stopTalk()`. The lease is released independently of ZLM or SIP cleanup failures.

### 3. Fail-closed ZLM reconciliation

Each media server has a reconciliation state: `UNKNOWN`, `READY`, or `FAILED`. Automatic allocation is allowed only in `READY`. The first successful ZLM media-list reconciliation transitions to `READY`; query exceptions, non-success results, null/invalid data, or a structurally unusable list transition to `FAILED` and retain the last trusted occupancy snapshot. Allocation attempts in `UNKNOWN`/`FAILED` return unavailable and emit a warning containing the media server id and reconciliation state. A later successful reconciliation logs recovery and re-enables allocation.

Known active leases are always merged into a successful snapshot, so an in-process resource cannot be freed by reconciliation. Random-mode nodes are no longer skipped: their active leases and ZLM-observed RTP listeners are reconciled using the same ownership rules.

## Lifecycle and Error Handling

- Lease release is idempotent and owner-token based; duplicate terminal callbacks cannot clear a newer lease.
- Reconciliation failure never clears the last trusted snapshot and never permits a new automatically generated SSRC.
- ZLM query recovery is observable through a single state-transition log; repeated failures are rate-limited to the existing scheduler cadence.
- Talk cleanup catches and logs release failures after attempting SIP, ZLM, and Redis cleanup, so one remote failure cannot prevent local lease release.

## Verification

- Unit tests prove random allocations are unique under concurrent calls and become reusable after release.
- Unit tests prove allocation is rejected with a log-visible failure while reconciliation is not ready, then succeeds after a valid snapshot.
- Unit tests cover the startup race where the first reconciliation fails before ZLM is online and the online event triggers a short-delayed recovery after node state persistence.
- Unit tests prove talk lease release on each terminal path and idempotent repeated stop.
- Existing RTP lifecycle tests remain unchanged except for required lease injection.
- Real ZLM integration remains required to verify online-event ordering, `getMediaList` completeness, and listener behavior; this design does not treat unit tests as proof of server-side occupancy. Record the environment and risk before scheduling that test because it requires a reachable ZLM node and active GB28181 RTP traffic.
- A successful ZLM response with `code=0` and omitted `data` is normalized to an empty media snapshot, matching the existing media-list service behavior; non-success responses and invalid structures remain fail-closed.
- Play timeout and SIP failure callbacks must validate the RTP `resourceId`/SSRC owner before notifying callbacks or deleting InviteInfo/SIP session indexes, so an old request cannot terminate a replacement play.
