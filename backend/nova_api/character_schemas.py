from typing import Literal

from pydantic import BaseModel, Field, field_validator


class CharacterUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    personality: str | None = Field(default=None, max_length=4000)
    specialization: str | None = Field(default=None, max_length=180)
    communication_style: str | None = Field(default=None, max_length=2000)
    appearance: dict | None = None

    @field_validator("name")
    @classmethod
    def nonblank_name(cls, value):
        if value is not None and not value.strip():
            raise ValueError("Character name cannot be blank")
        return value.strip() if value is not None else value


class CharacterVoiceSettings(BaseModel):
    speed: float = Field(default=0.96, ge=0.7, le=1.2)
    stability: float = Field(default=0.55, ge=0, le=1)
    style: float = Field(default=0.25, ge=0, le=1)
    similarity_boost: float = Field(default=0.75, ge=0, le=1)


class VoiceIdRequest(BaseModel):
    voice_id: str = Field(min_length=5, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")


class CharacterVoiceUpdate(VoiceIdRequest):
    settings: CharacterVoiceSettings = Field(default_factory=CharacterVoiceSettings)


class CharacterVoicePreview(BaseModel):
    voice_id: str | None = Field(default=None, min_length=5, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")
    text: str = Field(default="Hello, I'm ready to help.", min_length=1, max_length=800)
    settings: CharacterVoiceSettings | None = None


class CharacterProfileView(BaseModel):
    character_key: str
    agent_id: str | None
    name: str
    gender_identity: Literal["female", "male"]
    age: int | None
    personality: str
    specialization: str
    communication_style: str
    appearance: dict
    provider: str | None
    model_id: str | None
    availability: str
    voice_provider: Literal["elevenlabs"] = "elevenlabs"
    voice_id: str | None
    voice_settings: CharacterVoiceSettings
    voice_ready: bool


class VoiceValidationView(BaseModel):
    valid: bool
    voice_id: str
    voice_name: str
    gender: Literal["female", "male"]
    message: str


class VoiceServiceStatus(BaseModel):
    provider: Literal["elevenlabs"] = "elevenlabs"
    configured: bool
    message: str
