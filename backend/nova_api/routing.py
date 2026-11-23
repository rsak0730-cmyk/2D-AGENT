from datetime import datetime, timezone
from time import monotonic
from fastapi import HTTPException
from sqlalchemy.orm import Session
from .config import get_settings
from .credentials import decrypt_secret
from .models import ApiKey, ProviderConnection, ProviderCredential, ResourceSetting, UsageRecord
from .providers import complete_detailed, is_fallback_worthy
from .streaming import open_provider_stream

async def invoke_owner_detailed(db: Session, owner: ApiKey, message: str, model: str | None = None, history: list[dict[str, str]] | None = None, system: str | None = None, task_id: str | None = None, agent_id: str | None = None, provider_preference: str | None = None, temperature: float = 0.2, context_length: int | None = None, max_tokens: int | None = None, include_finish_reason: bool = False):
    today = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    resources = db.query(ResourceSetting).filter(ResourceSetting.owner_key_id == owner.id).first()
    if resources and resources.daily_request_budget is not None:
        requests_today = db.query(UsageRecord).filter(UsageRecord.owner_key_id == owner.id, UsageRecord.created_at >= today).count()
        if requests_today >= resources.daily_request_budget:
            raise HTTPException(429, "Configured daily NOVA request budget has been reached")
    connections = db.query(ProviderConnection).filter(ProviderConnection.owner_key_id == owner.id, ProviderConnection.enabled.is_(True)).order_by(ProviderConnection.priority.asc()).all()
    candidates = []
    for row in connections:
        if model and row.models and model not in row.models:
            continue
        if row.daily_request_limit is not None:
            used = db.query(UsageRecord).filter(UsageRecord.owner_key_id == owner.id, UsageRecord.provider == row.provider, UsageRecord.created_at >= today).count()
            if used >= row.daily_request_limit:
                continue
        endpoint = row.endpoint or None
        chosen = model or (row.models[0] if row.models else get_settings().model)
        candidates.append((row.provider, decrypt_secret(row.encrypted_api_key), chosen, endpoint, row.account_id))
    legacy = db.query(ProviderCredential).filter(ProviderCredential.owner_key_id == owner.id).first()
    if legacy and (not model or model == legacy.model_id):
        candidates.append((legacy.provider, decrypt_secret(legacy.encrypted_api_key), model or legacy.model_id, None, None))
    cfg = get_settings()
    if cfg.provider_api_key and (not model or model == cfg.model):
        candidates.append((cfg.provider, cfg.provider_api_key, model or cfg.model, cfg.provider_base_url, None))
    if model:
        candidates.sort(key=lambda candidate: (candidate[2] != model, candidate[0] != provider_preference if provider_preference else False))
    elif provider_preference:
        candidates.sort(key=lambda candidate: candidate[0] != provider_preference)
    if not candidates:
        raise HTTPException(503, "No enabled provider connection with remaining request quota and a compatible model is configured")
    # End read transactions before awaiting network I/O. Parallel worker sessions must not
    # hold database locks or stale SQLite transactions across provider latency.
    db.commit()
    last_error = None
    for index, (provider, secret, chosen, endpoint, account_id) in enumerate(candidates):
        started = monotonic()
        kwargs = {}
        if provider != "arena": kwargs["provider"] = provider
        if account_id is not None: kwargs["account_id"] = account_id
        if system is not None: kwargs["system"] = system
        if temperature != 0.2: kwargs["temperature"] = temperature
        if context_length is not None: kwargs["context_length"] = context_length
        if max_tokens is not None: kwargs["max_tokens"] = max_tokens
        if include_finish_reason: kwargs["include_finish_reason"] = True
        try:
            completion = await complete_detailed(message, chosen, history, api_key=secret, base_url=endpoint, **kwargs)
            reply, served_model, input_tokens, output_tokens = completion[:4]
            finish_reason = completion[4] if len(completion) > 4 else None
            db.add(UsageRecord(owner_key_id=owner.id, agent_id=agent_id, task_id=task_id, provider=provider, model_id=served_model, input_tokens=input_tokens or 0, output_tokens=output_tokens or 0, status="success", latency_ms=int((monotonic()-started)*1000), fallback_used=index > 0))
            db.commit()
            result = (reply, provider, served_model, input_tokens, output_tokens, index > 0)
            return (*result, finish_reason) if include_finish_reason else result
        except HTTPException as error:
            last_error = error
            db.add(UsageRecord(owner_key_id=owner.id, agent_id=agent_id, task_id=task_id, provider=provider, model_id=chosen or "unknown", status="failed", latency_ms=int((monotonic()-started)*1000), fallback_used=index > 0, error_kind=str(error.status_code)))
            db.commit()
            if not is_fallback_worthy(error):
                raise
    raise last_error or HTTPException(503, "All configured AI providers are unavailable")


