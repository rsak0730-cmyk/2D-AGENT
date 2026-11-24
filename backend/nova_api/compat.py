"""OpenAI and Anthropic-shaped, owner-authenticated NOVA gateway facade.

This first protocol slice intentionally supports text-only, non-streaming messages.
It routes through NOVA's existing per-owner provider policy and never exposes upstream
provider credentials. Streaming is rejected explicitly until native upstream streaming
is implemented rather than being simulated from buffered completions.
"""
from __future__ import annotations

from typing import Any, Literal
from uuid import uuid4
from time import time, monotonic

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.orm import Session

from .auth import digest
from .config import get_settings
from .db import get_db
from .models import ApiKey, ProviderConnection, ProviderCredential
from .providers import _base
from .routing import invoke_owner_detailed, open_owner_stream
from .db import SessionLocal

router = APIRouter()


class CompatMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role: Literal["system", "developer", "user", "assistant"]
    content: str | list[dict[str, Any]]


class OpenAIChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    model: str = Field(min_length=1, max_length=160)
    messages: list[CompatMessage] = Field(min_length=1, max_length=100)
    stream: bool = False
    temperature: float = Field(default=0.2, ge=0, le=2)
    max_tokens: int | None = Field(default=None, ge=1, le=32768)


class AnthropicMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role: Literal["user", "assistant"]
    content: str | list[dict[str, Any]]


class AnthropicMessagesRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    model: str = Field(min_length=1, max_length=160)
    max_tokens: int = Field(ge=1, le=32768)
    messages: list[AnthropicMessage] = Field(min_length=1, max_length=100)
    system: str | list[dict[str, Any]] | None = None
    temperature: float | None = Field(default=None, ge=0, le=1)
    stream: bool = False


def authenticate_token(authorization: str | None, x_api_key: str | None, db: Session, scopes: set[str]) -> ApiKey:
    token = x_api_key.strip() if x_api_key and x_api_key.strip() else ""
    if not token and authorization and authorization.startswith("Bearer "):
        token = authorization[7:].strip()
    if not token:
        raise HTTPException(401, "NOVA application API key required")
    key = db.query(ApiKey).filter(ApiKey.key_hash == digest(token), ApiKey.revoked.is_(False)).first()
    if key is None:
        raise HTTPException(401, "Invalid or revoked NOVA application API key")
    from datetime import datetime, timezone
    if key.expires_at and key.expires_at.replace(tzinfo=timezone.utc) <= datetime.now(timezone.utc):
        raise HTTPException(401, "NOVA application API key expired")
    if not (scopes & set(key.scopes or [])):
        raise HTTPException(403, "NOVA application API key lacks a required scope")
    return key


def text_content(value: str | list[dict[str, Any]], *, label: str) -> str:
    if isinstance(value, str):
        return value
    if not isinstance(value, list):
        raise HTTPException(422, f"{label} must contain text")
    chunks: list[str] = []
    for block in value:
        if not isinstance(block, dict) or block.get("type") != "text" or not isinstance(block.get("text"), str):
            raise HTTPException(422, f"{label} supports text blocks only; tools and multimodal blocks are not supported")
        chunks.append(block["text"])
    return "\n".join(chunks)


def openai_error(status: int, message: str, request_id: str | None = None) -> JSONResponse:
    kind = "invalid_request_error" if status < 500 else "api_error"
    headers = {"x-request-id": request_id} if request_id else None
    return JSONResponse(status_code=status, content={"error": {"message": message, "type": kind, "code": status}}, headers=headers)


def anthropic_error(status: int, message: str, request_id: str | None = None) -> JSONResponse:
    error_type = {401: "authentication_error", 403: "permission_error", 429: "rate_limit_error", 422: "invalid_request_error", 502: "api_error", 503: "api_error", 504: "timeout_error"}.get(status, "api_error")
    headers = {"request-id": request_id, "x-request-id": request_id} if request_id else None
    return JSONResponse(status_code=status, content={"type": "error", "error": {"type": error_type, "message": message}}, headers=headers)


