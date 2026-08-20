package nativewa

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"time"
)

const journalRetention = 2000

// Action results exist only so a retried request id returns the first result
// instead of sending twice. A retry follows a reconnect, never a week of
// downtime, so the rows are pruned by age — without this the table grew for
// every send the bot ever made and nothing ever deleted from it.
const actionResultRetention = 7 * 24 * time.Hour

const actionResultInFlight = `{"_state":"in_flight"}`

// eventPruneEvery is how many appends may pass between retention deletes.
//
// The prune only has anything to do once the table has grown past
// journalRetention, so running it on every append paid for a MAX-scan and a
// DELETE 199 times out of 200 for nothing — on a phone, for every receipt,
// presence change and connection blip. Batching it keeps the table within
// journalRetention + eventPruneEvery rows, which the replay LIMIT already
// tolerates.
const eventPruneEvery = 200

// actionPruneEvery does the same for the action-result table, which is pruned by
// age and therefore has even less to do per call.
const actionPruneEvery = 200

type eventJournal struct {
	db *sql.DB

	// nextSequence caches the journal head so append does not re-derive it with a
	// MAX scan every time. Guarded by the caller: every append goes through
	// Runtime.publish, which holds r.publishes for the whole call. Zero means
	// "not loaded yet"; openJournal primes it.
	nextSequence int64
	appendsSinceEventPrune int
	appendsSinceActionPrune int
}

type journalBounds struct {
	Oldest int64
	Latest int64
	Count  int
}

func openJournal(db *sql.DB) (*eventJournal, error) {
	statements := []string{
		`PRAGMA journal_mode=WAL`,
		`PRAGMA busy_timeout=5000`,
		`CREATE TABLE IF NOT EXISTS native_bridge_events (
			sequence INTEGER PRIMARY KEY,
			frame_json TEXT NOT NULL,
			created_at_ms INTEGER NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS native_action_results (
			request_id TEXT PRIMARY KEY,
			action TEXT NOT NULL,
			payload_json TEXT NOT NULL,
			result_json TEXT NOT NULL,
			created_at_ms INTEGER NOT NULL
		)`,
		`CREATE INDEX IF NOT EXISTS native_action_results_created_at
			ON native_action_results(created_at_ms)`,
		`CREATE TABLE IF NOT EXISTS native_quotable_messages (
			chat_id TEXT NOT NULL,
			stanza_id TEXT NOT NULL,
			participant TEXT NOT NULL,
			message_proto BLOB NOT NULL,
			created_at_ms INTEGER NOT NULL,
			PRIMARY KEY (chat_id, stanza_id)
		)`,
		`CREATE TABLE IF NOT EXISTS native_ephemeral (
				chat_id TEXT PRIMARY KEY,
				expiration_seconds INTEGER NOT NULL,
				setting_stamp INTEGER NOT NULL,
				updated_at_ms INTEGER NOT NULL
			)`,
		`CREATE TABLE IF NOT EXISTS native_media (
			id TEXT PRIMARY KEY,
			path TEXT NOT NULL,
			mime_type TEXT NOT NULL,
			original_name TEXT NOT NULL,
			size_bytes INTEGER NOT NULL,
			sha256 TEXT NOT NULL,
			created_at_ms INTEGER NOT NULL
		)`,
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement); err != nil {
			return nil, fmt.Errorf("initialize native bridge store: %w", err)
		}
	}
	journal := &eventJournal{db: db}
	var head int64
	if err := db.QueryRow(`SELECT COALESCE(MAX(sequence), 0) FROM native_bridge_events`).Scan(&head); err != nil {
		return nil, fmt.Errorf("read native bridge journal head: %w", err)
	}
	journal.nextSequence = head + 1
	return journal, nil
}

func (j *eventJournal) append(frame map[string]any) ([]byte, int64, error) {
	if j.nextSequence <= 0 {
		// Defensive: a journal built without openJournal (tests) still gets a head.
		if err := j.db.QueryRow(`SELECT COALESCE(MAX(sequence), 0) FROM native_bridge_events`).Scan(&j.nextSequence); err != nil {
			return nil, 0, err
		}
		j.nextSequence++
	}
	sequence := j.nextSequence
	frame["v"] = protocolVersion
	frame["sequence"] = sequence
	encoded, err := json.Marshal(frame)
	if err != nil {
		return nil, 0, err
	}
	if _, err = j.db.Exec(
		`INSERT INTO native_bridge_events(sequence, frame_json, created_at_ms) VALUES(?, ?, ?)`,
		sequence,
		string(encoded),
		time.Now().UnixMilli(),
	); err != nil {
		// The sequence is not consumed on failure: publish reports the error and the
		// caller retries, which must reuse this number or the client sees a gap.
		return nil, 0, err
	}
	j.nextSequence = sequence + 1

	j.appendsSinceEventPrune++
	if j.appendsSinceEventPrune >= eventPruneEvery {
		j.appendsSinceEventPrune = 0
		// Best effort: a stale row is cheaper than turning a published event into a
		// failure that the inbound loop then retries forever.
		_, _ = j.db.Exec(
			`DELETE FROM native_bridge_events WHERE sequence <= ?`,
			sequence-journalRetention,
		)
	}
	return encoded, sequence, nil
}

