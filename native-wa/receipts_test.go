package nativewa

import (
	"testing"
)

func readKey(chat, id, participant string) map[string]any {
	key := map[string]any{"chatId": chat, "id": id}
	if participant != "" {
		key["participant"] = participant
	}
	return key
}

// A real client acknowledges a backlog with one receipt stanza per conversation
// partner, carrying the whole ID list. Ten stanzas in a row for ten unread
// messages is the pattern this grouping exists to avoid.
func TestGroupReceiptsEmitsOneReceiptPerChatAndParticipant(t *testing.T) {
	groups, count, err := groupReceipts([]any{
		readKey("4915100000001@s.whatsapp.net", "A", ""),
		readKey("4915100000001@s.whatsapp.net", "B", ""),
		readKey("120363000000000001@g.us", "C", "4915100000002@s.whatsapp.net"),
		readKey("4915100000001@s.whatsapp.net", "D", ""),
		readKey("120363000000000001@g.us", "E", "4915100000003@s.whatsapp.net"),
	})
	if err != nil {
		t.Fatalf("groupReceipts: %v", err)
	}
	if count != 5 {
		t.Fatalf("count = %d, want 5", count)
	}
	if len(groups) != 3 {
		t.Fatalf("receipts = %d, want 3", len(groups))
	}
	if len(groups[0].ids) != 3 {
		t.Fatalf("first receipt carried %d ids, want 3", len(groups[0].ids))
	}
	// Grouping must not reorder the receipts the engine asked for.
	if groups[0].chat.User != "4915100000001" || groups[1].sender.User != "4915100000002" {
		t.Fatalf("receipt order drifted: %v", groups)
	}
}

func TestGroupReceiptsRejectsUnusableInput(t *testing.T) {
	for name, raw := range map[string]any{
		"missing":     nil,
		"empty":       []any{},
		"not a key":   []any{"4915100000001@s.whatsapp.net"},
		"invalid jid": []any{readKey("not-a-jid", "A", "")},
	} {
		if _, _, err := groupReceipts(raw); err == nil {
			t.Errorf("%s input was accepted", name)
		}
	}
	tooMany := make([]any, 0, 101)
	for index := 0; index < 101; index++ {
		tooMany = append(tooMany, readKey("4915100000001@s.whatsapp.net", "A", ""))
	}
	if _, _, err := groupReceipts(tooMany); err == nil {
		t.Error("more than 100 keys were accepted")
	}
}

// WhatsApp can announce one ring twice (an offer and an offer notice). Two
// rejection stanzas for the same call is something no real client sends.
func TestClaimCallAnswersEachRingOnce(t *testing.T) {
	runtime := &Runtime{}
	if !runtime.claimCall("call-1") {
		t.Fatal("the first offer was not claimed")
	}
	if runtime.claimCall("call-1") {
		t.Fatal("the repeated offer was claimed a second time")
	}
	if !runtime.claimCall("call-2") {
		t.Fatal("a different call was not claimed")
	}
	runtime.mu.Lock()
	runtime.stopped = true
	runtime.mu.Unlock()
	if runtime.claimCall("call-3") {
		t.Fatal("a stopped runtime still claimed a call")
	}
}
