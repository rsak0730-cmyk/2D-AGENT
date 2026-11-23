"""Authenticated, separately-keyed provider/gateway credential encryption."""
from cryptography.fernet import Fernet, InvalidToken, MultiFernet
from fastapi import HTTPException
from .config import get_settings


def _fernet() -> MultiFernet:
    settings = get_settings()
    primary = getattr(settings, "credential_encryption_key", "")
    if not primary:
        raise HTTPException(503, "Gateway credential storage is disabled until NOVA_CREDENTIAL_ENCRYPTION_KEY is configured on the backend")
    candidates = [primary] + [item.strip() for item in getattr(settings, "credential_encryption_key_previous", "").split(",") if item.strip()]
    try:
        return MultiFernet([Fernet(key.encode("ascii")) for key in candidates])
    except (ValueError, TypeError, UnicodeEncodeError):
        raise HTTPException(503, "Backend credential encryption key configuration is invalid")


def encrypt_secret(secret: str) -> str:
    return _fernet().encrypt(secret.encode("utf-8")).decode("ascii")


def decrypt_secret(ciphertext: str) -> str:
    try:
        return _fernet().decrypt(ciphertext.encode("ascii")).decode("utf-8")
    except (InvalidToken, ValueError, UnicodeEncodeError):
        raise HTTPException(503, "Stored credential cannot be decrypted; configure the correct key or restore its backup")


def rotate_secret(ciphertext: str) -> str:
    """Re-encrypt ciphertext under the current primary key without revealing plaintext."""
    try:
        return _fernet().rotate(ciphertext.encode("ascii")).decode("ascii")
    except (InvalidToken, ValueError, UnicodeEncodeError):
        raise HTTPException(503, "Stored credential cannot be rotated with the configured key ring")
