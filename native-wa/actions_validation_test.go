package nativewa

import (
	"strings"
	"testing"
)

func TestOutgoingTextUsesTheSameBoundForSendAndEdit(t *testing.T) {
	if text, err := outgoingText(map[string]any{"text": " hello "}); err != nil || text != "hello" {
		t.Fatalf("unexpected normalized text %q, %v", text, err)
	}
	if _, err := outgoingText(map[string]any{"text": strings.Repeat("x", 16384)}); err != nil {
		t.Fatalf("boundary text was rejected: %v", err)
	}
	if _, err := outgoingText(map[string]any{"text": strings.Repeat("x", 16385)}); err == nil {
		t.Fatal("oversized edit/send text was accepted")
	}
}
