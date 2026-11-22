import httpx
from fastapi import HTTPException
from .config import get_settings

async def complete(message: str, model: str | None = None, history: list[dict[str, str]] | None = None) -> tuple[str, str]:
    cfg = get_settings()
    if not cfg.provider_api_key:
        raise HTTPException(503, "No AI provider key configured. Add NOVA_PROVIDER_API_KEY to the backend environment.")
    chosen = model or cfg.model
    url = cfg.provider_base_url.rstrip("/") + "/chat/completions"
    headers = {"Authorization": f"Bearer {cfg.provider_api_key}", "Content-Type": "application/json"}
    body = {"model": chosen, "messages": [*(history or []), {"role": "user", "content": message}]}
    try:
        async with httpx.AsyncClient(timeout=cfg.request_timeout_seconds) as client:
            response = await client.post(url, headers=headers, json=body)
    except httpx.TimeoutException:
        raise HTTPException(504, "AI provider timed out")
    except httpx.RequestError:
        raise HTTPException(502, "AI provider is unreachable")
    if response.status_code == 429:
        raise HTTPException(429, "AI provider rate limit reached", headers={"Retry-After": response.headers.get("Retry-After", "60")})
    if response.status_code in (401, 403):
        raise HTTPException(502, "AI provider rejected configured credentials")
    if response.status_code == 402:
        raise HTTPException(402, "AI provider reports insufficient balance or payment required")
    if response.status_code >= 400:
        # Avoid reflecting arbitrary upstream bodies, which may include sensitive data.
        raise HTTPException(502, f"AI provider request failed with status {response.status_code}")
    try:
        data = response.json()
        text = data["choices"][0]["message"]["content"]
        served_model = str(data.get("model", chosen))
    except (ValueError, KeyError, IndexError, TypeError):
        raise HTTPException(502, "AI provider returned an invalid chat completion")
    if not isinstance(text, str):
        raise HTTPException(502, "AI provider returned an unsupported response format")
    return text, served_model
