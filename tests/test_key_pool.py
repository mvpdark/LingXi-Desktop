"""TDD: KeyPool comprehensive test coverage.

KeyPool is the critical API key rotation component shared by ImageService
and LLMService. These tests characterize its round-robin, failure-cooling,
and thread-safety behavior to prevent regressions.
"""
import sys
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from services.key_pool import KeyPool


class KeyPoolInitTest(unittest.TestCase):
    """Initialization and property tests."""

    def test_empty_keys_returns_none_and_unavailable(self):
        """Empty key list → available=False, get_next_key=None."""
        pool = KeyPool([])
        self.assertFalse(pool.available)
        self.assertEqual(pool.size, 0)
        self.assertIsNone(pool.get_next_key())

    def test_none_keys_treated_as_empty(self):
        """None key list → treated as empty."""
        pool = KeyPool(None)
        self.assertFalse(pool.available)
        self.assertEqual(pool.size, 0)

    def test_whitespace_keys_filtered(self):
        """Whitespace-only and empty keys are filtered out."""
        pool = KeyPool(["sk-aaa", "", "  ", "sk-bbb", "\t\n"])
        self.assertEqual(pool.size, 2)
        self.assertTrue(pool.available)

    def test_keys_are_stripped(self):
        """Keys are stripped of surrounding whitespace."""
        pool = KeyPool(["  sk-aaa  "])
        self.assertEqual(pool.get_next_key(), "sk-aaa")

    def test_current_key_none_before_first_call(self):
        """current_key is None before any get_next_key call."""
        pool = KeyPool(["sk-aaa"])
        self.assertIsNone(pool.current_key)


class KeyPoolRoundRobinTest(unittest.TestCase):
    """Round-robin cycling tests."""

    def test_single_key_always_returned(self):
        """Single key → always returns the same key."""
        pool = KeyPool(["sk-aaa"])
        for _ in range(5):
            self.assertEqual(pool.get_next_key(), "sk-aaa")

    def test_multiple_keys_cycle_round_robin(self):
        """Three keys → cycles through all three in order."""
        pool = KeyPool(["sk-aaa", "sk-bbb", "sk-ccc"])
        sequence = [pool.get_next_key() for _ in range(6)]
        self.assertEqual(sequence, ["sk-aaa", "sk-bbb", "sk-ccc",
                                     "sk-aaa", "sk-bbb", "sk-ccc"])

    def test_current_key_tracks_last_issued(self):
        """current_key returns the last key issued by get_next_key."""
        pool = KeyPool(["sk-aaa", "sk-bbb"])
        pool.get_next_key()
        self.assertEqual(pool.current_key, "sk-aaa")
        pool.get_next_key()
        self.assertEqual(pool.current_key, "sk-bbb")


class KeyPoolFailureTest(unittest.TestCase):
    """Failure cooling and recovery tests."""

    def test_mark_failed_skips_key(self):
        """Failed key is skipped in subsequent get_next_key calls."""
        pool = KeyPool(["sk-aaa", "sk-bbb"])
        pool.mark_failed("sk-aaa")
        # Should skip sk-aaa and return sk-bbb
        self.assertEqual(pool.get_next_key(), "sk-bbb")
        # Next call should still skip sk-aaa (within cooldown)
        self.assertEqual(pool.get_next_key(), "sk-bbb")

    def test_mark_success_restores_key(self):
        """mark_success clears failure and restores the key to rotation."""
        pool = KeyPool(["sk-aaa", "sk-bbb"])
        pool.mark_failed("sk-aaa")
        pool.mark_success("sk-aaa")
        # After recovery, both keys should be in rotation again
        keys_returned = {pool.get_next_key(), pool.get_next_key()}
        self.assertEqual(keys_returned, {"sk-aaa", "sk-bbb"})

    def test_all_keys_failed_degrades_to_first(self):
        """When all keys are in cooldown, degrades to returning the first key."""
        pool = KeyPool(["sk-aaa", "sk-bbb"])
        pool.mark_failed("sk-aaa")
        pool.mark_failed("sk-bbb")
        key = pool.get_next_key()
        self.assertEqual(key, "sk-aaa")

    def test_mark_failed_empty_key_is_noop(self):
        """Calling mark_failed with empty string is a no-op."""
        pool = KeyPool(["sk-aaa"])
        pool.mark_failed("")
        self.assertEqual(pool.get_next_key(), "sk-aaa")

    def test_mark_success_unknown_key_is_noop(self):
        """mark_success with a key not in _failed is a safe no-op."""
        pool = KeyPool(["sk-aaa"])
        pool.mark_success("sk-unknown")  # should not raise
        self.assertEqual(pool.get_next_key(), "sk-aaa")


