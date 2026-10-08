-- MineStormBan schema migration (MariaDB 10.2+ / MySQL 8)
--
-- You normally do NOT need this file: with `database.auto-repair: true` (default) the plugin
-- creates missing tables, columns and indexes by itself on every start. Use this script only to
-- prepare / fix a database by hand. It is safe to run repeatedly. Replace `msban_` with your
-- configured `database.table-prefix`.

-- ---------- msban_punishments ----------
CREATE TABLE IF NOT EXISTS msban_punishments (
  id BIGINT NOT NULL AUTO_INCREMENT,
  type VARCHAR(8) NOT NULL DEFAULT 'BAN',
  uuid VARCHAR(36) NOT NULL DEFAULT '',
  name VARCHAR(32) NOT NULL DEFAULT '',
  operator VARCHAR(64) NOT NULL DEFAULT '',
  reason VARCHAR(512) NOT NULL DEFAULT '',
  created_at BIGINT NOT NULL DEFAULT 0,
  expires_at BIGINT NOT NULL DEFAULT -1,
  server VARCHAR(64) NOT NULL DEFAULT 'default',
  scope VARCHAR(8) NOT NULL DEFAULT 'SERVER',
  active SMALLINT NOT NULL DEFAULT 0,
  removed_by VARCHAR(64),
  removed_at BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS type VARCHAR(8) NOT NULL DEFAULT 'BAN';
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS uuid VARCHAR(36) NOT NULL DEFAULT '';
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS name VARCHAR(32) NOT NULL DEFAULT '';
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS operator VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS reason VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS created_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS expires_at BIGINT NOT NULL DEFAULT -1;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS server VARCHAR(64) NOT NULL DEFAULT 'default';
-- NEW: where the punishment is enforced. SERVER = only on `server`, GLOBAL = every server.
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS scope VARCHAR(8) NOT NULL DEFAULT 'SERVER';
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS active SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS removed_by VARCHAR(64);
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS removed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE msban_punishments ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_lookup ON msban_punishments (uuid, type, active);
CREATE INDEX IF NOT EXISTS idx_updated ON msban_punishments (updated_at);

-- Optional: make every EXISTING ban/mute network-wide again (new rows follow config.yml `scope.*`).
-- UPDATE msban_punishments SET scope = 'GLOBAL' WHERE type IN ('BAN', 'MUTE');

-- ---------- msban_players ----------
CREATE TABLE IF NOT EXISTS msban_players (
  uuid VARCHAR(36) NOT NULL,
  name VARCHAR(32) NOT NULL DEFAULT '',
  name_lower VARCHAR(32) NOT NULL DEFAULT '',
  ip VARCHAR(64),
  last_seen BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE msban_players ADD COLUMN IF NOT EXISTS name VARCHAR(32) NOT NULL DEFAULT '';
ALTER TABLE msban_players ADD COLUMN IF NOT EXISTS name_lower VARCHAR(32) NOT NULL DEFAULT '';
ALTER TABLE msban_players ADD COLUMN IF NOT EXISTS ip VARCHAR(64);
ALTER TABLE msban_players ADD COLUMN IF NOT EXISTS last_seen BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_name ON msban_players (name_lower);
