import hashlib
import secrets
from datetime import datetime, timezone
from fastapi import Depends, Header, HTTPException
from sqlalchemy.orm import Session
from .config import get_settings
from .db import get_db
from .models import ApiKey


def digest(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def require_admin(authorization: str | None = Header(default=None)):
    expected = get_settings().admin_token
    if not expected:
        raise HTTPException(503, "Admin key management is disabled: configure NOVA_ADMIN_TOKEN")
    if not authorization or not secrets.compare_digest(authorization.removeprefix("Bearer "), expected):
        raise HTTPException(401, "Admin authentication required")


def require_scope(scope: str):
    def dependency(authorization: str | None = Header(default=None), db: Session = Depends(get_db)) -> ApiKey:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "Scoped NOVA API key required")
        key = db.query(ApiKey).filter(ApiKey.key_hash == digest(authorization[7:]), ApiKey.revoked.is_(False)).first()
        if key is None:
            raise HTTPException(401, "Invalid or revoked API key")
        if key.expires_at and key.expires_at.replace(tzinfo=timezone.utc) <= datetime.now(timezone.utc):
            raise HTTPException(401, "API key expired")
        if scope not in key.scopes:
            raise HTTPException(403, f"Missing required scope: {scope}")
        return key
    return dependency


def new_secret() -> str:
    return "nova_" + secrets.token_urlsafe(32)
