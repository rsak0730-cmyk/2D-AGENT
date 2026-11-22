from datetime import datetime, timezone
from time import monotonic
from fastapi import HTTPException
from sqlalchemy.orm import Session
from .config import get_settings
from .credentials import decrypt_secret
from .models import ApiKey, ProviderConnection, ProviderCredential, ResourceSetting, UsageRecord
from .providers import complete_detailed, is_fallback_worthy

async def invoke_owner(db: Session, owner: ApiKey, message: str, model: str | None = None, history: list[dict[str, str]] | None = None, system: str | None = None, task_id: str | None = None, agent_id: str | None = None, provider_preference: str | None = None, temperature: float = 0.2, context_length: int | None = None):
    today = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    resources = db.query(ResourceSetting).filter(ResourceSetting.owner_key_id == owner.id).first()
    if resources and resources.daily_request_budget is not None:
        requests_today = db.query(UsageRecord).filter(UsageRecord.owner_key_id == owner.id, UsageRecord.created_at >= today).count()
        if requests_today >= resources.daily_request_budget:
            raise HTTPException(429, "Configured daily NOVA request budget has been reached")
    connections = db.query(ProviderConnection).filter(ProviderConnection.owner_key_id == owner.id, ProviderConnection.enabled.is_(True)).order_by(ProviderConnection.priority.asc()).all()
    candidates = []
    for row in connections:
        if row.daily_request_limit is not None:
            used = db.query(UsageRecord).filter(UsageRecord.owner_key_id == owner.id, UsageRecord.provider == row.provider, UsageRecord.created_at >= today).count()
            if used >= row.daily_request_limit:
                continue
        endpoint = row.endpoint or None
        chosen = model if not row.models or model in row.models else row.models[0]
        candidates.append((row.provider, decrypt_secret(row.encrypted_api_key), chosen, endpoint, row.account_id))
    legacy = db.query(ProviderCredential).filter(ProviderCredential.owner_key_id == owner.id).first()
    if legacy:
        candidates.append((legacy.provider, decrypt_secret(legacy.encrypted_api_key), model if model == legacy.model_id else legacy.model_id, None, None))
    cfg = get_settings()
    if cfg.provider_api_key:
        candidates.append((cfg.provider, cfg.provider_api_key, model if model == cfg.model else cfg.model, cfg.provider_base_url, None))
    if model:
        candidates.sort(key=lambda candidate: (candidate[2] != model, candidate[0] != provider_preference if provider_preference else False))
    elif provider_preference:
        candidates.sort(key=lambda candidate: candidate[0] != provider_preference)
    if not candidates:
        raise HTTPException(503, "No enabled provider connection with remaining request quota and a compatible model is configured")
    last_error = None
    for index, (provider, secret, chosen, endpoint, account_id) in enumerate(candidates):
        started = monotonic()
        kwargs = {}
        if provider != "arena": kwargs["provider"] = provider
        if account_id is not None: kwargs["account_id"] = account_id
        if system is not None: kwargs["system"] = system
        if temperature != 0.2: kwargs["temperature"] = temperature
        if context_length is not None: kwargs["context_length"] = context_length
        try:
            reply, served_model, input_tokens, output_tokens = await complete_detailed(message, chosen, history, api_key=secret, base_url=endpoint, **kwargs)
            db.add(UsageRecord(owner_key_id=owner.id, agent_id=agent_id, task_id=task_id, provider=provider, model_id=served_model, input_tokens=input_tokens or 0, output_tokens=output_tokens or 0, status="success", latency_ms=int((monotonic()-started)*1000), fallback_used=index > 0))
            db.commit()
            return reply, provider, served_model
        except HTTPException as error:
            last_error = error
            db.add(UsageRecord(owner_key_id=owner.id, agent_id=agent_id, task_id=task_id, provider=provider, model_id=chosen or "unknown", status="failed", latency_ms=int((monotonic()-started)*1000), fallback_used=index > 0, error_kind=str(error.status_code)))
            db.commit()
            if not is_fallback_worthy(error):
                raise
    raise last_error or HTTPException(503, "All configured AI providers are unavailable")
