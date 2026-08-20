package nativewa

import (
	"testing"
	"time"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

func TestIncomingDirectMessageUsesSenderPhoneAliasForChatAndAccess(t *testing.T) {
	runtime := &Runtime{}
	lid := types.NewJID("123456789", types.HiddenUserServer)
	phone := types.NewJID("491234567890", types.DefaultUserServer)
	event := &events.Message{
		Info: types.MessageInfo{
			MessageSource: types.MessageSource{
				Chat:      lid,
				Sender:    lid,
				SenderAlt: phone,
			},
			ID:        types.MessageID("message-1"),
			Timestamp: time.Unix(1_700_000_000, 0),
		},
		Message: &waE2E.Message{Conversation: proto.String("hello")},
	}

	payload := runtime.normalizedIncoming(event)
	if payload == nil {
		t.Fatal("expected normalized payload")
	}
	want := phone.String()
	assertAliasContains(t, payload["senderAliases"], want)
	assertAliasContains(t, payload["chatAliases"], want)
}

func TestIncomingOpenRouterSecretIsRedactedBeforePublicationAndJournal(t *testing.T) {
	event := testIncomingEvent("secret-message")
	event.Message.Conversation = proto.String("/apikey set " + syntheticOpenRouterKey())

	payload := (&Runtime{}).normalizedIncoming(event)
	if payload == nil {
		t.Fatal("expected normalized payload")
	}
	text, _ := payload["text"].(string)
	if text != "/apikey set [redacted-openrouter-key]" {
		t.Fatalf("secret survived normalization: %q", text)
	}
}

func syntheticOpenRouterKey() string {
	return "sk-or-" + "v1-" + "abcdefghijklmnopqrstuvwxyz012345"
}

func assertAliasContains(t *testing.T, raw any, want string) {
	t.Helper()
	aliases, ok := raw.([]string)
	if !ok {
		t.Fatalf("expected string aliases, got %T", raw)
	}
	for _, alias := range aliases {
		if alias == want {
			return
		}
	}
	t.Fatalf("expected alias %q in %#v", want, aliases)
}
