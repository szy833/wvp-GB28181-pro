# RTP Departure Owner Design

## Goal

Close the two remaining RTP lifecycle gaps without expanding the existing rollback
scope: notify callers when a stream leaves before media arrival, and prevent a
delayed departure event from closing a newer RTP resource that reused the same
business stream.

## Design

`MediaDepartureEvent` carries the ZLM `originUrl` when it is created from the
ZLM `on_stream_changed` hook. `RtpServerServiceImpl` extracts the actual RTP
stream identifier from that URL and resolves the resource by its ZLM owner key.
The event stream may be either the published business stream (when
`stream_replace` is used) or the actual ZLM stream (when it is not); in both
cases the ZLM owner key must match. The business-stream index remains a
fallback only when the event stream is also the resource's ZLM stream
(proxy/non-replaced stream). If a replaced RTP stream cannot be correlated, the
service does not close an unrelated current owner.

When a matching context is still `REGISTERING` or `WAITING_MEDIA`, the service
completes it as a failure so the existing callback is delivered exactly once and
cleanup runs before that callback. A context already in `SUCCESS` is closed as
before, releasing RTP, authentication, SSRC, and owner indexes.

## Scope

- Modify `MediaDepartureEvent` to preserve ZLM `originUrl`.
- Add an explicit departure/failure transition to `RtpResourceContext`.
- Add owner-aware departure lookup in `RtpServerServiceImpl`.
- Add focused tests for waiting-state departure, successful departure cleanup,
  and stale departure isolation.
- Do not modify JT1078 callback batching, Redis authentication migration,
  BYE handling, or unrelated Hook/DynamicTask compatibility behavior.

## Compatibility

Events without an origin URL continue to work for resources whose event stream
is also the actual ZLM stream. Replaced RTP streams without a correlation value
are left for their normal timeout/explicit close path rather than risking a
new owner's resources.