func (j *eventJournal) bounds() (journalBounds, error) {
	var result journalBounds
	err := j.db.QueryRow(
		`SELECT COALESCE(MIN(sequence), 0), COALESCE(MAX(sequence), 0), COUNT(*) FROM native_bridge_events`,
	).Scan(&result.Oldest, &result.Latest, &result.Count)
	return result, err
}

// replayBatch is how many frames are held in memory at once during a resume.
//
// Collecting the whole replay first meant up to journalRetention frames at the
// 100 KB per-frame text cap — enough to matter on a phone. Reading it in batches
// keeps the allocation bounded and, just as importantly, does not hold a SQLite
// read cursor open across the socket writes, which each carry a 15 s deadline.
const replayBatch = 200

// replayEach streams every frame after the cursor, in sequence order, and stops at
// the first write error. count reports how many frames were handed to write.
func (j *eventJournal) replayEach(after int64, write func([]byte) error) (int, error) {
	cursor := after
	total := 0
	for {
		rows, err := j.db.Query(
			`SELECT sequence, frame_json FROM native_bridge_events
			 WHERE sequence > ? ORDER BY sequence ASC LIMIT ?`,
			cursor,
			replayBatch,
		)
		if err != nil {
			return total, err
		}
		type frame struct {
			sequence int64
			raw      string
		}
		batch := make([]frame, 0, replayBatch)
		for rows.Next() {
			var item frame
			if err = rows.Scan(&item.sequence, &item.raw); err != nil {
				rows.Close()
				return total, err
			}
			batch = append(batch, item)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return total, err
		}
		if len(batch) == 0 {
			return total, nil
		}
		for _, item := range batch {
			if err = write([]byte(item.raw)); err != nil {
				return total, err
			}
			cursor = item.sequence
			total++
		}
	}
}

// replayCount reports how many frames a resume from after would deliver, without
// materializing any of them. The handshake needs it for the welcome frame, which
// is written before the replay itself starts.
func (j *eventJournal) replayCount(after int64) (int, error) {
	var count int
	err := j.db.QueryRow(
		`SELECT COUNT(*) FROM native_bridge_events WHERE sequence > ?`,
		after,
	).Scan(&count)
	return count, err
}

func (j *eventJournal) actionResult(requestID, action, payload string) (string, bool, error) {
	var storedAction, storedPayload, result string
	err := j.db.QueryRow(
		`SELECT action, payload_json, result_json FROM native_action_results WHERE request_id = ?`,
		requestID,
	).Scan(&storedAction, &storedPayload, &result)
	if err == sql.ErrNoRows {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	if storedAction != action || storedPayload != payload {
		return "", false, fmt.Errorf("request id was reused with different input")
	}
	return result, true, nil
}

// reserveAction closes the external-effect gap: after this row commits, a process crash can no
// longer make the same request ID look unseen and send it again. A surviving in-flight row means
// the outcome is ambiguous and is deliberately not retried automatically.
func (j *eventJournal) reserveAction(requestID, action, payload string) error {
	_, err := j.db.Exec(
		`INSERT INTO native_action_results
		 (request_id, action, payload_json, result_json, created_at_ms)
		 VALUES (?, ?, ?, ?, ?)`,
		requestID,
		action,
		payload,
		actionResultInFlight,
		time.Now().UnixMilli(),
	)
	return err
}

// releaseAction removes a reservation that provably had no effect on the account,
// so the retry that follows is treated as a first attempt instead of being refused
// with `idempotency_in_doubt`.
//
// The result_json guard is what makes this safe: only a row that is still in
// flight can be deleted, so a reservation that has already been resolved into a
// real result can never be dropped and replayed.
func (j *eventJournal) releaseAction(requestID, action, payload string) error {
	_, err := j.db.Exec(
		`DELETE FROM native_action_results
		 WHERE request_id = ? AND action = ? AND payload_json = ? AND result_json = ?`,
		requestID,
		action,
		payload,
		actionResultInFlight,
	)
	return err
}

func (j *eventJournal) storeActionResult(requestID, action, payload, result string) error {
	now := time.Now()
	updated, err := j.db.Exec(
		`UPDATE native_action_results
		 SET result_json = ?, created_at_ms = ?
		 WHERE request_id = ? AND action = ? AND payload_json = ?`,
		result,
		now.UnixMilli(),
		requestID,
		action,
		payload,
	)
	if err != nil {
		return err
	}
	rows, err := updated.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		_, err = j.db.Exec(
			`INSERT INTO native_action_results
		 (request_id, action, payload_json, result_json, created_at_ms)
		 VALUES (?, ?, ?, ?, ?)`,
			requestID,
			action,
			payload,
			result,
			now.UnixMilli(),
		)
		if err != nil {
			return err
		}
	}
	// Best effort, and only every actionPruneEvery calls: rows are pruned by age,
	// so a scan per send bought nothing. A failed prune must never turn a delivered
	// send into a reported failure.
	j.appendsSinceActionPrune++
	if j.appendsSinceActionPrune >= actionPruneEvery {
		j.appendsSinceActionPrune = 0
		_, _ = j.db.Exec(
			`DELETE FROM native_action_results WHERE created_at_ms < ?`,
			now.Add(-actionResultRetention).UnixMilli(),
		)
	}
	return nil
}
