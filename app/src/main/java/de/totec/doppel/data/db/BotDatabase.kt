package de.totec.doppel.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single process-wide database helper.
 *
 * WAL lets bridge/outbox writers coexist with UI readers. NORMAL synchronous mode retains WAL
 * crash safety while avoiding the extra fsync cost of FULL for every commit. A finite busy timeout
 * handles short write contention without a retry loop or background worker.
 */
class BotDatabase(
    context: Context,
    databaseName: String = BotDbSchema.DATABASE_NAME,
) : SQLiteOpenHelper(
    context.applicationContext ?: context,
    databaseName,
    null,
    BotDbSchema.VERSION,
) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        // Android classifies PRAGMA assignments as result-bearing queries on
        // recent SQLite builds. execSQL() therefore crashes before
        // Application.onCreate; moving the cursor executes and consumes the
        // assignment on the configured connection.
        db.applyPragma("PRAGMA busy_timeout=5000")
        db.applyPragma("PRAGMA synchronous=NORMAL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        BotDbSchema.create(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        BotDbSchema.upgrade(db, oldVersion, newVersion)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException(
            "Refusing destructive Doppel database downgrade $oldVersion -> $newVersion",
        )
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            BotDbSchema.ensureCurrent(db)
        }
    }
}

private fun SQLiteDatabase.applyPragma(statement: String) {
    rawQuery(statement, null).use { cursor ->
        // Some vendor SQLite builds return the effective value, while others
        // apply the assignment and expose an empty cursor. Advancing the cursor
        // is sufficient to execute the lazy rawQuery in both cases.
        cursor.moveToFirst()
    }
}
