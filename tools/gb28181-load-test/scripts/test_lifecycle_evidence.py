import unittest
from unittest.mock import patch

from lifecycle_evidence import observe_terminal, terminal_clean


class TerminalEvidenceTest(unittest.TestCase):
    def clean(self):
        snap = dict.fromkeys(('active', 'pending', 'playing', 'ssrcReservations', 'sipPending',
                             'globalSip', 'mediaEntries', 'listeners', 'deviceIndexMembers'), 0)
        snap.update({'records': [{'lifecycle': 'CLOSED', 'owner': None, 'jobFields': 0, 'leasePresent': False,
                                 'streamIndexMembers': 0, 'ssrcIndexMembers': 0, 'channelIndexMembers': 0}],
                     'sql': [{'state': 'CLOSED', 'stream': None, 'seq': 3, 'epoch': 3}]})
        return snap

    def test_failures_do_not_block_cleanup_evidence(self):
        clock = [0]
        with patch('lifecycle_evidence.time.monotonic', side_effect=lambda: clock[0]), \
             patch('lifecycle_evidence.time.sleep', side_effect=lambda _: clock.__setitem__(0, clock[0]+11)):
            result = observe_terminal(self.clean, 1, lambda: 1)
        self.assertTrue(result['cleanupVerified'])
        self.assertTrue(result['terminalEvidence']['available'])
        self.assertIn('postExit', result)
        self.assertNotIn('passed', result)

    def test_unavailable_not_faked_as_zero(self):
        def fail(): raise OSError('unavailable')
        result = observe_terminal(fail, 1, lambda: 1)
        self.assertFalse(result['cleanupVerified'])
        self.assertFalse(result['terminalEvidence']['available'])
        self.assertNotIn('postExit', result)

    def test_residual_and_projection_mismatch(self):
        snap = self.clean(); snap['pending'] = 1
        self.assertFalse(terminal_clean(snap, 1))
        snap = self.clean(); snap['sql'][0]['seq'] = 4
        self.assertFalse(terminal_clean(snap, 1))
        self.assertFalse(terminal_clean(self.clean(), 2))

    def test_unknown_generation_cannot_pass(self):
        result = observe_terminal(self.clean, 1, lambda: 2, workload_passed=True, budget=0)
        self.assertFalse(result['cleanupVerified'])
        self.assertTrue(result['terminalEvidence']['timedOut'])


if __name__ == '__main__':
    unittest.main()
