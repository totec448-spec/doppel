package nativewa

import (
	"strings"
	"testing"
)

func TestSanitizeLogMessageRedactsPrivateValues(t *testing.T) {
	// Assemble secret/path shapes at runtime so the release-hygiene scanner can still reject a
	// literal credential or personal home path if somebody accidentally adds one to a test.
	bearer := "Bearer " + "example_token_value_1234567890"
	openRouterKey := "sk-" + "or-v1-" + "example_token_value_1234567890"
	windowsPath := `C:` + `\Users\private-name\checkout\file.go`
	privateValues := []string{
		"12345678901@s.whatsapp.net",
		"98765432101@lid",
		bearer,
		openRouterKey,
		"ABCD-EFGH",
		windowsPath,
		"+49 170 1234567",
	}
	input := "contact=" + privateValues[0] +
		" lid=" + privateValues[1] +
		" auth=" + privateValues[2] +
		" key=" + privateValues[3] +
		" pairing code: " + privateValues[4] +
		" path=" + privateValues[5] +
		" phone=" + privateValues[6]

	result := sanitizeLogMessage(input)

	for _, privateValue := range privateValues {
		if strings.Contains(result, privateValue) {
			t.Fatalf("sanitized log still contains a private value: %q", privateValue)
		}
	}
	for _, marker := range []string{
		"<redacted-jid>",
		"Bearer <redacted>",
		"<redacted-secret>",
		"pairing code: <redacted>",
		"<redacted-path>",
		"<redacted-number>",
	} {
		if !strings.Contains(result, marker) {
			t.Fatalf("sanitized log is missing marker %q: %s", marker, result)
		}
	}
}

func TestSanitizeLogMessageKeepsCoarseDiagnosticText(t *testing.T) {
	input := "websocket disconnected with status 515"
	if result := sanitizeLogMessage(input); result != input {
		t.Fatalf("non-private diagnostic changed: %q", result)
	}
}
