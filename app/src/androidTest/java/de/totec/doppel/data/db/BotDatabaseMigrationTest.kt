package de.totec.doppel.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BotDatabaseMigrationTest {
    @Test
    fun versionOneMemoryMigratesWithoutCrossPersonaInjection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use {
                database ->
                database.execSQL(
                    """
                    CREATE TABLE schema_migrations (
                        version INTEGER NOT NULL PRIMARY KEY,
                        applied_at INTEGER NOT NULL
                    ) WITHOUT ROWID
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE chats (
                        chat_id TEXT NOT NULL PRIMARY KEY,
                        kind TEXT NOT NULL,
                        display_name TEXT,
                        subject TEXT,
                        metadata_json TEXT,
                        last_message_at INTEGER,
                        unread_count INTEGER NOT NULL DEFAULT 0,
                        archived INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    ) WITHOUT ROWID
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE chat_memory (
                        chat_id TEXT NOT NULL PRIMARY KEY,
                        summary TEXT NOT NULL,
                        facts_json TEXT,
                        last_provider_message_id TEXT,
                        source_message_count INTEGER NOT NULL DEFAULT 0,
                        revision INTEGER NOT NULL DEFAULT 1,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(chat_id) REFERENCES chats(chat_id) ON DELETE CASCADE
                    ) WITHOUT ROWID
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO chats(
                        chat_id, kind, unread_count, archived, created_at, updated_at
                    ) VALUES('49123@s.whatsapp.net', 'direct', 0, 0, 1, 1)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO chat_memory(
                        chat_id, summary, source_message_count, revision, updated_at
                    ) VALUES('49123@s.whatsapp.net', 'legacy memory', 70, 3, 2)
                    """.trimIndent(),
                )
                database.execSQL(
                    "INSERT INTO schema_migrations(version, applied_at) VALUES(1, 1)",
                )
                database.version = 1
            }

            BotDatabase(context, databaseName).use { helper ->
                val database = helper.writableDatabase
                assertEquals(BotDbSchema.VERSION, database.version)
                assertTrue(database.hasColumn("chat_memory", "conversation_key"))
                assertTrue(database.hasColumn("bridge_outbox", "result_json"))
                assertTrue(database.hasColumn("processed_events", "disposition"))
                assertTrue(database.hasColumn("chat_memory", "persona_id"))
                assertTrue(database.hasColumn("messages", "conversation_key"))

                database.rawQuery(
                    """
                    SELECT conversation_key, chat_id, summary, revision
                    FROM chat_memory
                    WHERE conversation_key = '49123@s.whatsapp.net'
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("49123@s.whatsapp.net", cursor.getString(0))
                    assertEquals("49123@s.whatsapp.net", cursor.getString(1))
                    assertEquals("legacy memory", cursor.getString(2))
                    assertEquals(3L, cursor.getLong(3))
                }

                // A v1 row is retained under an explicit legacy key. Exact persona-scoped lookups
                // cannot see or inject it until a new consolidation creates that persona's key.
                assertFalse(database.hasMemory("49123@s.whatsapp.net#human"))
                assertFalse(database.hasMemory("49123@s.whatsapp.net#coach"))

                database.execSQL(
                    """
                    INSERT INTO chat_memory(
                        conversation_key, chat_id, summary, source_message_count, revision, updated_at
                    ) VALUES(?, ?, ?, 70, 1, 3)
                    """.trimIndent(),
                    arrayOf(
                        "49123@s.whatsapp.net#human",
                        "49123@s.whatsapp.net",
                        "human memory",
                    ),
                )
                database.execSQL(
                    """
                    INSERT INTO chat_memory(
                        conversation_key, chat_id, summary, source_message_count, revision, updated_at
                    ) VALUES(?, ?, ?, 70, 1, 3)
                    """.trimIndent(),
                    arrayOf(
                        "49123@s.whatsapp.net#coach",
                        "49123@s.whatsapp.net",
                        "coach memory",
                    ),
                )
                assertTrue(database.hasMemory("49123@s.whatsapp.net#human"))
                assertTrue(database.hasMemory("49123@s.whatsapp.net#coach"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun SQLiteDatabase.hasColumn(
        table: String,
        column: String,
    ): Boolean {
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(name) == column) return true
            }
        }
        return false
    }

    private fun SQLiteDatabase.hasMemory(conversationKey: String): Boolean {
        rawQuery(
            "SELECT 1 FROM chat_memory WHERE conversation_key = ? LIMIT 1",
            arrayOf(conversationKey),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }
}
