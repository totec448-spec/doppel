package nativewa

import (
	"context"
	"errors"
	"fmt"
	"testing"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// TestServerErrorCodeSurvivesBothShapes is the guard the reach-out time lock
// depends on. Everything account-level — 463, 419/429, 401/403 — is derived from
// this number, and when it silently returns 0 the bot keeps sending into a live
// restriction, which is what turns one strike into a ban. Sends and info queries
// report the code in two completely different shapes, so both are pinned here: a
// whatsmeow update that changes either one has to fail this test instead of
// quietly disabling the safeguard.
func TestServerErrorCodeSurvivesBothShapes(t *testing.T) {
	sendShape := fmt.Errorf("%w %d", whatsmeow.ErrServerReturnedError, 463)
	if code := whatsmeowServerErrorCode(sendShape); code != 463 {
		t.Fatalf("send-shaped 463 classified as %d", code)
	}
	if classified, _ := classifyActionError(sendShape); classified != "timelock_463" {
		t.Fatalf("send-shaped 463 classified as %q", classified)
	}

	iqShape := &whatsmeow.IQError{Code: 463, Text: "reachout-timelock"}
	if code := whatsmeowServerErrorCode(iqShape); code != 463 {
		t.Fatalf("IQ-shaped 463 classified as %d", code)
	}

	wrappedIQ := fmt.Errorf("update WhatsApp blocklist: %w", &whatsmeow.IQError{Code: 400, Text: "bad-request"})
	if code := whatsmeowServerErrorCode(wrappedIQ); code != 400 {
		t.Fatalf("wrapped IQ error classified as %d", code)
	}

	if code := whatsmeowServerErrorCode(errors.New("some unrelated failure 12345")); code != 0 {
		t.Fatalf("unrelated error read as status %d", code)
	}
}

// TestAmbiguityOnlyForRequestsThatReachedWhatsApp pins the rule that makes the
// outbox's retry ladder reachable again. A reservation may only survive a failure
// that might have had an effect on the account; anything rejected before the wire
// has to release it, or the very first retry is answered with
// `idempotency_in_doubt` and the message is dead-lettered without ever being sent.
func TestAmbiguityOnlyForRequestsThatReachedWhatsApp(t *testing.T) {
	preflight := []error{
		fmt.Errorf("invalid WhatsApp JID"),
		fmt.Errorf("text is too long"),
		fmt.Errorf("media upload not found"),
		fmt.Errorf("unsupported media kind"),
		whatsmeow.ErrNotLoggedIn,
	}
	for _, err := range preflight {
		if ambiguousActionError(err) {
			t.Fatalf("pre-wire failure treated as ambiguous: %v", err)
		}
	}

	ambiguous := []error{
		wireError(context.DeadlineExceeded),
		wireError(fmt.Errorf("%w %d", whatsmeow.ErrServerReturnedError, 463)),
		context.DeadlineExceeded,
		whatsmeow.ErrMessageTimedOut,
		errIdempotencyInDoubt,
	}
	for _, err := range ambiguous {
		if !ambiguousActionError(err) {
			t.Fatalf("post-wire failure treated as retryable: %v", err)
		}
	}

	// Wrapping must not disturb classification: the code is what arms the safety
	// lock, and it is read out of the original error.
	if code, _ := classifyActionError(wireError(fmt.Errorf("%w %d", whatsmeow.ErrServerReturnedError, 429))); code != "server_error_429" {
		t.Fatalf("wrapped send error classified as %q", code)
	}
}

// TestReleaseActionOnlyDropsInFlightRows guards the one way this could lose a
// message: releasing a reservation that had already been resolved into a real
// result would let the same send run twice.
func TestReleaseActionOnlyDropsInFlightRows(t *testing.T) {
	_, journal := testJournal(t)
	if err := journal.reserveAction("req-1", "send_text", `{"a":1}`); err != nil {
		t.Fatal(err)
	}
	if err := journal.releaseAction("req-1", "send_text", `{"a":1}`); err != nil {
		t.Fatal(err)
	}
	if _, found, err := journal.actionResult("req-1", "send_text", `{"a":1}`); err != nil || found {
		t.Fatalf("in-flight reservation survived release (found=%v, err=%v)", found, err)
	}

	if err := journal.reserveAction("req-2", "send_text", `{"a":2}`); err != nil {
		t.Fatal(err)
	}
	if err := journal.storeActionResult("req-2", "send_text", `{"a":2}`, `{"ok":true}`); err != nil {
		t.Fatal(err)
	}
	if err := journal.releaseAction("req-2", "send_text", `{"a":2}`); err != nil {
		t.Fatal(err)
	}
	cached, found, err := journal.actionResult("req-2", "send_text", `{"a":2}`)
	if err != nil || !found || cached != `{"ok":true}` {
		t.Fatalf("settled result was released: cached=%q found=%v err=%v", cached, found, err)
	}
}

// TestBlocklistChangePushAvoidsAReadBack covers the traffic reduction: a change
// WhatsApp pushes to us must be folded into the mirror instead of triggering yet
// another blocklist query.
func TestBlocklistChangePushAvoidsAReadBack(t *testing.T) {
	runtime := &Runtime{}
	blocked := mustTestJID(t, "49123456789@s.whatsapp.net")
	added := mustTestJID(t, "49111111111@s.whatsapp.net")

	runtime.rememberBlocklist(&types.Blocklist{JIDs: []types.JID{blocked}, DHash: "hash-1"})
	if _, ok := runtime.cachedBlocklist(); !ok {
		t.Fatal("mirror did not warm up")
	}

	updated, ok := runtime.applyBlocklistChanges(&events.Blocklist{
		Changes: []events.BlocklistChange{
			{JID: added, Action: events.BlocklistChangeActionBlock},
			{JID: blocked, Action: events.BlocklistChangeActionUnblock},
		},
	})
	if !ok {
		t.Fatal("change push was not applied locally")
	}
	if len(updated.JIDs) != 1 || normalizeJID(updated.JIDs[0]) != normalizeJID(added) {
		t.Fatalf("mirror did not track the push: %+v", updated.JIDs)
	}

	// A push that cannot be reconciled has to fall back to a real read.
	if _, ok := runtime.applyBlocklistChanges(&events.Blocklist{Action: "modify"}); ok {
		t.Fatal("a modify push must force a full re-read")
	}
}
