"""TDD tests: WebDAVService should reuse a shared httpx.AsyncClient."""
import asyncio
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from services.webdav_service import WebDAVService


class WebDAVSharedClientTest(unittest.TestCase):
    """TDD: WebDAVService should reuse a shared httpx.AsyncClient."""

    def _make_service(self, **kwargs):
        defaults = dict(
            base_url='http://example.com/dav',
            username='user',
            password='pass',
        )
        defaults.update(kwargs)
        return WebDAVService(**defaults)

    def test_client_starts_none(self):
        svc = self._make_service()
        self.assertIsNone(svc._client)

    def test_get_client_creates_lazy_instance(self):
        svc = self._make_service()
        client = asyncio.run(svc._get_client())
        self.assertIsNotNone(client)
        self.assertFalse(client.is_closed)

    def test_get_client_returns_same_instance(self):
        svc = self._make_service()
        c1 = asyncio.run(svc._get_client())
        c2 = asyncio.run(svc._get_client())
        self.assertIs(c1, c2)

    def test_close_resets_client_to_none(self):
        svc = self._make_service()
        asyncio.run(svc._get_client())
        asyncio.run(svc.close())
        self.assertIsNone(svc._client)

    def test_close_idempotent(self):
        svc = self._make_service()
        asyncio.run(svc.close())
        self.assertIsNone(svc._client)

    def test_auth_type_change_recreates_client(self):
        svc = self._make_service(auth_type='basic')
        c1 = asyncio.run(svc._get_client())
        svc._auth_type = 'digest'
        c2 = asyncio.run(svc._get_client())
        self.assertIsNot(c1, c2)
        self.assertFalse(c2.is_closed)

    def test_has_get_client_method(self):
        svc = self._make_service()
        self.assertTrue(hasattr(svc, '_get_client'))
        self.assertTrue(callable(getattr(svc, '_get_client')))

    def test_get_client_after_close_creates_fresh(self):
        svc = self._make_service()
        c1 = asyncio.run(svc._get_client())
        asyncio.run(svc.close())
        c2 = asyncio.run(svc._get_client())
        self.assertIsNot(c1, c2)
        self.assertFalse(c2.is_closed)


class WebDAVServiceBasicTest(unittest.TestCase):
    """Smoke tests that do not require a real WebDAV server."""

    def test_disabled_when_no_base_url(self):
        svc = WebDAVService(base_url='', username='u', password='p')
        self.assertFalse(svc.enabled)

    def test_enabled_with_base_url(self):
        svc = WebDAVService(base_url='http://ex.com/dav', username='u', password='p')
        self.assertTrue(svc.enabled)

    def test_sanitize_username(self):
        self.assertEqual(WebDAVService.sanitize_username('alice'), 'alice')
        self.assertEqual(WebDAVService.sanitize_username(''), 'anonymous')
        self.assertEqual(WebDAVService.sanitize_username('a/b'), 'a_b')

    def test_subdir_for(self):
        self.assertEqual(WebDAVService.subdir_for('upload_1.png'), 'uploads')
        self.assertEqual(WebDAVService.subdir_for('generated_1.png'), 'generated')
        self.assertEqual(WebDAVService.subdir_for('rembg_1.png'), 'generated')


if __name__ == '__main__':
    unittest.main()
