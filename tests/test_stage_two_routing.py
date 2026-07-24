import asyncio
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from services.agent_orchestrator import AgentOrchestrator, AgentResult


class StageTwoRoutingTest(unittest.TestCase):
    def setUp(self):
        self.orchestrator = AgentOrchestrator("https://example.test", ["key"])

    def test_global_adjustments_use_light_pipeline(self):
        for request in ("整体调色", "提高亮度和对比度", "清晰一点", "降噪", "简单电影风格"):
            with self.subTest(request=request):
                self.assertEqual(self.orchestrator._classify_image_edit(request), "light")

    def test_targeted_edits_use_standard_pipeline(self):
        for request in ("局部提亮脸部", "使用蒙版处理", "换背景", "删除路人", "增加一只猫", "精修人物眼睛"):
            with self.subTest(request=request):
                self.assertEqual(self.orchestrator._classify_image_edit(request), "standard")

    def test_retry_only_whitelisted_errors_and_honors_call_limit(self):
        retryable = AgentResult("专家", "key", "model", False, "", "HTTP 429")
        success = AgentResult("专家", "key", "model", True, "ok")
        self.orchestrator.run_sub_agent = AsyncMock(side_effect=[retryable, success])
        with unittest.mock.patch("services.agent_orchestrator.asyncio.sleep", new=AsyncMock()):
            result = asyncio.run(self.orchestrator.run_sub_agent_with_retry("key", "request", max_retries=9))
        self.assertTrue(result.success)
        self.assertEqual(self.orchestrator.run_sub_agent.await_count, 2)

        self.orchestrator.run_sub_agent.reset_mock(side_effect=True)
        self.orchestrator.run_sub_agent.side_effect = [
            AgentResult("专家", "key", "model", False, "", "HTTP 400 bad request"), success,
        ]
        result = asyncio.run(self.orchestrator.run_sub_agent_with_retry("key", "request", max_retries=9))
        self.assertFalse(result.success)
        self.assertEqual(self.orchestrator.run_sub_agent.await_count, 1)

    def test_retry_rejects_auth_errors(self):
        for error in ('HTTP 401 unauthorized', 'HTTP 403 forbidden'):
            with self.subTest(error=error):
                self.assertFalse(self.orchestrator._is_retryable_error(error))

    def test_retry_rejects_bad_request_and_unprocessable(self):
        # 400/422 属请求级错误，换 key 无意义，不应重试
        for error in ('HTTP 400 bad request', 'HTTP 422 unprocessable entity'):
            with self.subTest(error=error):
                self.assertFalse(self.orchestrator._is_retryable_error(error))

    def test_retry_accepts_network_429_and_all_5xx(self):
        for error in ('ConnectError: network unreachable', 'HTTP 429', 'HTTP 500', 'HTTP 599'):
            with self.subTest(error=error):
                self.assertTrue(self.orchestrator._is_retryable_error(error))

    def test_image_request_does_not_retry_auth_errors(self):
        request = httpx.Request('POST', 'https://example.test/v1/images/edits')
        first = httpx.Response(401, request=request)
        second = httpx.Response(200, request=request, json={'data': [{'url': 'ok'}]})
        client = AsyncMock()
        client.post = AsyncMock(side_effect=[first, second])
        self.orchestrator._get_client = AsyncMock(return_value=client)
        with self.assertRaises(httpx.HTTPStatusError):
            asyncio.run(self.orchestrator._request_image_edit(headers={}, files={}, data={}))
        self.assertEqual(client.post.await_count, 1)

    def test_light_pipeline_skips_analysis_and_prompt_llm(self):
        self.orchestrator._analyze_edit_request = AsyncMock()
        self.orchestrator._build_image_prompt = AsyncMock()
        self.orchestrator._request_image_edit = AsyncMock(return_value={"data": [{"url": "https://example.test/a.png"}]})
        result = asyncio.run(self.orchestrator.run_image_generator(
            "整体调色并提高亮度", reference_image="aW1hZ2U=", edit_tier="light",
        ))
        self.assertTrue(result.success)
        self.orchestrator._analyze_edit_request.assert_not_awaited()
        self.orchestrator._build_image_prompt.assert_not_awaited()
        self.orchestrator._request_image_edit.assert_awaited_once()
        sent_model = self.orchestrator._request_image_edit.await_args.kwargs['data']['model']
        self.assertEqual(sent_model, self.orchestrator.image_models['light'])

    def test_normal_image_edit_dispatches_only_image_enhancer(self):
        self.orchestrator.run_image_generator = AsyncMock(return_value=AgentResult(
            "图效师", "image_enhancer", "gpt-image-2", True,
            "[IMAGE]https://example.test/a.png[/IMAGE]",
        ))
        async def collect():
            return [event async for event in self.orchestrator.run_stream("整体调色", image_data="aW1hZ2U=")]
        events = asyncio.run(collect())
        dispatch = next(event for event in events if event.type == "dispatch")
        self.assertEqual([item[0] for item in dispatch.agents_dispatched], ["image_enhancer"])
        self.assertEqual(self.orchestrator.run_image_generator.await_args.kwargs["edit_tier"], "light")

    def test_text_to_image_does_not_start_unused_expert_tasks(self):
        self.orchestrator.route = AsyncMock(return_value={
            'agents': ['color_grader', 'image_enhancer'],
            'reason': '出图', 'complexity': 'standard', 'needs_search': False,
        })
        self.orchestrator.run_sub_agent_with_retry = AsyncMock()
        self.orchestrator.run_image_generator = AsyncMock(return_value=AgentResult(
            '图效师', 'image_enhancer', 'gpt-image-2', True,
            '[IMAGE]https://example.test/a.png[/IMAGE]'))
        async def collect():
            return [event async for event in self.orchestrator.run_stream('生成一张电影感照片')]
        events = asyncio.run(collect())
        dispatch = next(event for event in events if event.type == 'dispatch')
        self.assertEqual([item[0] for item in dispatch.agents_dispatched], ['image_enhancer'])
        self.orchestrator.run_sub_agent_with_retry.assert_not_awaited()

    def test_collaboration_keeps_full_expert_chain(self):
        self.orchestrator.run_vision_agent = AsyncMock(return_value=AgentResult(
            "照片分析师", "photo_analyst", "qwen-vl-max", True, "analysis"))
        self.orchestrator.run_sub_agent_with_retry = AsyncMock(side_effect=lambda key, *args, **kwargs: AgentResult(
            key, key, "model", True, "advice"))
        self.orchestrator.run_image_generator = AsyncMock(return_value=AgentResult(
            "图效师", "image_enhancer", "gpt-image-2", True,
            "[IMAGE]https://example.test/a.png[/IMAGE]"))
        async def collect():
            return [event async for event in self.orchestrator.run_collaborative_stream("协作修图", "aW1hZ2U=")]
        events = asyncio.run(collect())
        dispatch = next(event for event in events if event.type == "dispatch")
        self.assertEqual([item[0] for item in dispatch.agents_dispatched], [
            "composition_advisor", "color_grader", "lighting_analyst"])


if __name__ == "__main__":
    unittest.main()
