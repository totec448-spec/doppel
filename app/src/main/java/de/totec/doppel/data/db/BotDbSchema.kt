package de.totec.doppel.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/**
 * Pure SQL schema for the bot's bounded local state.
 *
 * Design rules:
 * - High-volume tables use an INTEGER rowid and covering indexes for chronological paging.
 * - Stable text/composite primary-key tables use WITHOUT ROWID to avoid a duplicate rowid index.
 * - Foreign keys protect owned state, while audit/dedupe rows intentionally retain external IDs
 *   without a foreign key so deleting a chat cannot erase operational evidence.
 * - No table contains credentials or WhatsApp session/authentication material.
 * - Every CREATE statement is idempotent. [schema_migrations] records applied logical versions;
 *   SQLiteOpenHelper's user_version remains the authoritative migration trigger.
 */
internal object BotDbSchema {
    const val DATABASE_NAME = "whatsapp_bot.db"
    const val VERSION = 10

    const val TABLE_SCHEMA_MIGRATIONS = "schema_migrations"
    const val TABLE_DB_META = "db_meta"
    const val TABLE_CHATS = "chats"
    const val TABLE_MESSAGES = "messages"
    const val TABLE_PROCESSED_EVENTS = "processed_events"
    const val TABLE_CHAT_MEMORY = "chat_memory"
    const val TABLE_CHAT_MEMORY_HISTORY = "chat_memory_history"
    const val TABLE_PERSONAS = "personas"
    const val TABLE_PERSONA_MEMORY = "persona_memory"
    const val TABLE_PERSONA_ASSIGNMENTS = "persona_assignments"
    const val TABLE_ACCESS_ENTRIES = "access_entries"
    const val TABLE_SCOPED_SETTINGS = "scoped_settings"
    const val TABLE_PROACTIVE_STATE = "proactive_state"
    const val TABLE_MEDIA_ANALYSIS_CACHE = "media_analysis_cache"
    const val TABLE_OUTBOUND_SAFETY = "outbound_safety_ledger"
    const val TABLE_ACTIVITY_LOG = "activity_log"
    const val TABLE_BRIDGE_OUTBOX = "bridge_outbox"

