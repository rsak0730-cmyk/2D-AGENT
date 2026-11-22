from functools import lru_cache
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="NOVA_", extra="ignore")
    database_url: str = "sqlite:///./nova.db"
    admin_token: str = ""
    provider: str = "arena"
    provider_base_url: str = "https://api.arena.ai/v1"
    provider_api_key: str = ""
    model: str = "coding-router-preview"
    request_timeout_seconds: float = Field(default=45.0, ge=1, le=300)
    max_completion_tokens: int = Field(default=2048, ge=1, le=32768)
    max_prompt_characters: int = Field(default=60000, ge=1000, le=1000000)
    gateway_allowed_hosts: str = ""
    credential_encryption_key: str = ""

@lru_cache
def get_settings() -> Settings:
    return Settings()
