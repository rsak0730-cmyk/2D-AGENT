from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="NOVA_", extra="ignore")
    database_url: str = "sqlite:///./nova.db"
    admin_token: str = ""
    provider: str = "arena"
    provider_base_url: str = "https://api.arena.ai/v1"
    provider_api_key: str = ""
    model: str = "coding-router-preview"
    request_timeout_seconds: float = 45.0

@lru_cache
def get_settings() -> Settings:
    return Settings()