def openai_finish_reason(reason: str | None) -> str:
    return {"max_tokens": "length", "end_turn": "stop", "stop_sequence": "stop"}.get(reason or "", reason or "stop")


def anthropic_finish_reason(reason: str | None) -> str:
    return {"length": "max_tokens", "max_tokens": "max_tokens", "content_filter": "refusal", "stop": "end_turn", "stop_sequence": "stop_sequence"}.get(reason or "", "end_turn")


@router.get("/v1/models")
def list_compatible_models(
    authorization: str | None = Header(default=None),
    x_api_key: str | None = Header(default=None, alias="x-api-key"),
    db: Session = Depends(get_db),
):
    request_id = "req_" + uuid4().hex
    try:
        owner = authenticate_token(authorization, x_api_key, db, {"assistant:read", "assistant:write", "providers:read"})
    except HTTPException as error:
        return openai_error(error.status_code, str(error.detail), request_id)
    models: dict[str, str] = {}
    for connection in db.query(ProviderConnection).filter(ProviderConnection.owner_key_id == owner.id, ProviderConnection.enabled.is_(True)).all():
        for model_id in connection.models or []:
            if isinstance(model_id, str) and model_id:
                models.setdefault(model_id, connection.provider)
    legacy = db.query(ProviderCredential).filter(ProviderCredential.owner_key_id == owner.id).first()
    if legacy and legacy.model_id:
        models.setdefault(legacy.model_id, legacy.provider)
    cfg = get_settings()
    if cfg.provider_api_key and cfg.model:
        models.setdefault(cfg.model, cfg.provider)
    return JSONResponse(
        content={"object": "list", "data": [{"id": model_id, "object": "model", "created": 0, "owned_by": provider} for model_id, provider in sorted(models.items())]},
        headers={"x-request-id": request_id},
    )


@router.post("/v1/chat/completions")
async def openai_chat_completions(
    body: OpenAIChatRequest,
    authorization: str | None = Header(default=None),
    x_api_key: str | None = Header(default=None, alias="x-api-key"),
    db: Session = Depends(get_db),
):
    request_id = "chatcmpl-" + uuid4().hex
    try:
        owner = authenticate_token(authorization, x_api_key, db, {"assistant:write"})
        if body.max_tokens and body.max_tokens > get_settings().max_completion_tokens:
            raise HTTPException(422, "max_tokens exceeds the backend configured output limit")
        system_parts: list[str] = []
        history: list[dict[str, str]] = []
        for item in body.messages:
            content = text_content(item.content, label="message content")
            if item.role in {"system", "developer"}:
                system_parts.append(content)
            else:
                history.append({"role": item.role, "content": content})
        if not history or history[-1]["role"] != "user":
            raise HTTPException(422, "The final chat message must be from the user")
        prompt = history.pop()["content"]
        system = "\n\n".join(system_parts) or None
        if body.stream:
            messages=([{"role":"system","content":system}] if system else [])+history+[{"role":"user","content":prompt}]
            if sum(len(item["content"]) for item in messages)>get_settings().max_prompt_characters:
                raise HTTPException(413,"Request exceeds the configured prompt-size limit")
            upstream,stream_provider,fallback=await open_owner_stream(db,owner,body.model,messages,body.temperature,min(body.max_tokens or get_settings().max_completion_tokens,get_settings().max_completion_tokens))
            from .compat_stream import openai_stream
            return StreamingResponse(openai_stream(upstream,owner.id,stream_provider,fallback,body.model,request_id),media_type="text/event-stream",headers={"x-request-id":request_id,"x-nova-provider":stream_provider,"x-nova-fallback":str(fallback).lower(),"x-nova-token-usage":"provider-reported-or-zero-if-unavailable","cache-control":"no-cache","connection":"keep-alive","x-accel-buffering":"no"})
        text, provider, model, input_tokens, output_tokens, fallback, finish_reason = await invoke_owner_detailed(
            db, owner, prompt, body.model, history=history, system=system,
            temperature=body.temperature, max_tokens=body.max_tokens, include_finish_reason=True,
        )
    except HTTPException as error:
        return openai_error(error.status_code, str(error.detail), request_id)
    except Exception:
        return openai_error(500, "NOVA gateway request failed", request_id)
    return JSONResponse(
        content={
            "id": request_id,
            "object": "chat.completion",
            "created": int(time()),
            "model": model,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": openai_finish_reason(finish_reason)}],
            "usage": {"prompt_tokens": input_tokens or 0, "completion_tokens": output_tokens or 0, "total_tokens": (input_tokens or 0) + (output_tokens or 0)},
        },
        headers={"x-request-id": request_id, "x-nova-provider": provider, "x-nova-fallback": str(fallback).lower(), "x-nova-token-usage": "provider-reported-or-zero-if-unavailable"},
    )


