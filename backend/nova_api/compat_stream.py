"""Wire-format translation and usage accounting for inbound streaming routes."""
from __future__ import annotations

import asyncio
import json
import httpx
import logging
from time import monotonic, time
from typing import AsyncIterator

from fastapi import HTTPException
from sqlalchemy.orm import Session

from .db import SessionLocal
from .models import UsageRecord
from .streaming import ProviderEventStream

logger=logging.getLogger("nova.compat_stream")


def _record_safely(*args):
    try:_record(*args)
    except Exception as error:logger.warning("Could not persist streaming usage (%s)",type(error).__name__)


def _anthropic_finish_reason(reason):
    return {"length":"max_tokens","max_tokens":"max_tokens","content_filter":"refusal","stop":"end_turn","stop_sequence":"stop_sequence"}.get(reason or "","end_turn")


def _openai_finish_reason(reason):
    return {"end_turn":"stop","stop_sequence":"stop","max_tokens":"length"}.get(reason or "",reason or "stop")


def _sse(data: dict, event: str | None = None) -> str:
    prefix = f"event: {event}\n" if event else ""
    return prefix + "data: " + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n\n"


def _record(owner_id: str, provider: str, model: str, fallback: bool, started: float, stream: ProviderEventStream,
            status: str, error_kind: str | None = None) -> None:
    db: Session = SessionLocal()
    try:
        db.add(UsageRecord(owner_key_id=owner_id, provider=provider, model_id=stream.model or model,
                           input_tokens=stream.input_tokens or 0, output_tokens=stream.output_tokens or 0,
                           status=status, latency_ms=int((monotonic() - started) * 1000), fallback_used=fallback, error_kind=error_kind))
        db.commit()
    finally:
        db.close()


def openai_stream(stream: ProviderEventStream, owner_id: str, provider: str, fallback: bool, model: str, request_id: str) -> AsyncIterator[str]:
    async def generate():
        started=monotonic(); status="failed"; error_kind=None
        base={"id":request_id,"object":"chat.completion.chunk","created":int(time()),"model":stream.model or model}
        try:
            first={**base,"choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":None}]}
            yield _sse(first)
            async for item in stream.events():
                if item["type"]=="text":
                    base["model"]=stream.model or model
                    yield _sse({**base,"choices":[{"index":0,"delta":{"content":item["text"]},"finish_reason":None}]})
            base["model"]=stream.model or model
            yield _sse({**base,"choices":[{"index":0,"delta":{},"finish_reason":_openai_finish_reason(stream.finish_reason)}]})
            if stream.input_tokens is not None or stream.output_tokens is not None:
                yield _sse({**base,"choices":[],"usage":{"prompt_tokens":stream.input_tokens or 0,"completion_tokens":stream.output_tokens or 0,"total_tokens":(stream.input_tokens or 0)+(stream.output_tokens or 0)}})
            yield "data: [DONE]\n\n"
            status="success"
        except (asyncio.CancelledError,GeneratorExit):
            error_kind="stream_cancelled"
            raise
        except HTTPException as exc:
            error_kind=f"upstream_http_{exc.status_code}"
            yield _sse({"error":{"message":str(exc.detail),"type":"api_error","code":exc.status_code}})
        except httpx.TimeoutException:
            error_kind="stream_timeout"
            yield _sse({"error":{"message":"Provider stream timed out; retry starts a new generation","type":"timeout_error","code":504}})
        except httpx.RequestError:
            error_kind="stream_connection_error"
            yield _sse({"error":{"message":"Provider stream connection was interrupted; retry starts a new generation","type":"api_error","code":502}})
        except Exception:
            error_kind="stream_interrupted"
            yield _sse({"error":{"message":"Provider stream was interrupted; retry the request to start a new generation","type":"api_error","code":502}})
        finally:
            await stream.close()
            await asyncio.to_thread(_record_safely,owner_id,provider,model,fallback,started,stream,status,error_kind)
    return generate()


def anthropic_stream(stream: ProviderEventStream, owner_id: str, provider: str, fallback: bool, model: str, request_id: str) -> AsyncIterator[str]:
    async def generate():
        started=monotonic(); status="failed"; error_kind=None; message_started=False; block_started=False
        def emit_start(input_tokens=0):
            nonlocal message_started
            message_started=True
            return _sse({"type":"message_start","message":{"id":request_id,"type":"message","role":"assistant","content":[],"model":stream.model or model,"stop_reason":None,"stop_sequence":None,"usage":{"input_tokens":input_tokens,"output_tokens":0}}},"message_start")
        try:
            async for item in stream.events():
                if item["type"]=="start" and not message_started:
                    yield emit_start(item.get("input_tokens",0))
                elif item["type"]=="text":
                    if not message_started: yield emit_start(stream.input_tokens or 0)
                    if not block_started:
                        block_started=True
                        yield _sse({"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}},"content_block_start")
                    yield _sse({"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":item["text"]}},"content_block_delta")
            if not message_started: yield emit_start(stream.input_tokens or 0)
            if not block_started:
                yield _sse({"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}},"content_block_start")
            yield _sse({"type":"content_block_stop","index":0},"content_block_stop")
            yield _sse({"type":"message_delta","delta":{"stop_reason":_anthropic_finish_reason(stream.finish_reason),"stop_sequence":None},"usage":{"output_tokens":stream.output_tokens or 0}},"message_delta")
            yield _sse({"type":"message_stop"},"message_stop")
            status="success"
        except (asyncio.CancelledError,GeneratorExit):
            error_kind="stream_cancelled"
            raise
        except HTTPException as exc:
            error_kind=f"upstream_http_{exc.status_code}"
            if not message_started: yield emit_start(stream.input_tokens or 0)
            error_type="timeout_error" if exc.status_code==504 else "api_error"
            yield _sse({"type":"error","error":{"type":error_type,"message":str(exc.detail)}},"error")
        except httpx.TimeoutException:
            error_kind="stream_timeout"
            if not message_started: yield emit_start(stream.input_tokens or 0)
            yield _sse({"type":"error","error":{"type":"timeout_error","message":"Provider stream timed out; retry starts a new generation"}},"error")
        except httpx.RequestError:
            error_kind="stream_connection_error"
            if not message_started: yield emit_start(stream.input_tokens or 0)
            yield _sse({"type":"error","error":{"type":"api_error","message":"Provider stream connection was interrupted; retry starts a new generation"}},"error")
        except Exception:
            error_kind="stream_interrupted"
            if not message_started: yield emit_start(stream.input_tokens or 0)
            yield _sse({"type":"error","error":{"type":"api_error","message":"Provider stream was interrupted; retry to start a new generation"}},"error")
        finally:
            await stream.close()
            await asyncio.to_thread(_record_safely,owner_id,provider,model,fallback,started,stream,status,error_kind)
    return generate()
