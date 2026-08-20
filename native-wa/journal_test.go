package nativewa

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"path/filepath"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

func testJournal(t *testing.T) (*sql.DB, *eventJournal) {
	t.Helper()
	db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "native-test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	journal, err := openJournal(db)
	if err != nil {
		t.Fatal(err)
	}
	return db, journal
}

func TestJournalPersistsOrderedReplay(t *testing.T) {
	_, journal := testJournal(t)
	for _, name := range []string{"connection", "message", "delivery"} {
		_, _, err := journal.append(map[string]any{"type": name})
		if err != nil {
			t.Fatal(err)
		}
	}

	bounds, err := journal.bounds()
	if err != nil {
		t.Fatal(err)
	}
	if bounds.Oldest != 1 || bounds.Latest != 3 || bounds.Count != 3 {
		t.Fatalf("unexpected bounds: %+v", bounds)
	}

	replayed, err := collectReplay(journal, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(replayed) != 2 {
		t.Fatalf("expected two frames, got %d", len(replayed))
	}
	var second map[string]any
	if err = json.Unmarshal(replayed[0], &second); err != nil {
		t.Fatal(err)
	}
	if second["type"] != "message" || second["sequence"] != float64(2) || second["v"] != float64(protocolVersion) {
		t.Fatalf("unexpected replay frame: %#v", second)
	}
}

// collectReplay materializes a streamed replay, which is what the assertions want
// even though production deliberately writes frames straight to the socket.
func collectReplay(journal *eventJournal, after int64) ([][]byte, error) {
	var frames [][]byte
	_, err := journal.replayEach(after, func(raw []byte) error {
		// replayEach reuses nothing, but copying keeps the helper honest if it ever does.
		frames = append(frames, append([]byte(nil), raw...))
		return nil
	})
	return frames, err
}

// TestJournalPrunesInBatches pins the retention behaviour that moved off the
// per-append path: the table may run up to eventPruneEvery rows past the
// retention, and must be cut back once the batch boundary is crossed.
func TestJournalPrunesInBatches(t *testing.T) {
	_, journal := testJournal(t)
	total := journalRetention + eventPruneEvery + 5
	for index := 0; index < total; index++ {
		if _, _, err := journal.append(map[string]any{"type": "message"}); err != nil {
			t.Fatal(err)
		}
	}
	bounds, err := journal.bounds()
	if err != nil {
		t.Fatal(err)
	}
	if bounds.Latest != int64(total) {
		t.Fatalf("expected head %d, got %d", total, bounds.Latest)
	}
	if bounds.Count > journalRetention+eventPruneEvery {
		t.Fatalf("retention not applied: %d rows kept", bounds.Count)
	}
	// A resume from the very start must still deliver every row the table holds,
	// or the client sees a gap it cannot close.
	replayed, err := collectReplay(journal, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(replayed) != bounds.Count {
		t.Fatalf("replay delivered %d of %d rows", len(replayed), bounds.Count)
	}
}

func TestPublishSurfacesJournalFailure(t *testing.T) {
	db, journal := testJournal(t)
	runtime := &Runtime{journal: journal}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	if err := runtime.publish(map[string]any{"type": "incoming"}); err == nil {
		t.Fatal("journal failure was silently reported as a successful publish")
	}
}

// The sweep moved off the per-send path — it now runs once per actionPruneEvery
// stores — but it must still actually happen, or the idempotency table grows for
// every message the bot ever sent. Aged rows may linger for a batch; they may not
// linger forever, and a row inside the retention window must never be swept.
func TestJournalActionResultsPruneAgedRows(t *testing.T) {
	db, journal := testJournal(t)
	if err := journal.storeActionResult("old-request", "send_text", `{"text":"hi"}`, `{"ok":true}`); err != nil {
		t.Fatal(err)
	}
	// Backdate past the retention window; the next sweep has to take it.
	aged := time.Now().Add(-actionResultRetention - time.Hour).UnixMilli()
	if _, err := db.Exec(`UPDATE native_action_results SET created_at_ms = ?`, aged); err != nil {
		t.Fatal(err)
	}
	if _, found, err := journal.actionResult("old-request", "send_text", `{"text":"hi"}`); err != nil || !found {
		t.Fatalf("aged row vanished before a sweep was due, found=%v err=%v", found, err)
	}

	for index := 0; index < actionPruneEvery; index++ {
		id := fmt.Sprintf("fresh-request-%d", index)
		if err := journal.storeActionResult(id, "send_text", `{"text":"yo"}`, `{"ok":true}`); err != nil {
			t.Fatal(err)
		}
	}

	if _, found, err := journal.actionResult("old-request", "send_text", `{"text":"hi"}`); err != nil || found {
		t.Fatalf("expected aged row to be pruned, found=%v err=%v", found, err)
	}
	if _, found, err := journal.actionResult("fresh-request-0", "send_text", `{"text":"yo"}`); err != nil || !found {
		t.Fatalf("expected fresh row to survive, found=%v err=%v", found, err)
	}
}

func TestJournalActionIdempotencyRejectsConflictingReuse(t *testing.T) {
	_, journal := testJournal(t)
	if err := journal.storeActionResult("request-1", "send_text", `{"text":"hi"}`, `{"ok":true}`); err != nil {
		t.Fatal(err)
	}
	result, found, err := journal.actionResult("request-1", "send_text", `{"text":"hi"}`)
	if err != nil || !found || result != `{"ok":true}` {
		t.Fatalf("unexpected replay result found=%v result=%q err=%v", found, result, err)
	}
	if _, _, err = journal.actionResult("request-1", "send_text", `{"text":"changed"}`); err == nil {
		t.Fatal("expected conflicting request-id reuse to fail")
	}
}

func TestJournalReservationSurvivesUntilAResultReplacesIt(t *testing.T) {
	_, journal := testJournal(t)
	payload := `{"text":"hi"}`
	if err := journal.reserveAction("request-2", "send_text", payload); err != nil {
		t.Fatal(err)
	}
	result, found, err := journal.actionResult("request-2", "send_text", payload)
	if err != nil || !found || result != actionResultInFlight {
		t.Fatalf("reservation not visible found=%v result=%q err=%v", found, result, err)
	}
	if err = journal.storeActionResult("request-2", "send_text", payload, `{"ok":true}`); err != nil {
		t.Fatal(err)
	}
	result, found, err = journal.actionResult("request-2", "send_text", payload)
	if err != nil || !found || result != `{"ok":true}` {
		t.Fatalf("result did not replace reservation found=%v result=%q err=%v", found, result, err)
	}
}