@router.post("/v1/messages")
async def anthropic_messages(
    body: AnthropicMessagesRequest,
    authorization: str | None = Header(default=None),
    x_api_key: str | None = Header(default=None, alias="x-api-key"),
    db: Session = Depends(get_db),
):
    request_id = "msg_" + uuid4().hex
    try:
        owner = authenticate_token(authorization, x_api_key, db, {"assistant:write"})
        if body.max_tokens > get_settings().max_completion_tokens:
            raise HTTPException(422, "max_tokens exceeds the backend configured output limit")
        if body.temperature is not None and body.temperature > 1:
            raise HTTPException(422, "Anthropic-compatible temperature must be between 0 and 1")
        history = [{"role": item.role, "content": text_content(item.content, label="message content")} for item in body.messages]
        if history[-1]["role"] != "user":
            raise HTTPException(422, "The final message must be from the user")
        prompt = history.pop()["content"]
        system = text_content(body.system, label="system") if isinstance(body.system, (str, list)) else None
        if body.stream:
            messages=([{"role":"system","content":system}] if system else [])+history+[{"role":"user","content":prompt}]
            if sum(len(item["content"]) for item in messages)>get_settings().max_prompt_characters:
                raise HTTPException(413,"Request exceeds the configured prompt-size limit")
            upstream,stream_provider,fallback=await open_owner_stream(db,owner,body.model,messages,body.temperature if body.temperature is not None else 0.2,min(body.max_tokens,get_settings().max_completion_tokens))
            from .compat_stream import anthropic_stream
            return StreamingResponse(anthropic_stream(upstream,owner.id,stream_provider,fallback,body.model,request_id),media_type="text/event-stream",headers={"request-id":request_id,"x-request-id":request_id,"x-nova-provider":stream_provider,"x-nova-fallback":str(fallback).lower(),"x-nova-token-usage":"provider-reported-or-zero-if-unavailable","cache-control":"no-cache","connection":"keep-alive","x-accel-buffering":"no"})
        text, provider, model, input_tokens, output_tokens, fallback, finish_reason = await invoke_owner_detailed(
            db, owner, prompt, body.model, history=history, system=system,
            temperature=body.temperature if body.temperature is not None else 0.2,
            max_tokens=body.max_tokens, include_finish_reason=True,
        )
    except HTTPException as error:
        return anthropic_error(error.status_code, str(error.detail), request_id)
    except Exception:
        return anthropic_error(500, "NOVA gateway request failed", request_id)
    return JSONResponse(
        content={
            "id": request_id,
            "type": "message",
            "role": "assistant",
            "model": model,
            "content": [{"type": "text", "text": text}],
            "stop_reason": anthropic_finish_reason(finish_reason),
            "stop_sequence": None,
            "usage": {"input_tokens": input_tokens or 0, "output_tokens": output_tokens or 0},
        },
        headers={"request-id": request_id, "x-request-id": request_id, "x-nova-provider": provider, "x-nova-fallback": str(fallback).lower(), "x-nova-token-usage": "provider-reported-or-zero-if-unavailable"},
    )