async def open_owner_stream(db: Session, owner: ApiKey, model: str, messages: list[dict[str, str]], temperature: float, max_tokens: int):
    """Resolve owner credentials/quota and open the first usable native provider stream."""
    today=datetime.now(timezone.utc).replace(hour=0,minute=0,second=0,microsecond=0)
    resources=db.query(ResourceSetting).filter(ResourceSetting.owner_key_id==owner.id).first()
    if resources and resources.daily_request_budget is not None:
        used=db.query(UsageRecord).filter(UsageRecord.owner_key_id==owner.id,UsageRecord.created_at>=today).count()
        if used>=resources.daily_request_budget: raise HTTPException(429,"Configured daily NOVA request budget has been reached")
    candidates=[]
    for row in db.query(ProviderConnection).filter(ProviderConnection.owner_key_id==owner.id,ProviderConnection.enabled.is_(True)).order_by(ProviderConnection.priority.asc()).all():
        if row.models and model not in row.models: continue
        if row.daily_request_limit is not None:
            used=db.query(UsageRecord).filter(UsageRecord.owner_key_id==owner.id,UsageRecord.provider==row.provider,UsageRecord.created_at>=today).count()
            if used>=row.daily_request_limit: continue
        candidates.append((row.provider,decrypt_secret(row.encrypted_api_key),row.endpoint or None))
    legacy=db.query(ProviderCredential).filter(ProviderCredential.owner_key_id==owner.id,ProviderCredential.model_id==model).first()
    if legacy: candidates.append((legacy.provider,decrypt_secret(legacy.encrypted_api_key),None))
    cfg=get_settings()
    if cfg.provider_api_key and cfg.model==model: candidates.append((cfg.provider,cfg.provider_api_key,cfg.provider_base_url))
    if not candidates: raise HTTPException(503,"No enabled provider connection with remaining request quota and the requested model is configured")
    db.commit()
    last_error=None
    for index,(provider,secret,endpoint) in enumerate(candidates):
        try:
            upstream=await open_provider_stream(provider=provider,secret=secret,endpoint=endpoint,model=model,messages=messages,temperature=temperature,max_tokens=max_tokens)
            return upstream,provider,index>0
        except HTTPException as error:
            last_error=error
            db.add(UsageRecord(owner_key_id=owner.id,provider=provider,model_id=model,status="failed",error_kind=str(error.status_code)))
            db.commit()
            if not is_fallback_worthy(error): raise
    raise last_error or HTTPException(503,"All configured streaming providers are unavailable")


async def invoke_owner(db: Session, owner: ApiKey, message: str, model: str | None = None, history: list[dict[str, str]] | None = None, system: str | None = None, task_id: str | None = None, agent_id: str | None = None, provider_preference: str | None = None, temperature: float = 0.2, context_length: int | None = None):
    """Backward-compatible convenience API for callers that do not need usage metadata."""
    reply, provider, served_model, _, _, _ = await invoke_owner_detailed(
        db, owner, message, model, history=history, system=system, task_id=task_id,
        agent_id=agent_id, provider_preference=provider_preference,
        temperature=temperature, context_length=context_length,
    )
    return reply, provider, served_model
