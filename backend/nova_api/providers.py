import httpx
from fastapi import HTTPException
from .config import get_settings


def _provider_error(status: int, retry_after: str | None = None) -> HTTPException:
    if status == 429:
        return HTTPException(429, "AI provider rate limit reached", headers={"Retry-After": retry_after or "60"})
    if status in (401, 403):
        return HTTPException(502, "AI provider rejected configured credentials")
    if status == 402:
        return HTTPException(402, "AI provider reports insufficient balance or payment required")
    return HTTPException(502, f"AI provider request failed with status {status}")

async def list_models(api_key: str, base_url: str | None = None) -> list[str]:
    cfg = get_settings()
    url = (base_url or cfg.provider_base_url).rstrip("/") + "/models"
    try:
        async with httpx.AsyncClient(timeout=cfg.request_timeout_seconds) as client:
            response = await client.get(url, headers={"Authorization": f"Bearer {api_key}"})
    except httpx.TimeoutException:
        raise HTTPException(504, "AI provider timed out")
    except httpx.RequestError:
        raise HTTPException(502, "AI provider is unreachable")
    if response.status_code >= 400:
        raise _provider_error(response.status_code, response.headers.get("Retry-After"))
    try:
        data = response.json()["data"]
        model_ids = [item["id"] for item in data if isinstance(item, dict) and isinstance(item.get("id"), str)]
    except (ValueError, KeyError, TypeError):
        raise HTTPException(502, "AI provider returned an invalid model list")
    if not model_ids:
        raise HTTPException(502, "AI provider returned no usable model IDs")
    return model_ids[:200]

async def complete(message: str, model: str | None = None, history: list[dict[str, str]] | None = None, api_key: str | None = None, base_url: str | None = None) -> tuple[str, str]:
    cfg = get_settings()
    secret = api_key or cfg.provider_api_key
    if not secret:
        raise HTTPException(503, "No AI provider key configured. Configure one in the app or set NOVA_PROVIDER_API_KEY on the backend.")
    chosen = model or cfg.model
    url = (base_url or cfg.provider_base_url).rstrip("/") + "/chat/completions"
    headers = {"Authorization": f"Bearer {secret}", "Content-Type": "application/json"}
    body = {"model": chosen, "messages": [*(history or []), {"role": "user", "content": message}]}
    try:
        async with httpx.AsyncClient(timeout=cfg.request_timeout_seconds) as client:
            response = await client.post(url, headers=headers, json=body)
    except httpx.TimeoutException:
        raise HTTPException(504, "AI provider timed out")
    except httpx.RequestError:
        raise HTTPException(502, "AI provider is unreachable")
    if response.status_code >= 400:
        raise _provider_error(response.status_code, response.headers.get("Retry-After"))
    try:
        data = response.json()
        text = data["choices"][0]["message"]["content"]
        served_model = str(data.get("model", chosen))
    except (ValueError, KeyError, IndexError, TypeError):
        raise HTTPException(502, "AI provider returned an invalid chat completion")
    if not isinstance(text, str):
        raise HTTPException(502, "AI provider returned an unsupported response format")
    return text, served_model
