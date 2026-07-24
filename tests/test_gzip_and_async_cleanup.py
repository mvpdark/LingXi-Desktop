"""TDD tests for GZipMiddleware and async cleanup optimization.

1. GZipMiddleware: server.py should add GZipMiddleware to compress
   large base64 image responses (1-5MB each), reducing bandwidth.

2. Async cleanup: _cleanup_loop should use asyncio.to_thread() to
   avoid blocking the event loop during file I/O operations.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))


class GZipMiddlewareTest(unittest.TestCase):
    """TDD: server.py should add GZipMiddleware for response compression."""

    def setUp(self):
        server_path = Path(__file__).resolve().parents[1] / "src" / "server.py"
        self.source = server_path.read_text(encoding="utf-8")

    def test_imports_gzip_middleware(self):
        """server.py should import GZipMiddleware."""
        self.assertIn("GZipMiddleware", self.source)

    def test_adds_gzip_middleware_to_app(self):
        """server.py should call app.add_middleware(GZipMiddleware, ...)."""
        self.assertIn("app.add_middleware(GZipMiddleware", self.source)

    def test_gzip_minimum_size_configured(self):
        """GZipMiddleware should have minimum_size configured."""
        for line in self.source.splitlines():
            if "GZipMiddleware" in line and "add_middleware" in line:
                self.assertIn("minimum_size", line)
                return
        self.fail("GZipMiddleware add_middleware line not found")


class AsyncCleanupTest(unittest.TestCase):
    """TDD: _cleanup_loop should use asyncio.to_thread for file I/O."""

    def setUp(self):
        server_path = Path(__file__).resolve().parents[1] / "src" / "server.py"
        self.source = server_path.read_text(encoding="utf-8")

    def test_cleanup_loop_uses_to_thread(self):
        """_cleanup_loop should call _cleanup_old_assets via asyncio.to_thread."""
        self.assertIn("to_thread", self.source)

    def test_cleanup_old_assets_called_via_to_thread(self):
        """The cleanup call should be wrapped in asyncio.to_thread."""
        lines = self.source.splitlines()
        in_cleanup_loop = False
        found_to_thread = False
        for i, line in enumerate(lines):
            if "_cleanup_loop" in line and "def " in line:
                in_cleanup_loop = True
            elif in_cleanup_loop:
                if "_cleanup_old_assets" in line:
                    if "to_thread" in line:
                        found_to_thread = True
                    break
        self.assertTrue(
            found_to_thread,
            "_cleanup_loop should call _cleanup_old_assets via asyncio.to_thread"
        )


if __name__ == "__main__":
    unittest.main()
