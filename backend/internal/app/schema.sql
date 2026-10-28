
      PRAGMA foreign_keys = ON;
      PRAGMA journal_mode = WAL;
      PRAGMA busy_timeout = 5000;
      CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS sessions (
        token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS events (
        id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 120),
        start_at TEXT NOT NULL, end_at TEXT NOT NULL CHECK(end_at > start_at),
        timezone TEXT NOT NULL CHECK(timezone = 'Asia/Shanghai'),
        status TEXT NOT NULL DEFAULT 'planned' CHECK(status IN ('planned','completed')),
        version INTEGER NOT NULL DEFAULT 1 CHECK(version > 0),
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT
      );
      CREATE INDEX IF NOT EXISTS events_owner_time ON events(user_id, start_at, end_at);
      CREATE TABLE IF NOT EXISTS mutation_requests (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        request_id TEXT NOT NULL, payload_hash TEXT NOT NULL, result TEXT NOT NULL,
        created_at TEXT NOT NULL, PRIMARY KEY(user_id, request_id)
      );
      CREATE TABLE IF NOT EXISTS sync_mutations (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        mutation_id TEXT NOT NULL, payload_hash TEXT NOT NULL, result TEXT NOT NULL,
        PRIMARY KEY(user_id, mutation_id)
      );
      CREATE TABLE IF NOT EXISTS ai_usage (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        usage_day TEXT NOT NULL, count INTEGER NOT NULL,
        PRIMARY KEY(user_id, usage_day)
      );
      CREATE TABLE IF NOT EXISTS model_settings (
        user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
        base_url TEXT, model TEXT, api_key_ciphertext TEXT, key_hint TEXT,
        version INTEGER NOT NULL CHECK(version > 0),
        updated_at TEXT NOT NULL, deleted_at TEXT,
        CHECK(deleted_at IS NOT NULL OR (base_url IS NOT NULL AND model IS NOT NULL AND api_key_ciphertext IS NOT NULL AND key_hint IS NOT NULL))
      );
      CREATE TABLE IF NOT EXISTS pairing_requests (
        token_hash TEXT PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        source_session_hash TEXT NOT NULL REFERENCES sessions(token_hash) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL,
        consumed_at INTEGER
      );
    
