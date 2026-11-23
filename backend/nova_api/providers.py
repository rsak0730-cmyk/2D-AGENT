"""Verified provider adapters; dynamic model IDs are always obtained from provider APIs."""
import httpx
from fastapi import HTTPException
from .config import get_settings

OPENAI_COMPAT = {
    "arena": None,
    "openai": "https://api.openai.com/v1",
    "groq": "https://api.groq.com/openai/v1",
    "openrouter": "https://openrouter.ai/api/v1",
}


def _gateway_base(endpoint: str | None) -> str:
    """Accept only explicitly allow-listed HTTPS gateway origins to prevent SSRF."""
    from urllib.parse import urlsplit
    if not endpoint:
        raise HTTPException(422, "A compatible gateway requires an HTTPS base URL")
    try:
        parsed = urlsplit(endpoint.strip())
        port = parsed.port
    except ValueError:
        raise HTTPException(422, "Gateway base URL is invalid")
    allowed = {host.strip().lower() for host in get_settings().gateway_allowed_hosts.split(",") if host.strip()}
    host = (parsed.hostname or "").lower()
    path = parsed.path.rstrip("/")
    if (parsed.scheme != "https" or not host or parsed.username or parsed.password or parsed.query or parsed.fragment
            or host not in allowed or (port is not None and port != 443)
            or path != "/v1"):
        raise HTTPException(422, "Gateway URL must use HTTPS, an allow-listed hostname, and the /v1 path")
    return endpoint.strip().rstrip("/")


def _provider_error(status: int, retry_after: str | None = None) -> HTTPException:
    if status == 429:
        return HTTPException(429, "AI provider rate limit reached", headers={"Retry-After": retry_after or "60"})
    if status in (401, 403):
        return HTTPException(502, "AI provider rejected configured credentials")
    if status == 402:
        return HTTPException(402, "AI provider reports insufficient balance or payment required")
    return HTTPException(502, f"AI provider request failed with status {status}")


def _base(provider: str, endpoint: str | None = None) -> str:
    if provider == "arena":
        return (endpoint or get_settings().provider_base_url).rstrip("/")
    if provider == "cloudflare":
        if not endpoint or not endpoint.startswith("https://api.cloudflare.com/client/v4/accounts/"):
            raise HTTPException(422, "Cloudflare requires its account-specific API base URL")
        return endpoint.rstrip("/")
    if provider in {"claude_gateway", "openai_compatible", "anthropic_compatible"}:
        return _gateway_base(endpoint)
    if provider == "anthropic":
        return "https://api.anthropic.com"
    if provider == "gemini":
        return "https://generativelanguage.googleapis.com/v1beta"
    if provider not in OPENAI_COMPAT:
        raise HTTPException(422, "Unsupported AI provider")
    return OPENAI_COMPAT[provider].rstrip("/")


async def _request(method: str, url: str, *, secret: str, provider: str, json_body=None):
    cfg = get_settings()
    try:
        headers = {"Content-Type": "application/json"}
        if provider in {"anthropic", "anthropic_compatible"}:
            headers.update({"x-api-key": secret, "anthropic-version": "2023-06-01"})
        elif provider == "gemini":
            headers["x-goog-api-key"] = secret
        else:
            headers["Authorization"] = f"Bearer {secret}"
        async with httpx.AsyncClient(timeout=cfg.request_timeout_seconds) as client:
            response = await client.request(method, url, headers=headers, json=json_body)
    except httpx.TimeoutException:
        raise HTTPException(504, "AI provider timed out")
    except httpx.RequestError:
        raise HTTPException(502, "AI provider is unreachable")
    if response.status_code >= 400:
        raise _provider_error(response.status_code, response.headers.get("Retry-After"))
    try:
        return response.json()
    except ValueError:
        raise HTTPException(502, "AI provider returned invalid JSON")


