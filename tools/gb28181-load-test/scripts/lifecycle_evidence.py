"""Resource-only terminal evidence, independent of a workload error verdict."""
import time


def terminal_clean(snap, count):
    zero = ('active', 'pending', 'playing', 'ssrcReservations', 'sipPending', 'globalSip',
            'mediaEntries', 'listeners', 'deviceIndexMembers')
    return (all(snap.get(key) == 0 for key in zero)
            and all(row['lifecycle'] == 'CLOSED' and row['owner'] is None
                    and not row['jobFields'] and not row['leasePresent']
                    and row['streamIndexMembers'] == (1 if row.get('type') == 'DOWNLOAD' else 0)
                    and not row['ssrcIndexMembers'] and not row['channelIndexMembers']
                    for row in snap['records'])
            and len(snap['sql']) == count
            and all(row['state'] == 'CLOSED' and row['stream'] is None
                    and row['seq'] == row['epoch'] for row in snap['sql']))


def observe_terminal(snapshot, count, known_count, workload_passed=False, budget=70, stable=10):
    """The supplied snapshot must be read-only and not gated by protocol counters."""
    started = time.monotonic()
    first = None
    result = {'cleanupVerified': False}
    while True:
        try:
            snap = snapshot()
            result['postExit'] = snap
            clean = terminal_clean(snap, count) and len(snap['records']) == known_count()
        except Exception as error:
            result['terminalEvidence'] = {'available': False, 'error': type(error).__name__}
            return result
        if clean:
            if first is None:
                first = time.monotonic()
            if workload_passed or time.monotonic() - first >= stable:
                result['cleanupVerified'] = True
                result['terminalEvidence'] = {'available': True, 'seconds': round(time.monotonic()-started, 3),
                                              'stableSeconds': round(time.monotonic()-first, 3)}
                return result
        else:
            first = None
        if time.monotonic() - started >= budget:
            result['terminalEvidence'] = {'available': True, 'timedOut': True,
                                          'seconds': round(time.monotonic()-started, 3)}
            return result
        time.sleep(2)
