from types import SimpleNamespace

from cryptography.fernet import Fernet
from backend.nova_api import credentials


def test_gateway_credential_rotation_accepts_old_key_then_reencrypts_under_primary(monkeypatch):
    old_key = Fernet.generate_key().decode()
    new_key = Fernet.generate_key().decode()
    encrypted_before = Fernet(old_key.encode()).encrypt(b"restricted-gateway-token").decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(
        credential_encryption_key=new_key,
        credential_encryption_key_previous=old_key,
    ))
    rotated = credentials.rotate_secret(encrypted_before)
    assert rotated != encrypted_before
    assert credentials.decrypt_secret(rotated) == "restricted-gateway-token"
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(
        credential_encryption_key=new_key,
        credential_encryption_key_previous="",
    ))
    assert credentials.decrypt_secret(rotated) == "restricted-gateway-token"