async def list_models(api_key: str, base_url: str | None = None, provider: str = "arena", account_id: str | None = None) -> list[str]:
    base = _base(provider, base_url)
    if provider == "cloudflare":
        if not account_id:
            raise HTTPException(422, "Cloudflare account ID is required")
        data = await _request("GET", f"{base}/ai/models/search?task=text-generation&per_page=100", secret=api_key, provider=provider)
        entries = data.get("result", []) if data.get("success") else []
        model_ids = [x.get("name") for x in entries if isinstance(x, dict) and isinstance(x.get("name"), str)]
    elif provider == "gemini":
        data = await _request("GET", base + "/models?pageSize=100", secret=api_key, provider=provider)
        entries = data.get("models", [])
        model_ids = []
        if isinstance(entries, list):
            for item in entries:
                if not isinstance(item, dict) or not isinstance(item.get("name"), str):
                    continue
                methods = item.get("supportedGenerationMethods", [])
                if isinstance(methods, list) and "generateContent" in methods:
                    model_ids.append(item["name"].removeprefix("models/"))
    else:
        path = "/v1/models" if provider == "anthropic" else "/models"
        data = await _request("GET", base + path, secret=api_key, provider=provider)
        entries = data.get("data", [])
        model_ids = [x.get("id") for x in entries if isinstance(x, dict) and isinstance(x.get("id"), str)] if isinstance(entries, list) else []
    if not model_ids:
        raise HTTPException(502, "AI provider returned no usable model IDs")
    return model_ids[:200]


