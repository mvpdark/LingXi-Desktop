"""LLM API 公共工具函数。

抽取 SSE 流式解析和 API 响应文本提取的公共逻辑，
消除 agent_orchestrator / llm_service / memory_manager 中的重复代码。
"""

from __future__ import annotations

import json
from typing import AsyncGenerator


def extract_response_text(data: dict) -> str:
    """从 API 响应中提取文本（兼容 Responses API 和 Chat Completions 格式）。

    优先解析 Responses API 格式: output[].content[].text
    回退到 Chat Completions 格式: choices[].message.content

    参数:
        data: API 返回的 JSON 字典

    返回:
        提取到的文本，未找到则返回空字符串
    """
    text = ""
    output = data.get("output", [])
    if output:
        for item in output:
            for content in item.get("content", []):
                text += content.get("text", "")

    if not text:
        choices = data.get("choices", [])
        if choices:
            for choice in choices:
                msg = choice.get("message", {})
                content = msg.get("content", "")
                if content:
                    text += content

    return text


async def parse_sse_stream(
    response,
) -> AsyncGenerator[str, None]:
    """解析 SSE 流式响应，yield 增量文本。

    兼容两种格式：
    - Responses API: response.output_text.delta 事件
    - Chat Completions: choices[0].delta.content 字段

    在遇到 [DONE] 或 response.completed 时停止。

    参数:
        response: httpx 流式响应对象（已进入 context manager）

    Yields:
        增量文本片段
    """
    async for line in response.aiter_lines():
        if not line or not line.startswith("data: "):
            continue

        data_str = line[6:]  # 去掉 "data: " 前缀

        if data_str.strip() == "[DONE]":
            break

        try:
            event = json.loads(data_str)
        except json.JSONDecodeError:
            continue

        event_type = event.get("type", "")

        if event_type == "response.completed":
            break

        # Responses API 格式
        if event_type == "response.output_text.delta":
            delta = event.get("delta", "")
            if delta:
                yield delta
            continue

        # Chat Completions 格式
        choices = event.get("choices", [])
        if choices:
            delta = choices[0].get("delta", {})
            content = delta.get("content", "")
            if content:
                yield content
