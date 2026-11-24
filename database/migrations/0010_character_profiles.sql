-- Owner-scoped Head and Worker identity/voice profiles. Provider keys remain in
-- NOVA server runtime secrets; this table stores only Voice IDs and tunable settings.
CREATE TABLE IF NOT EXISTS character_profiles (
    id VARCHAR(36) PRIMARY KEY,
    owner_key_id VARCHAR(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    character_key VARCHAR(80) NOT NULL,
    agent_id VARCHAR(36) REFERENCES agents(id) ON DELETE SET NULL,
    name VARCHAR(100) NOT NULL,
    gender_identity VARCHAR(16) NOT NULL CHECK (gender_identity IN ('female', 'male')),
    age INTEGER,
    personality TEXT NOT NULL DEFAULT '',
    specialization VARCHAR(180) NOT NULL DEFAULT '',
    communication_style TEXT NOT NULL DEFAULT '',
    appearance JSONB NOT NULL DEFAULT '{}'::jsonb,
    voice_id VARCHAR(128),
    voice_settings JSONB NOT NULL DEFAULT '{"speed":0.96,"stability":0.55,"style":0.25,"similarity_boost":0.75}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_character_profile_owner_key UNIQUE (owner_key_id, character_key)
);
CREATE INDEX IF NOT EXISTS ix_character_profiles_owner_key_id ON character_profiles(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_character_profiles_character_key ON character_profiles(character_key);
CREATE INDEX IF NOT EXISTS ix_character_profiles_agent_id ON character_profiles(agent_id);
