"""Encryption helpers for user-configured provider credentials."""
from cryptography.fernet import Fernet, InvalidToken
from fastapi import HTTPException
from .config import get_settings


def _fernet() -> Fernet:
    key = get_settings().credential_encryption_key
    if not key:
        raise HTTPException(503, "Mobile provider-key setup is disabled until NOVA_CREDENTIAL_ENCRYPTION_KEY is configured on the backend")
    try:
        return Fernet(key.encode("ascii"))
    except (ValueError, TypeError, UnicodeEncodeError):
        raise HTTPException(503, "Backend credential encryption key is invalid; provider settings are unavailable")


def encrypt_secret(secret: str) -> str:
    return _fernet().encrypt(secret.encode("utf-8")).decode("ascii")


def decrypt_secret(ciphertext: str) -> str:
    try:
        return _fernet().decrypt(ciphertext.encode("ascii")).decode("utf-8")
    except (InvalidToken, ValueError, UnicodeEncodeError):
        raise HTTPException(503, "Stored provider credential cannot be decrypted; re-enter it or restore the correct encryption key")
