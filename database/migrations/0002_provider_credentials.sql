-- Encrypt provider API keys per NOVA scoped client key; encryption key lives outside DB.
CREATE TABLE provider_credentials (
  id varchar(36) PRIMARY KEY,
  owner_key_id varchar(36) NOT NULL UNIQUE REFERENCES api_keys(id) ON DELETE CASCADE,
  provider varchar(40) NOT NULL DEFAULT 'arena',
  model_id varchar(160) NOT NULL DEFAULT 'coding-router-preview',
  encrypted_api_key text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_provider_credentials_owner_key_id ON provider_credentials(owner_key_id);
