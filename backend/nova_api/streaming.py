"""Native upstream SSE handling for OpenAI Chat Completions and Anthropic Messages."""
from __future__ import annotations

import json
from typing import Any, AsyncIterator

import httpx
from fastapi import HTTPException

from .config import get_settings
from .providers import _base, _provider_error


class ProviderEventStream:
    """Opened upstream stream; close() always releases the HTTP connection."""
    def __init__(self, client: httpx.AsyncClient, response: httpx.Response, provider: str, model: str):
        self.client = client
        self.response = response
        self.provider = provider
        self.model = model
        self.input_tokens: int | None = None
        self.output_tokens: int | None = None
        self.finish_reason: str | None = None
        self.saw_text = False
        self.closed = False

    async def events(self) -> AsyncIterator[dict[str, Any]]:
        anthropic = self.provider in {"anthropic", "anthropic_compatible"}
        current_event = "message"
        async for line in self.response.aiter_lines():
            if line.startswith("event:"):
                current_event = line[6:].strip()
                continue
            if not line.startswith("data:"):
                if not line:
                    current_event = "message"
                continue
            payload = line[5:].strip()
            if not payload:
                continue
            if payload == "[DONE]":
                if not self.saw_text: raise HTTPException(502,"AI provider stream contained no text response")
                yield {"type": "finish", "reason": self.finish_reason}
                return
            try:
                data = json.loads(payload)
            except (ValueError, TypeError):
                raise HTTPException(502, "AI provider sent malformed server-sent events")
            if not isinstance(data, dict):
                continue
            if anthropic:
                if current_event == "error" or data.get("type") == "error":
                    raise HTTPException(502, "AI provider reported a streaming error")
                kind = data.get("type", current_event)
                if kind == "message_start":
                    message = data.get("message") or {}
                    self.model = str(message.get("model", self.model))
                    usage = message.get("usage") or {}
                    if isinstance(usage.get("input_tokens"), int): self.input_tokens = usage["input_tokens"]
                    yield {"type": "start", "input_tokens": self.input_tokens or 0}
                elif kind == "content_block_delta":
                    delta = data.get("delta") or {}
                    if delta.get("type") == "text_delta" and isinstance(delta.get("text"), str) and delta["text"]:
                        self.saw_text=True
                        yield {"type": "text", "text": delta["text"]}
                elif kind == "message_delta":
                    delta = data.get("delta") or {}
                    self.finish_reason = delta.get("stop_reason")
                    usage = data.get("usage") or {}
                    if isinstance(usage.get("output_tokens"), int): self.output_tokens = usage["output_tokens"]
                elif kind == "message_stop":
                    if not self.saw_text: raise HTTPException(502,"AI provider stream contained no text response")
                    yield {"type": "finish", "reason": self.finish_reason}
                    return
            else:
                if "error" in data:
                    raise HTTPException(502, "AI provider reported a streaming error")
                if isinstance(data.get("model"), str): self.model = data["model"]
                usage = data.get("usage")
                if isinstance(usage, dict):
                    if isinstance(usage.get("prompt_tokens"), int): self.input_tokens = usage["prompt_tokens"]
                    if isinstance(usage.get("completion_tokens"), int): self.output_tokens = usage["completion_tokens"]
                choices = data.get("choices")
                if isinstance(choices, list) and choices and isinstance(choices[0], dict):
                    choice = choices[0]
                    delta = choice.get("delta") or {}
                    content = delta.get("content")
                    if isinstance(content, str) and content:
                        self.saw_text=True
                        yield {"type": "text", "text": content}
                    elif isinstance(content, list):
                        for block in content:
                            if isinstance(block, dict) and block.get("type") == "text" and isinstance(block.get("text"), str) and block["text"]:
                                self.saw_text=True
                                yield {"type": "text", "text": block["text"]}
                    if choice.get("finish_reason") is not None:
                        self.finish_reason = str(choice["finish_reason"])
        yield {"type": "finish", "reason": self.finish_reason}
        if not self.saw_text: raise HTTPException(502,"AI provider stream contained no text response")
        yield {"type": "finish", "reason": self.finish_reason}

    async def close(self) -> None:
        if not self.closed:
            self.closed = True
            try: await self.response.aclose()
            finally: await self.client.aclose()


async def open_provider_stream(*, provider: str, secret: str, endpoint: str | None, model: str,
                               messages: list[dict[str, str]], temperature: float, max_tokens: int) -> ProviderEventStream:
    """Open (but do not buffer) an upstream provider SSE response."""
    if provider not in {"arena", "openai", "groq", "openrouter", "openai_compatible", "anthropic", "anthropic_compatible"}:
        raise HTTPException(422, "Streaming is not supported for this configured provider")
    anthropic = provider in {"anthropic", "anthropic_compatible"}
    base = _base(provider, endpoint)
    if anthropic:
        body: dict[str, Any] = {"model": model, "messages": [m for m in messages if m["role"] != "system"], "max_tokens": max_tokens, "temperature": temperature, "stream": True}
        system = "\n\n".join(m["content"] for m in messages if m["role"] == "system")
        if system: body["system"] = system
        path = "/v1/messages" if provider == "anthropic" else "/messages"
        headers = {"x-api-key": secret, "anthropic-version": "2023-06-01", "content-type": "application/json", "accept": "text/event-stream"}
    else:
        body = {"model": model, "messages": messages, "temperature": temperature, "max_tokens": max_tokens, "stream": True, "stream_options":{"include_usage":True}}
        path = "/chat/completions"
        headers = {"Authorization": f"Bearer {secret}", "content-type": "application/json", "accept": "text/event-stream"}
    timeout = httpx.Timeout(get_settings().request_timeout_seconds)
    client = httpx.AsyncClient(timeout=timeout)
    try:
        request = client.build_request("POST", base + path, headers=headers, json=body)
        response = await client.send(request, stream=True)
        if response.status_code >= 400:
            status = response.status_code
            retry_after = response.headers.get("Retry-After")
            await response.aclose()
            await client.aclose()
            raise _provider_error(status, retry_after)
        content_type = response.headers.get("content-type", "").lower()
        if "text/event-stream" not in content_type:
            await response.aclose(); await client.aclose()
            raise HTTPException(502, "AI provider did not return a server-sent event stream")
        return ProviderEventStream(client, response, provider, model)
    except httpx.TimeoutException:
        await client.aclose(); raise HTTPException(504, "AI provider timed out while opening its stream")
    except httpx.RequestError:
        await client.aclose(); raise HTTPException(502, "AI provider stream is unreachable")
    except Exception:
        if not client.is_closed: await client.aclose()
        raise
