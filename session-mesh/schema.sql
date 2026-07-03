PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS sessions (
  session_id   TEXT PRIMARY KEY,
  ticket       TEXT,
  cwd          TEXT NOT NULL UNIQUE,
  branch       TEXT,
  pid          INTEGER,
  status       TEXT DEFAULT 'idle',
  current_task TEXT,
  started_at   TEXT NOT NULL,
  last_seen    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS commands (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  target     TEXT NOT NULL,
  kind       TEXT NOT NULL,
  payload    TEXT NOT NULL,
  state      TEXT DEFAULT 'pending',
  output     TEXT,
  exit_state TEXT,
  created_by TEXT,
  created_at TEXT NOT NULL,
  picked_at  TEXT,
  done_at    TEXT,
  ack_at     TEXT
);

CREATE TABLE IF NOT EXISTS mesh_meta (key TEXT PRIMARY KEY, value TEXT);
INSERT OR IGNORE INTO mesh_meta(key,value) VALUES ('paused','0');
INSERT OR IGNORE INTO mesh_meta(key,value) VALUES ('schema_version','1');
