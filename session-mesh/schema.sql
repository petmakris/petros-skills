PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS sessions (
  session_id   TEXT PRIMARY KEY,
  label        TEXT,
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

-- Layer 2 (task manager): a backlog that lives in the store, independent of
-- sessions. A task is serviced by 0..N sessions via the task_sessions link.
CREATE TABLE IF NOT EXISTS tasks (
  slug        TEXT PRIMARY KEY,
  title       TEXT NOT NULL,
  description TEXT,
  status      TEXT NOT NULL DEFAULT 'todo',   -- todo | in_progress | blocked | done
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS task_sessions (
  task_slug   TEXT NOT NULL,
  session_id  TEXT NOT NULL,
  role        TEXT DEFAULT 'lead',            -- lead | helper
  assigned_at TEXT NOT NULL,
  UNIQUE (task_slug, session_id)
);

CREATE TABLE IF NOT EXISTS mesh_meta (key TEXT PRIMARY KEY, value TEXT);
INSERT OR IGNORE INTO mesh_meta(key,value) VALUES ('paused','0');
INSERT OR IGNORE INTO mesh_meta(key,value) VALUES ('schema_version','3');
