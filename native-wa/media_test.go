package nativewa

import (
	"crypto/sha256"
	"encoding/hex"
	"math"
	"os"
	"runtime"
	"strings"
	"testing"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func TestMediaStoreWritesPrivateVerifiedPayload(t *testing.T) {
	db, _ := testJournal(t)
	store, err := newMediaStore(db, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	payload := "voice-note-payload"
	record, err := store.putReader(
		strings.NewReader(payload),
		"audio/ogg; codecs=opus",
		"../../unsafe voice.ogg",
		int64(len(payload)),
	)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256([]byte(payload))
	if record.MimeType != "audio/ogg" ||
		record.OriginalName != "unsafe_voice.ogg" ||
		record.Size != int64(len(payload)) ||
		record.SHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("unexpected media record: %+v", record)
	}
	info, err := os.Stat(record.Path)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0 {
		t.Fatalf("media file is accessible outside the app owner: %v", info.Mode())
	}
	loaded, err := store.get(record.ID)
	if err != nil || loaded.SHA256 != record.SHA256 {
		t.Fatalf("stored media did not round-trip: %+v, %v", loaded, err)
	}
}

func TestMediaStoreRejectsOversizedDeclarationAndUnsafeID(t *testing.T) {
	db, _ := testJournal(t)
	store, err := newMediaStore(db, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.putReader(strings.NewReader("tiny"), "text/plain", "a.txt", maxMediaBytes+1); err == nil {
		t.Fatal("expected oversized declared payload to fail")
	}
	if _, err = store.get("../../database"); !os.IsNotExist(err) {
		t.Fatalf("unsafe media ID should look absent, got %v", err)
	}
}

func TestIncomingMediaBoundsUnsignedDeclarationAndFileWrites(t *testing.T) {
	message := &waE2E.Message{ImageMessage: &waE2E.ImageMessage{FileLength: proto.Uint64(math.MaxUint64)}}
	_, _, _, _, declared, _, _ := mediaOf(message)
	if declared != math.MaxUint64 || declared <= uint64(maxMediaBytes) {
		t.Fatalf("oversized unsigned declaration became %d", declared)
	}

	raw, err := os.CreateTemp(t.TempDir(), "bounded-media")
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	file := &boundedMediaFile{File: raw}
	if _, err = file.WriteAt([]byte{1}, maxMediaBytes+31); err != nil {
		t.Fatalf("encrypted padding allowance should fit: %v", err)
	}
	if _, err = file.WriteAt([]byte{1}, maxMediaBytes+32); err == nil {
		t.Fatal("write beyond the bounded encrypted payload was accepted")
	}
}

func TestQuotedTextUsesTheSameSecretAndSizeBoundaryAsTopLevelText(t *testing.T) {
	secret := syntheticOpenRouterKey()
	message := &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
		ContextInfo: &waE2E.ContextInfo{
			StanzaID:      proto.String("quoted"),
			QuotedMessage: &waE2E.Message{Conversation: proto.String(secret + strings.Repeat("x", maxIncomingTextBytes))},
		},
	}}
	quoted := quotedOf(message)
	text, _ := quoted["text"].(string)
	if strings.Contains(text, secret) || !strings.Contains(text, "[redacted-openrouter-key]") {
		t.Fatalf("quoted secret was not redacted: %q", text)
	}
	if len(text) > maxIncomingTextBytes {
		t.Fatalf("quoted text exceeds wire limit: %d", len(text))
	}
}