    private val versionOneStatements = listOf(
        """
        CREATE TABLE IF NOT EXISTS $TABLE_SCHEMA_MIGRATIONS (
            version INTEGER NOT NULL PRIMARY KEY,
            applied_at INTEGER NOT NULL
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_DB_META (
            meta_key TEXT NOT NULL PRIMARY KEY CHECK(length(meta_key) BETWEEN 1 AND 128),
            meta_value TEXT NOT NULL CHECK(length(meta_value) <= 4096)
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_CHATS (
            chat_id TEXT NOT NULL PRIMARY KEY CHECK(length(chat_id) BETWEEN 1 AND 512),
            kind TEXT NOT NULL CHECK(kind IN ('direct','group','broadcast','status','unknown')),
            display_name TEXT CHECK(display_name IS NULL OR length(display_name) <= 512),
            subject TEXT CHECK(subject IS NULL OR length(subject) <= 2048),
            metadata_json TEXT CHECK(metadata_json IS NULL OR length(metadata_json) <= 131072),
            last_message_at INTEGER,
            unread_count INTEGER NOT NULL DEFAULT 0 CHECK(unread_count >= 0),
            archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
            id INTEGER PRIMARY KEY,
            provider_message_id TEXT NOT NULL UNIQUE
                CHECK(length(provider_message_id) BETWEEN 1 AND 512),
            event_id TEXT CHECK(event_id IS NULL OR length(event_id) <= 512),
            chat_id TEXT NOT NULL,
            conversation_key TEXT
                CHECK(conversation_key IS NULL OR length(conversation_key) BETWEEN 1 AND 1024),
            sender_id TEXT CHECK(sender_id IS NULL OR length(sender_id) <= 512),
            direction TEXT NOT NULL CHECK(direction IN ('inbound','outbound','system')),
            message_type TEXT NOT NULL CHECK(length(message_type) BETWEEN 1 AND 64),
            body TEXT CHECK(body IS NULL OR length(body) <= 1048576),
            quoted_provider_message_id TEXT
                CHECK(quoted_provider_message_id IS NULL OR length(quoted_provider_message_id) <= 512),
            media_key TEXT CHECK(media_key IS NULL OR length(media_key) <= 512),
            occurred_at INTEGER NOT NULL,
            received_at INTEGER NOT NULL,
            delivery_state TEXT NOT NULL
                CHECK(delivery_state IN ('received','queued','sent','delivered','read','failed','unknown')),
            from_admin INTEGER NOT NULL DEFAULT 0 CHECK(from_admin IN (0,1)),
            metadata_json TEXT CHECK(metadata_json IS NULL OR length(metadata_json) <= 262144),
            FOREIGN KEY(chat_id) REFERENCES $TABLE_CHATS(chat_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_PROCESSED_EVENTS (
            event_id TEXT NOT NULL PRIMARY KEY CHECK(length(event_id) BETWEEN 1 AND 512),
            source TEXT NOT NULL CHECK(length(source) BETWEEN 1 AND 64),
            event_type TEXT NOT NULL CHECK(length(event_type) BETWEEN 1 AND 128),
            chat_id TEXT CHECK(chat_id IS NULL OR length(chat_id) <= 512),
            provider_message_id TEXT
                CHECK(provider_message_id IS NULL OR length(provider_message_id) <= 512),
            payload_hash TEXT CHECK(payload_hash IS NULL OR length(payload_hash) <= 128),
            disposition TEXT
                CHECK(disposition IS NULL OR length(disposition) BETWEEN 1 AND 64),
            received_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_CHAT_MEMORY (
            conversation_key TEXT NOT NULL PRIMARY KEY
                CHECK(length(conversation_key) BETWEEN 1 AND 1024),
            chat_id TEXT NOT NULL CHECK(length(chat_id) BETWEEN 1 AND 512),
            persona_id TEXT CHECK(persona_id IS NULL OR length(persona_id) BETWEEN 1 AND 128),
            summary TEXT NOT NULL CHECK(length(summary) <= 1048576),
            facts_json TEXT CHECK(facts_json IS NULL OR length(facts_json) <= 1048576),
            last_provider_message_id TEXT
                CHECK(last_provider_message_id IS NULL OR length(last_provider_message_id) <= 512),
            source_message_count INTEGER NOT NULL DEFAULT 0 CHECK(source_message_count >= 0),
            revision INTEGER NOT NULL DEFAULT 1 CHECK(revision >= 1),
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(chat_id) REFERENCES $TABLE_CHATS(chat_id) ON DELETE CASCADE
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_PERSONAS (
            persona_id TEXT NOT NULL PRIMARY KEY CHECK(length(persona_id) BETWEEN 1 AND 128),
            name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 256),
            description TEXT CHECK(description IS NULL OR length(description) <= 4096),
            system_prompt TEXT NOT NULL CHECK(length(system_prompt) <= 1048576),
            traits_json TEXT CHECK(traits_json IS NULL OR length(traits_json) <= 262144),
            voice_config_json TEXT
                CHECK(voice_config_json IS NULL OR length(voice_config_json) <= 131072),
            enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_PERSONA_MEMORY (
            persona_id TEXT NOT NULL PRIMARY KEY,
            summary TEXT NOT NULL CHECK(length(summary) <= 1048576),
            facts_json TEXT CHECK(facts_json IS NULL OR length(facts_json) <= 1048576),
            revision INTEGER NOT NULL DEFAULT 1 CHECK(revision >= 1),
            last_chat_write_count INTEGER NOT NULL DEFAULT 0
                CHECK(last_chat_write_count >= 0),
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(persona_id) REFERENCES $TABLE_PERSONAS(persona_id) ON DELETE CASCADE
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_PERSONA_ASSIGNMENTS (
            chat_id TEXT NOT NULL PRIMARY KEY,
            persona_id TEXT NOT NULL,
            assigned_at INTEGER NOT NULL,
            FOREIGN KEY(chat_id) REFERENCES $TABLE_CHATS(chat_id) ON DELETE CASCADE,
            FOREIGN KEY(persona_id) REFERENCES $TABLE_PERSONAS(persona_id) ON DELETE CASCADE
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_ACCESS_ENTRIES (
            list_kind TEXT NOT NULL CHECK(list_kind IN ('allow','group_allow','block','admin')),
            subject_type TEXT NOT NULL CHECK(subject_type IN ('jid','phone','chat','group')),
            subject_id TEXT NOT NULL CHECK(length(subject_id) BETWEEN 1 AND 512),
            label TEXT CHECK(label IS NULL OR length(label) <= 512),
            enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(list_kind, subject_type, subject_id)
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_SCOPED_SETTINGS (
            scope_type TEXT NOT NULL CHECK(length(scope_type) BETWEEN 1 AND 64),
            scope_id TEXT NOT NULL DEFAULT '' CHECK(length(scope_id) <= 512),
            setting_key TEXT NOT NULL CHECK(length(setting_key) BETWEEN 1 AND 128),
            setting_value TEXT NOT NULL CHECK(length(setting_value) <= 65536),
            value_type TEXT NOT NULL
                CHECK(value_type IN (
                    'string','boolean','integer','decimal','json','secret_reference'
                )),
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(scope_type, scope_id, setting_key)
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_PROACTIVE_STATE (
            chat_id TEXT NOT NULL PRIMARY KEY,
            enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN (0,1)),
            next_due_at INTEGER,
            cooldown_until INTEGER,
            last_inbound_at INTEGER,
            last_outbound_at INTEGER,
            daily_window_started_at INTEGER,
            daily_outbound_count INTEGER NOT NULL DEFAULT 0 CHECK(daily_outbound_count >= 0),
            consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK(consecutive_failures >= 0),
            lease_owner TEXT CHECK(lease_owner IS NULL OR length(lease_owner) <= 128),
            lease_until INTEGER,
            state_json TEXT CHECK(state_json IS NULL OR length(state_json) <= 131072),
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(chat_id) REFERENCES $TABLE_CHATS(chat_id) ON DELETE CASCADE
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_MEDIA_ANALYSIS_CACHE (
            content_hash TEXT NOT NULL CHECK(length(content_hash) BETWEEN 16 AND 128),
            analyzer TEXT NOT NULL CHECK(length(analyzer) BETWEEN 1 AND 128),
            analyzer_version TEXT NOT NULL CHECK(length(analyzer_version) BETWEEN 1 AND 64),
            media_type TEXT NOT NULL CHECK(length(media_type) BETWEEN 1 AND 128),
            result_json TEXT NOT NULL CHECK(length(result_json) <= 2097152),
            byte_size INTEGER CHECK(byte_size IS NULL OR byte_size >= 0),
            created_at INTEGER NOT NULL,
            last_accessed_at INTEGER NOT NULL,
            expires_at INTEGER,
            PRIMARY KEY(content_hash, analyzer, analyzer_version)
        ) WITHOUT ROWID
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_OUTBOUND_SAFETY (
            id INTEGER PRIMARY KEY,
            dedupe_key TEXT NOT NULL UNIQUE CHECK(length(dedupe_key) BETWEEN 1 AND 512),
            chat_id TEXT CHECK(chat_id IS NULL OR length(chat_id) <= 512),
            outbound_kind TEXT NOT NULL CHECK(length(outbound_kind) BETWEEN 1 AND 64),
            decision TEXT NOT NULL CHECK(decision IN ('allow','deny','review')),
            reason_code TEXT NOT NULL CHECK(length(reason_code) BETWEEN 1 AND 128),
            status TEXT NOT NULL CHECK(status IN ('reserved','sent','failed','cancelled')),
            payload_hash TEXT CHECK(payload_hash IS NULL OR length(payload_hash) <= 128),
            planned_at INTEGER NOT NULL,
            committed_at INTEGER,
            expires_at INTEGER,
            metadata_json TEXT CHECK(metadata_json IS NULL OR length(metadata_json) <= 131072)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_ACTIVITY_LOG (
            id INTEGER PRIMARY KEY,
            occurred_at INTEGER NOT NULL,
            level TEXT NOT NULL CHECK(level IN ('debug','info','warn','error')),
            category TEXT NOT NULL CHECK(length(category) BETWEEN 1 AND 128),
            action TEXT NOT NULL CHECK(length(action) BETWEEN 1 AND 128),
            chat_id TEXT CHECK(chat_id IS NULL OR length(chat_id) <= 512),
            correlation_id TEXT CHECK(correlation_id IS NULL OR length(correlation_id) <= 512),
            summary TEXT NOT NULL CHECK(length(summary) <= 4096),
            details_json TEXT CHECK(details_json IS NULL OR length(details_json) <= 262144)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $TABLE_BRIDGE_OUTBOX (
            id INTEGER PRIMARY KEY,
            dedupe_key TEXT NOT NULL UNIQUE CHECK(length(dedupe_key) BETWEEN 1 AND 512),
            operation TEXT NOT NULL CHECK(length(operation) BETWEEN 1 AND 128),
            chat_id TEXT CHECK(chat_id IS NULL OR length(chat_id) <= 512),
            payload_json TEXT NOT NULL CHECK(length(payload_json) <= 1048576),
            priority INTEGER NOT NULL DEFAULT 0 CHECK(priority BETWEEN -1000 AND 1000),
            state TEXT NOT NULL CHECK(state IN ('pending','leased','completed','dead')),
            attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
            available_at INTEGER NOT NULL,
            lease_owner TEXT CHECK(lease_owner IS NULL OR length(lease_owner) <= 128),
            lease_until INTEGER,
            last_error TEXT CHECK(last_error IS NULL OR length(last_error) <= 4096),
            result_json TEXT CHECK(result_json IS NULL OR length(result_json) <= 262144),
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
    )

    private val indexStatements = listOf(
        "CREATE INDEX IF NOT EXISTS idx_chats_last_message ON $TABLE_CHATS(archived, last_message_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_messages_chat_time ON $TABLE_MESSAGES(chat_id, occurred_at DESC, id DESC)",
        "CREATE INDEX IF NOT EXISTS idx_messages_conversation_time ON $TABLE_MESSAGES(conversation_key, occurred_at DESC, id DESC) WHERE conversation_key IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_messages_delivery ON $TABLE_MESSAGES(delivery_state, occurred_at)",
        "CREATE INDEX IF NOT EXISTS idx_messages_direction_time ON $TABLE_MESSAGES(direction, occurred_at)",
        "CREATE INDEX IF NOT EXISTS idx_messages_event ON $TABLE_MESSAGES(event_id) WHERE event_id IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_events_expiry ON $TABLE_PROCESSED_EVENTS(expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_chat_memory_chat ON $TABLE_CHAT_MEMORY(chat_id)",
        "CREATE INDEX IF NOT EXISTS idx_persona_assignment_persona ON $TABLE_PERSONA_ASSIGNMENTS(persona_id)",
        "CREATE INDEX IF NOT EXISTS idx_access_lookup ON $TABLE_ACCESS_ENTRIES(subject_id, enabled, list_kind)",
        "CREATE INDEX IF NOT EXISTS idx_settings_key ON $TABLE_SCOPED_SETTINGS(setting_key, scope_type)",
        "CREATE INDEX IF NOT EXISTS idx_proactive_due ON $TABLE_PROACTIVE_STATE(next_due_at, chat_id) WHERE enabled = 1",
        "CREATE INDEX IF NOT EXISTS idx_media_expiry ON $TABLE_MEDIA_ANALYSIS_CACHE(expires_at) WHERE expires_at IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_media_lru ON $TABLE_MEDIA_ANALYSIS_CACHE(last_accessed_at)",
        "CREATE INDEX IF NOT EXISTS idx_outbound_chat_time ON $TABLE_OUTBOUND_SAFETY(chat_id, planned_at DESC)",
        // The outbound safety policy pages the ledger chronologically without a chat filter on
        // every single send decision. Without this index that ORDER BY was a full table scan plus
        // a temp B-tree sort over thirty days of rows, several times per reply.
        "CREATE INDEX IF NOT EXISTS idx_outbound_time ON $TABLE_OUTBOUND_SAFETY(planned_at DESC, id DESC)",
        // The warmup factor asks for the newest cancelled safety lock. Kind-first ordering turns
        // that from "read the newest thousand rows and filter in Kotlin" into a short index seek.
        "CREATE INDEX IF NOT EXISTS idx_outbound_kind_time ON $TABLE_OUTBOUND_SAFETY(outbound_kind, status, planned_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_outbound_expiry ON $TABLE_OUTBOUND_SAFETY(expires_at) WHERE expires_at IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_outbound_status_commit_kind ON $TABLE_OUTBOUND_SAFETY(status, committed_at, outbound_kind) WHERE committed_at IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_activity_time ON $TABLE_ACTIVITY_LOG(occurred_at DESC, id DESC)",
        "CREATE INDEX IF NOT EXISTS idx_activity_chat_time ON $TABLE_ACTIVITY_LOG(chat_id, occurred_at DESC) WHERE chat_id IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_activity_category_time ON $TABLE_ACTIVITY_LOG(category, occurred_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_outbox_ready ON $TABLE_BRIDGE_OUTBOX(state, available_at, priority DESC, id) WHERE state IN ('pending','leased')",
        "CREATE INDEX IF NOT EXISTS idx_outbox_state_chat ON $TABLE_BRIDGE_OUTBOX(state, chat_id) WHERE chat_id IS NOT NULL",
    )

    fun create(db: SQLiteDatabase) {
        applyVersionOne(db)
        applyVersionTwo(db)
        applyVersionThree(db)
        applyVersionFour(db)
        applyVersionFive(db)
        applyVersionSix(db)
        applyVersionSeven(db)
        applyVersionEight(db)
        ensureCurrent(db)
    }

    fun upgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion <= newVersion) {
            "Schema downgrade must not be routed through upgrade: $oldVersion -> $newVersion"
        }
        // Historical databases can legitimately predate whole tables that later version steps
        // alter. CREATE IF NOT EXISTS fills in only those missing baseline tables while leaving
        // legacy tables (notably v1 chat_memory) untouched for their data-preserving migrations.
        // Without this bootstrap, a sparse v1 database reaches v3 and tries to ALTER a
        // bridge_outbox table that did not exist yet.
        versionOneStatements.forEach(db::execSQL)
        var appliedVersion = oldVersion
        if (appliedVersion < 1 && newVersion >= 1) {
            applyVersionOne(db)
            appliedVersion = 1
        }
        if (appliedVersion < 2 && newVersion >= 2) {
            applyVersionTwo(db)
            appliedVersion = 2
        }
        if (appliedVersion < 3 && newVersion >= 3) {
            applyVersionThree(db)
            appliedVersion = 3
        }
        if (appliedVersion < 4 && newVersion >= 4) {
            applyVersionFour(db)
            appliedVersion = 4
        }
        if (appliedVersion < 5 && newVersion >= 5) {
            applyVersionFive(db)
            appliedVersion = 5
        }
        if (appliedVersion < 6 && newVersion >= 6) {
            applyVersionSix(db)
            appliedVersion = 6
        }
        if (appliedVersion < 7 && newVersion >= 7) {
            applyVersionSeven(db)
            appliedVersion = 7
        }
        if (appliedVersion < 8 && newVersion >= 8) {
            applyVersionEight(db)
            appliedVersion = 8
        }
        if (appliedVersion < 9 && newVersion >= 9) {
            applyVersionNine(db)
            appliedVersion = 9
        }
        if (appliedVersion < 10 && newVersion >= 10) {
            applyVersionTen(db)
            appliedVersion = 10
        }
        check(appliedVersion == newVersion) {
            "Missing database migration from $appliedVersion to $newVersion"
        }
        ensureCurrent(db)
    }

    /**
     * Repairs a missing table/index after an interrupted development install without deleting data.
     * CREATE IF NOT EXISTS makes this safe to call whenever a writable database is opened.
     */
    fun ensureCurrent(db: SQLiteDatabase) {
        versionOneStatements.forEach(db::execSQL)
        if (!hasColumn(db, TABLE_CHAT_MEMORY, "conversation_key")) {
            applyVersionTwo(db)
        }
        if (!hasColumn(db, TABLE_BRIDGE_OUTBOX, "result_json")) {
            applyVersionThree(db)
        }
        if (!hasColumn(db, TABLE_PROCESSED_EVENTS, "disposition")) {
            applyVersionFour(db)
        }
        if (!hasColumn(db, TABLE_PROACTIVE_STATE, "cold_outreach_at")) {
            applyVersionFive(db)
        }
        if (!hasTable(db, TABLE_CHAT_MEMORY_HISTORY)) {
            applyVersionSix(db)
        }
        if (!hasColumn(db, TABLE_MESSAGES, "conversation_key")) {
            applyVersionSeven(db)
        } else if (!hasMigration(db, 7)) {
            repairMessageConversationKeys(db)
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(7, ?)",
                arrayOf(System.currentTimeMillis()),
            )
        }
        if (!hasColumn(db, TABLE_PERSONA_MEMORY, "last_chat_write_count")) {
            applyVersionNine(db)
        }
        if (!hasColumn(db, TABLE_CHATS, "ai_disclosure_sent_at")) {
            applyVersionTen(db)
        }
        indexStatements.forEach(db::execSQL)
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(?, ?)",
            arrayOf<Any?>(VERSION, System.currentTimeMillis()),
        )
    }

    private fun applyVersionOne(db: SQLiteDatabase) {
        versionOneStatements.forEach(db::execSQL)
        indexStatements.forEach(db::execSQL)
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(1, ?)",
            arrayOf(System.currentTimeMillis()),
        )
    }

    private fun applyVersionTwo(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_CHAT_MEMORY, "conversation_key")) {
            db.execSQL("ALTER TABLE $TABLE_CHAT_MEMORY RENAME TO ${TABLE_CHAT_MEMORY}_v1")
            db.execSQL(
                """
                CREATE TABLE $TABLE_CHAT_MEMORY (
                    conversation_key TEXT NOT NULL PRIMARY KEY
                        CHECK(length(conversation_key) BETWEEN 1 AND 1024),
                    chat_id TEXT NOT NULL CHECK(length(chat_id) BETWEEN 1 AND 512),
                    persona_id TEXT CHECK(persona_id IS NULL OR length(persona_id) BETWEEN 1 AND 128),
                    summary TEXT NOT NULL CHECK(length(summary) <= 1048576),
                    facts_json TEXT
                        CHECK(facts_json IS NULL OR length(facts_json) <= 1048576),
                    last_provider_message_id TEXT
                        CHECK(last_provider_message_id IS NULL OR
                            length(last_provider_message_id) <= 512),
                    source_message_count INTEGER NOT NULL DEFAULT 0
                        CHECK(source_message_count >= 0),
                    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision >= 1),
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(chat_id) REFERENCES $TABLE_CHATS(chat_id) ON DELETE CASCADE
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO $TABLE_CHAT_MEMORY(
                    conversation_key,
                    chat_id,
                    summary,
                    facts_json,
                    last_provider_message_id,
                    source_message_count,
                    revision,
                    updated_at
                )
                SELECT
                    chat_id,
                    chat_id,
                    summary,
                    facts_json,
                    last_provider_message_id,
                    source_message_count,
                    revision,
                    updated_at
                FROM ${TABLE_CHAT_MEMORY}_v1
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE ${TABLE_CHAT_MEMORY}_v1")
        }
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_chat_memory_chat ON $TABLE_CHAT_MEMORY(chat_id)",
        )
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(2, ?)",
            arrayOf(System.currentTimeMillis()),
        )
    }

    private fun applyVersionThree(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_BRIDGE_OUTBOX, "result_json")) {
            db.execSQL(
                """
                ALTER TABLE $TABLE_BRIDGE_OUTBOX
                ADD COLUMN result_json TEXT
                    CHECK(result_json IS NULL OR length(result_json) <= 262144)
                """.trimIndent(),
            )
        }
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(3, ?)",
            arrayOf(System.currentTimeMillis()),
        )
    }

    private fun applyVersionFour(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_PROCESSED_EVENTS, "disposition")) {
            db.execSQL(
                """
                ALTER TABLE $TABLE_PROCESSED_EVENTS
                ADD COLUMN disposition TEXT
                    CHECK(disposition IS NULL OR length(disposition) BETWEEN 1 AND 64)
                """.trimIndent(),
            )
        }
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(4, ?)",
            arrayOf(System.currentTimeMillis()),
        )
    }

    /**
     * Adds the timestamp at which a chat was armed for proactive outreach without ever having
     * received an inbound message. Keeping this separate from `last_inbound_at` preserves the
     * "cold contact" definition the outbound safety caps rely on, while still giving the
     * scheduler an anchor it can measure silence from.
     */
    private fun applyVersionFive(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_PROACTIVE_STATE, "cold_outreach_at")) {
            db.execSQL(
                "ALTER TABLE $TABLE_PROACTIVE_STATE ADD COLUMN cold_outreach_at INTEGER",
            )
        }
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(5, ?)",
            arrayOf(System.currentTimeMillis()),
        )
    }

    /** Keeps recent persona-specific memory revisions as chat timeline landmarks. */
    private fun applyVersionSix(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CHAT_MEMORY_HISTORY (
                conversation_key TEXT NOT NULL
                    CHECK(length(conversation_key) BETWEEN 1 AND 1024),
                chat_id TEXT NOT NULL CHECK(length(chat_id) BETWEEN 1 AND 512),
                persona_id TEXT CHECK(persona_id IS NULL OR length(persona_id) BETWEEN 1 AND 128),
                summary TEXT NOT NULL CHECK(length(summary) <= 1048576),
                facts_json TEXT
                    CHECK(facts_json IS NULL OR length(facts_json) <= 1048576),
                last_provider_message_id TEXT
                    CHECK(last_provider_message_id IS NULL OR length(last_provider_message_id) <= 512),
                source_message_count INTEGER NOT NULL DEFAULT 0 CHECK(source_message_count >= 0),
                revision INTEGER NOT NULL CHECK(revision >= 1),
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(conversation_key, revision),
                FOREIGN KEY(chat_id) REFERENCES $TABLE_CHATS(chat_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_chat_memory_history_chat_time " +
                "ON $TABLE_CHAT_MEMORY_HISTORY(chat_id, updated_at DESC)",
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE_CHAT_MEMORY_HISTORY(
                conversation_key, chat_id, summary, facts_json, last_provider_message_id,
                source_message_count, revision, updated_at
            )
            SELECT conversation_key, chat_id, summary, facts_json, last_provider_message_id,
                   source_message_count, revision, updated_at
            FROM $TABLE_CHAT_MEMORY
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(6, ?)",
            arrayOf(System.currentTimeMillis()),
        )
    }

    /**
     * Makes persona ownership queryable without reading and parsing an entire physical chat.
     *
     * Existing rows are backfilled only when their metadata names an exact conversation or a
     * persona. Ambiguous legacy rows stay null, which is the existing fail-closed isolation rule.
     */
    private fun applyVersionSeven(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_MESSAGES, "conversation_key")) {
            db.execSQL(
                "ALTER TABLE $TABLE_MESSAGES ADD COLUMN conversation_key TEXT " +
                    "CHECK(conversation_key IS NULL OR length(conversation_key) BETWEEN 1 AND 1024)",
            )
        }
        repairMessageConversationKeys(db)
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_messages_conversation_time " +
                "ON $TABLE_MESSAGES(conversation_key, occurred_at DESC, id DESC) " +
                "WHERE conversation_key IS NOT NULL",
        )
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(7, ?)",
            arrayOf(System.currentTimeMillis()),
        )
    }

    /** Idempotent completion of the v7 data backfill after any interrupted schema repair. */
    private fun repairMessageConversationKeys(db: SQLiteDatabase) {
        db.query(
            TABLE_MESSAGES,
            arrayOf("id", "chat_id", "metadata_json"),
            "conversation_key IS NULL AND metadata_json IS NOT NULL",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            val values = ContentValues(1)
            while (cursor.moveToNext()) {
                val chatId = cursor.getString(1)
                val metadata =
                    runCatching { JSONObject(cursor.getString(2)) }
                        .getOrNull() ?: continue
                val exact = metadata.optString("conversationKey").trim()
                val persona = metadata.optString("persona").trim()
                val conversationKey =
                    exact.takeIf { it.length in 1..1_024 && it.substringBeforeLast('#') == chatId }
                        ?: persona.takeIf { it.length in 1..128 }?.let { "$chatId#$it" }
                        ?: continue
                values.clear()
                values.put("conversation_key", conversationKey)
                db.update(TABLE_MESSAGES, values, "id = ?", arrayOf(cursor.getLong(0).toString()))
            }
        }
    }

    /** Marks only legacy rows created by the local Inject UI as trusted operator context. */
    private fun applyVersionEight(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_CHAT_MEMORY, "persona_id")) {
            db.execSQL(
                "ALTER TABLE $TABLE_CHAT_MEMORY ADD COLUMN persona_id TEXT " +
                    "CHECK(persona_id IS NULL OR length(persona_id) BETWEEN 1 AND 128)",
            )
        }
        if (!hasColumn(db, TABLE_CHAT_MEMORY_HISTORY, "persona_id")) {
            db.execSQL(
                "ALTER TABLE $TABLE_CHAT_MEMORY_HISTORY ADD COLUMN persona_id TEXT " +
                    "CHECK(persona_id IS NULL OR length(persona_id) BETWEEN 1 AND 128)",
            )
        }
        db.execSQL(
            "UPDATE $TABLE_CHAT_MEMORY SET persona_id = substr(conversation_key, instr(conversation_key, '#') + 1) " +
                "WHERE persona_id IS NULL AND instr(conversation_key, '#') > 0",
        )
        db.execSQL(
            "UPDATE $TABLE_CHAT_MEMORY_HISTORY SET persona_id = substr(conversation_key, instr(conversation_key, '#') + 1) " +
                "WHERE persona_id IS NULL AND instr(conversation_key, '#') > 0",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_chat_memory_persona_time " +
                "ON $TABLE_CHAT_MEMORY(persona_id, updated_at DESC) WHERE persona_id IS NOT NULL",
        )
        db.execSQL(
            "UPDATE $TABLE_MESSAGES SET from_admin = 1 " +
                "WHERE message_type = 'injection' AND provider_message_id LIKE 'inject:%'",
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE_SCOPED_SETTINGS(
                scope_type, scope_id, setting_key, setting_value, value_type, updated_at
            )
            SELECT 'persona_contact', chat_id, 'value', persona_id, 'string', assigned_at
            FROM $TABLE_PERSONA_ASSIGNMENTS
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO $TABLE_DB_META(meta_key, meta_value)
            VALUES('settings_revision', '1')
            ON CONFLICT(meta_key) DO UPDATE SET
                meta_value = CAST(CAST(meta_value AS INTEGER) + 1 AS TEXT)
            """.trimIndent(),
        )
        db.delete(TABLE_PERSONA_ASSIGNMENTS, null, null)
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(8, ?)",
            arrayOf(System.currentTimeMillis()),
        )
    }

    /**
     * Remembers the chat-write total a persona memory was last synthesized at.
     *
     * The cadence used to be `total % 3 == 0`, which has no state to move: a synthesis triggered by
     * hand would either be refused by the same modulo or, once forced, leave the automatic one due
     * again within a single chat write. Storing the total the last synthesis saw turns the rule into
     * a distance, so a forced synthesis pushes the next automatic one a full three writes out.
     *
     * Existing rows start at zero, which reads as "never synthesized against a known total" and
     * makes the first cadence check after the upgrade behave like a fresh persona.
     */
    private fun applyVersionNine(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_PERSONA_MEMORY, "last_chat_write_count")) {
            db.execSQL(
                "ALTER TABLE $TABLE_PERSONA_MEMORY ADD COLUMN last_chat_write_count " +
                    "INTEGER NOT NULL DEFAULT 0 CHECK(last_chat_write_count >= 0)",
            )
        }
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(9, ?)",
            arrayOf(System.currentTimeMillis()),
        )
    }

    /**
     * Records the moment a chat was told it is talking to an AI. It is a timestamp rather than a
     * flag so the disclosure is auditable after the fact, and it lives in its own column rather
     * than in [TABLE_CHATS].metadata_json because the alias writer rewrites that blob wholesale —
     * a clobber there would either lose the record or disclose to the same contact twice.
     */
    private fun applyVersionTen(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_CHATS, "ai_disclosure_sent_at")) {
            db.execSQL(
                "ALTER TABLE $TABLE_CHATS ADD COLUMN ai_disclosure_sent_at INTEGER " +
                    "CHECK(ai_disclosure_sent_at IS NULL OR ai_disclosure_sent_at >= 0)",
            )
        }
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SCHEMA_MIGRATIONS(version, applied_at) VALUES(10, ?)",
            arrayOf(System.currentTimeMillis()),
        )
    }

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun hasMigration(db: SQLiteDatabase, version: Int): Boolean =
        db.rawQuery(
            "SELECT 1 FROM $TABLE_SCHEMA_MIGRATIONS WHERE version = ? LIMIT 1",
            arrayOf(version.toString()),
        ).use { it.moveToFirst() }

    private fun hasColumn(
        db: SQLiteDatabase,
        table: String,
        column: String,
    ): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }
}

/**
 * Hard retention limits keep a long-running phone installation bounded without a polling job.
 * Maintenance is write-triggered and therefore consumes no CPU while the bot is idle.
 */
object BotDatabaseLimits {
    const val MAX_MESSAGES_TOTAL = 100_000
    const val MAX_MESSAGES_PER_CHAT = 5_000
    const val MAX_PROCESSED_EVENTS = 50_000
    const val MAX_SCOPED_SETTINGS = 10_000
    const val MAX_MEDIA_ANALYSES = 2_000
    const val MAX_OUTBOUND_LEDGER_ROWS = 50_000
    const val MAX_ACTIVITY_ROWS = 20_000
    const val MAX_ACTIVE_OUTBOX_ROWS = 50_000
    const val MAX_COMPLETED_OUTBOX_ROWS = 10_000

    const val DEFAULT_QUERY_LIMIT = 100
    const val MAX_QUERY_LIMIT = 1_000
    const val MAINTENANCE_EVERY_WRITES = 64
    const val MAINTENANCE_MIN_INTERVAL_MS = 15L * 60L * 1_000L
    const val MEDIA_TOUCH_MIN_INTERVAL_MS = 6L * 60L * 60L * 1_000L
}