async def complete_detailed(message: str, model: str | None = None, history: list[dict[str, str]] | None = None, api_key: str | None = None, base_url: str | None = None, provider: str = "arena", account_id: str | None = None, system: str | None = None, temperature: float = 0.2, context_length: int | None = None, max_tokens: int | None = None, include_finish_reason: bool = False):
    cfg = get_settings()
    secret = api_key or cfg.provider_api_key
    if not secret:
        raise HTTPException(503, "No AI provider key configured. Configure one in the app or set NOVA_PROVIDER_API_KEY on the backend.")
    chosen = model or cfg.model
    system_messages = [{"role": "system", "content": system}] if system else []
    base_messages = system_messages + [{"role": "user", "content": message}]
    limit = max(1000, min(cfg.max_prompt_characters, context_length * 4 if context_length else cfg.max_prompt_characters))
    required = sum(len(str(item.get("content", ""))) for item in base_messages)
    if required > limit:
        raise HTTPException(413, "System instructions and current request exceed the configured prompt-size limit")
    retained = []
    used = required
    for item in reversed(history or []):
        if item.get("role") not in {"user", "assistant"} or not isinstance(item.get("content"), str):
            continue
        size = len(item["content"])
        if used + size <= limit:
            retained.insert(0, item); used += size
    messages = system_messages + retained + [{"role": "user", "content": message}]
    output_limit = min(max_tokens or cfg.max_completion_tokens, cfg.max_completion_tokens)
    if provider == "gemini":
        import re
        model_name = chosen.removeprefix("models/")
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,160}", model_name):
            raise HTTPException(422, "Gemini model ID is invalid")
        contents = [{"role": "model" if item["role"] == "assistant" else "user", "parts": [{"text": item["content"]}]} for item in retained]
        contents.append({"role": "user", "parts": [{"text": message}]})
        request_body = {"contents": contents, "generationConfig": {"temperature": temperature, "maxOutputTokens": output_limit}}
        if system:
            request_body["systemInstruction"] = {"parts": [{"text": system}]}
        data = await _request("POST", f"{_base(provider, base_url)}/models/{model_name}:generateContent", secret=secret, provider=provider, json_body=request_body)
        try:
            candidate = data["candidates"][0]
            parts = candidate["content"]["parts"]
            text = "".join(part["text"] for part in parts if isinstance(part, dict) and isinstance(part.get("text"), str))
            served_model = str(data.get("modelVersion", model_name))
            usage = data.get("usageMetadata") or {}
            prompt_tokens = usage.get("promptTokenCount") if isinstance(usage, dict) else None
            completion_tokens = usage.get("candidatesTokenCount") if isinstance(usage, dict) else None
            finish_reason = candidate.get("finishReason")
        except (KeyError, IndexError, TypeError):
            raise HTTPException(502, "Gemini returned no usable text candidate")
        if not text:
            raise HTTPException(502, "Gemini returned no text content")
        result = (text, served_model, prompt_tokens if isinstance(prompt_tokens, int) else None, completion_tokens if isinstance(completion_tokens, int) else None)
        return (*result, finish_reason if isinstance(finish_reason, str) else None) if include_finish_reason else result
    if provider in {"anthropic", "anthropic_compatible"}:
        if temperature > 1:
            raise HTTPException(422, "Anthropic Messages API supports temperature no greater than 1")
        anthropic_messages = retained + [{"role": "user", "content": message}]
        request_body = {"model": chosen, "max_tokens": output_limit, "messages": anthropic_messages, "temperature": temperature}
        if system:
            request_body["system"] = system
        base = _base(provider, base_url)
        path = "/v1/messages" if provider == "anthropic" else "/messages"
        data = await _request("POST", base + path, secret=secret, provider=provider, json_body=request_body)
        try:
            blocks = data["content"]
            text = "\\n".join(block["text"] for block in blocks if isinstance(block, dict) and block.get("type") == "text" and isinstance(block.get("text"), str))
            served_model = str(data.get("model", chosen))
            usage = data.get("usage") or {}
            prompt_tokens = usage.get("input_tokens") if isinstance(usage, dict) else None
            completion_tokens = usage.get("output_tokens") if isinstance(usage, dict) else None
            finish_reason = data.get("stop_reason")
        except (KeyError, TypeError):
            raise HTTPException(502, "AI provider returned an invalid Anthropic Messages response")
        if not isinstance(text, str) or not text:
            raise HTTPException(502, "AI provider returned no text content; tool-use responses are not supported")
        result = (text, served_model, prompt_tokens if isinstance(prompt_tokens, int) else None, completion_tokens if isinstance(completion_tokens, int) else None)
        return (*result, finish_reason if isinstance(finish_reason, str) else None) if include_finish_reason else result
    if provider == "cloudflare":
        if not account_id:
            raise HTTPException(422, "Cloudflare account ID is required")
        data = await _request("POST", f"{_base(provider, base_url)}/ai/run/{chosen}", secret=secret, provider=provider, json_body={"messages": messages, "temperature": temperature, "max_tokens": output_limit})
        try:
            text = data["result"]["response"]
        except (KeyError, TypeError):
            raise HTTPException(502, "AI provider returned an invalid chat completion")
        result = (text, chosen, None, None)
        return (*result, None) if include_finish_reason else result
    data = await _request("POST", _base(provider, base_url) + "/chat/completions", secret=secret, provider=provider, json_body={"model": chosen, "messages": messages, "temperature": temperature, "max_tokens": output_limit})
    try:
        text = data["choices"][0]["message"]["content"]
        served_model = str(data.get("model", chosen))
        usage = data.get("usage") or {}
        prompt_tokens = usage.get("prompt_tokens") if isinstance(usage, dict) else None
        completion_tokens = usage.get("completion_tokens") if isinstance(usage, dict) else None
        finish_reason = data["choices"][0].get("finish_reason")
    except (KeyError, IndexError, TypeError):
        raise HTTPException(502, "AI provider returned an invalid chat completion")
    if not isinstance(text, str):
        raise HTTPException(502, "AI provider returned an unsupported response format")
    result = (text, served_model, prompt_tokens if isinstance(prompt_tokens, int) else None, completion_tokens if isinstance(completion_tokens, int) else None)
    return (*result, finish_reason if isinstance(finish_reason, str) else None) if include_finish_reason else result


async def complete(message: str, model: str | None = None, history: list[dict[str, str]] | None = None, api_key: str | None = None, base_url: str | None = None, provider: str = "arena", account_id: str | None = None, system: str | None = None, temperature: float = 0.2) -> tuple[str, str]:
    text, served_model, _, _ = await complete_detailed(message, model, history, api_key, base_url, provider, account_id, system, temperature)
    return text, served_model


def is_fallback_worthy(error: HTTPException) -> bool:
    return error.status_code in {429, 502, 503, 504}
