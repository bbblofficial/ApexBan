-- MineStormBan schema migration
-- Safe to run on an existing database: only adds missing objects.

-- ---------- msban_punishments ----------
CREATE TABLE IF NOT EXISTS msban_punishments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  type VARCHAR(8) NOT NULL,
  uuid VARCHAR(36) NOT NULL,
  name VARCHAR(32) NOT NULL,
  operator VARCHAR(64) NOT NULL,
  reason VARCHAR(512) NOT NULL,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  server VARCHAR(64) NOT NULL,
  active SMALLINT NOT NULL,
  removed_by VARCHAR(64) NULL,
  removed_at BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL,
  INDEX idx_lookup (uuid, type, active),
  INDEX idx_updated (updated_at)
);

ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS type VARCHAR(8) NOT NULL;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS uuid VARCHAR(36) NOT NULL;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS name VARCHAR(32) NOT NULL;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS operator VARCHAR(64) NOT NULL;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS reason VARCHAR(512) NOT NULL;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS created_at BIGINT NOT NULL;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS expires_at BIGINT NOT NULL;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS server VARCHAR(64) NOT NULL;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS active SMALLINT NOT NULL;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS removed_by VARCHAR(64) NULL;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS removed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL;

-- ---------- msban_players ----------
CREATE TABLE IF NOT EXISTS msban_players (
  uuid VARCHAR(36) NOT NULL PRIMARY KEY,
  name VARCHAR(32) NOT NULL,
  name_lower VARCHAR(32) NOT NULL,
  ip VARCHAR(64) NULL,
  last_seen BIGINT NOT NULL,
  INDEX idx_name (name_lower)
);

ALTER TABLE msban_players ADD COLUMN IF NOT EXISTS uuid VARCHAR(36) NOT NULL;
ALTER TABLE msban_players ADD COLUMN IF NOT EXISTS name VARCHAR(32) NOT NULL;
ALTER TABLE msban_players ADD COLUMN IF NOT EXISTS name_lower VARCHAR(32) NOT NULL;
ALTER TABLE msban_players ADD COLUMN IF NOT EXISTS ip VARCHAR(64) NULL;
ALTER TABLE msban_players ADD COLUMN IF NOT EXISTS last_seen BIGINT NOT NULL;