class KeyPoolCooldownExpiryTest(unittest.TestCase):
    """Cooldown expiry via mocked time.time()."""

    @patch("services.key_pool.time")
    def test_cooldown_expiry_restores_key(self, mock_time):
        """Key is restored after COOLDOWN_SECONDS elapses."""
        mock_time.time.return_value = 1000.0
        pool = KeyPool(["sk-aaa", "sk-bbb"])
        pool.mark_failed("sk-aaa")
        # Key should be skipped at t=1000
        self.assertEqual(pool.get_next_key(), "sk-bbb")
        # Advance time past cooldown (60s + 1s)
        mock_time.time.return_value = 1061.0
        # Key should be restored and part of rotation again
        keys_returned = set()
        for _ in range(4):
            keys_returned.add(pool.get_next_key())
        self.assertIn("sk-aaa", keys_returned)

    @patch("services.key_pool.time")
    def test_cooldown_not_yet_expired_skips_key(self, mock_time):
        """Key is still skipped just before cooldown expires."""
        mock_time.time.return_value = 1000.0
        pool = KeyPool(["sk-aaa", "sk-bbb"])
        pool.mark_failed("sk-aaa")
        # At t=1059 (59s later, within 60s cooldown)
        mock_time.time.return_value = 1059.0
        self.assertEqual(pool.get_next_key(), "sk-bbb")


class KeyPoolAuthHeaderTest(unittest.TestCase):
    """get_auth_header format tests."""

    def test_returns_correct_header_format(self):
        """get_auth_header returns {"Authorization": "Bearer <key>"}."""
        pool = KeyPool(["sk-aaa"])
        header = pool.get_auth_header()
        self.assertEqual(header, {"Authorization": "Bearer sk-aaa"})

    def test_empty_pool_returns_empty_dict(self):
        """Empty pool → get_auth_header returns {}."""
        pool = KeyPool([])
        self.assertEqual(pool.get_auth_header(), {})

    def test_auth_header_cycles_keys(self):
        """get_auth_header cycles through keys like get_next_key."""
        pool = KeyPool(["sk-aaa", "sk-bbb"])
        h1 = pool.get_auth_header()
        h2 = pool.get_auth_header()
        self.assertEqual(h1, {"Authorization": "Bearer sk-aaa"})
        self.assertEqual(h2, {"Authorization": "Bearer sk-bbb"})


class KeyPoolStatsTest(unittest.TestCase):
    """get_stats correctness tests."""

    def test_initial_stats(self):
        """Initial stats: all keys available, none failed."""
        pool = KeyPool(["sk-aaa", "sk-bbb", "sk-ccc"])
        stats = pool.get_stats()
        self.assertEqual(stats["total"], 3)
        self.assertEqual(stats["available"], 3)
        self.assertEqual(stats["failed"], 0)

    def test_stats_after_failure(self):
        """After mark_failed, available decreases and failed increases."""
        pool = KeyPool(["sk-aaa", "sk-bbb", "sk-ccc"])
        pool.mark_failed("sk-aaa")
        stats = pool.get_stats()
        self.assertEqual(stats["total"], 3)
        self.assertEqual(stats["available"], 2)
        self.assertEqual(stats["failed"], 1)

    def test_stats_after_recovery(self):
        """After mark_success, available is restored."""
        pool = KeyPool(["sk-aaa", "sk-bbb"])
        pool.mark_failed("sk-aaa")
        pool.mark_success("sk-aaa")
        stats = pool.get_stats()
        self.assertEqual(stats["available"], 2)
        self.assertEqual(stats["failed"], 0)

    def test_stats_empty_pool(self):
        """Empty pool stats: total=0, available=0, failed=0."""
        pool = KeyPool([])
        stats = pool.get_stats()
        self.assertEqual(stats["total"], 0)
        self.assertEqual(stats["available"], 0)
        self.assertEqual(stats["failed"], 0)


class KeyPoolThreadSafetyTest(unittest.TestCase):
    """Thread safety tests."""

    def test_concurrent_get_next_key_returns_valid_keys(self):
        """Concurrent calls from multiple threads return only valid keys."""
        keys = ["sk-aaa", "sk-bbb", "sk-ccc"]
        pool = KeyPool(keys)
        results = []
        lock = threading.Lock()

        def worker():
            for _ in range(100):
                k = pool.get_next_key()
                with lock:
                    results.append(k)

        threads = [threading.Thread(target=worker) for _ in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        # All returned keys must be from the original set
        for k in results:
            self.assertIn(k, keys)

    def test_concurrent_mark_failed_and_get_next_key(self):
        """Concurrent mark_failed and get_next_key do not crash."""
        keys = ["sk-aaa", "sk-bbb", "sk-ccc", "sk-ddd"]
        pool = KeyPool(keys)
        errors = []

        def producer():
            try:
                for _ in range(50):
                    k = pool.get_next_key()
                    if k:
                        pool.mark_failed(k)
                        pool.mark_success(k)
            except Exception as e:
                errors.append(e)

        def consumer():
            try:
                for _ in range(50):
                    pool.get_stats()
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=producer) for _ in range(3)]
        threads += [threading.Thread(target=consumer) for _ in range(2)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
