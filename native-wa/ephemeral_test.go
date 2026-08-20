package nativewa

import (
	"testing"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

func testEphemeralRuntime(t *testing.T) (*Runtime, types.JID) {
	t.Helper()
	db, journal := testJournal(t)
	_ = db
	return &Runtime{journal: journal, ephemeral: make(map[string]ephemeralSetting)},
		types.NewJID("491234567890", types.DefaultUserServer)
}

func TestEphemeralTimerIsLearnedFromIncomingContext(t *testing.T) {
	runtime, chat := testEphemeralRuntime(t)
	incoming := &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: proto.String("hi"),
			ContextInfo: &waE2E.ContextInfo{
				Expiration:                proto.Uint32(604800),
				EphemeralSettingTimestamp: proto.Int64(1_700_000_000),
			},
		},
	}

	runtime.learnEphemeralFromIncoming(chat, incoming, 1_700_000_000)

	if got := runtime.ephemeralFor(chat).expiration; got != 604800 {
		t.Fatalf("expected the seven-day timer to be learned, got %d", got)
	}
}

func TestEphemeralSettingChangeIsLearnedFromProtocolMessage(t *testing.T) {
	runtime, chat := testEphemeralRuntime(t)
	change := &waE2E.Message{
		ProtocolMessage: &waE2E.ProtocolMessage{
			Type:                      waE2E.ProtocolMessage_EPHEMERAL_SETTING.Enum(),
			EphemeralExpiration:       proto.Uint32(86400),
			EphemeralSettingTimestamp: proto.Int64(1_700_000_100),
		},
	}

	runtime.learnEphemeralFromIncoming(chat, change, 0)

	if got := runtime.ephemeralFor(chat).expiration; got != 86400 {
		t.Fatalf("expected the 24 hour timer, got %d", got)
	}

	// Turning the feature off is also a setting, and it has to win when newer.
	off := &waE2E.Message{
		ProtocolMessage: &waE2E.ProtocolMessage{
			Type:                      waE2E.ProtocolMessage_EPHEMERAL_SETTING.Enum(),
			EphemeralExpiration:       proto.Uint32(0),
			EphemeralSettingTimestamp: proto.Int64(1_700_000_200),
		},
	}
	runtime.learnEphemeralFromIncoming(chat, off, 0)
	if got := runtime.ephemeralFor(chat).expiration; got != 0 {
		t.Fatalf("expected the timer to be cleared, got %d", got)
	}
}

// Messages can arrive out of order; an older setting must not undo a newer one.
func TestEphemeralIgnoresStalerSetting(t *testing.T) {
	runtime, chat := testEphemeralRuntime(t)
	runtime.rememberEphemeral(chat, 604800, 1_700_000_200)
	runtime.rememberEphemeral(chat, 0, 1_700_000_100)

	if got := runtime.ephemeralFor(chat).expiration; got != 604800 {
		t.Fatalf("expected the newer setting to survive, got %d", got)
	}
}

func TestOutgoingTextIsStampedAndKeepsItsQuote(t *testing.T) {
	runtime, chat := testEphemeralRuntime(t)
	runtime.rememberEphemeral(chat, 86400, 1_700_000_000)

	plain := runtime.applyEphemeral(chat, &waE2E.Message{Conversation: proto.String("hey")})
	if plain.GetConversation() != "" {
		t.Fatal("a plain conversation cannot carry context and must be promoted")
	}
	if plain.GetExtendedTextMessage().GetText() != "hey" {
		t.Fatalf("promotion lost the text: %q", plain.GetExtendedTextMessage().GetText())
	}
	if got := plain.GetExtendedTextMessage().GetContextInfo().GetExpiration(); got != 86400 {
		t.Fatalf("expected the outgoing message to expire with the chat, got %d", got)
	}

	quoted := runtime.applyEphemeral(chat, &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text:        proto.String("yes"),
			ContextInfo: &waE2E.ContextInfo{StanzaID: proto.String("quoted-id")},
		},
	})
	context := quoted.GetExtendedTextMessage().GetContextInfo()
	if context.GetStanzaID() != "quoted-id" {
		t.Fatal("stamping must not drop the quote")
	}
	if context.GetExpiration() != 86400 {
		t.Fatalf("expected the quoted reply to be stamped too, got %d", context.GetExpiration())
	}
}

func TestOutgoingMessageIsUntouchedWithoutATimer(t *testing.T) {
	runtime, chat := testEphemeralRuntime(t)
	message := runtime.applyEphemeral(chat, &waE2E.Message{Conversation: proto.String("hey")})

	// A normal chat keeps the plain Conversation form it has always used.
	if message.GetConversation() != "hey" {
		t.Fatalf("expected an untouched conversation message, got %#v", message)
	}
}

func TestEphemeralSettingSurvivesRestart(t *testing.T) {
	db, journal := testJournal(t)
	chat := types.NewJID("491234567890", types.DefaultUserServer)
	runtime := &Runtime{journal: journal, ephemeral: make(map[string]ephemeralSetting)}
	runtime.rememberEphemeral(chat, 604800, 1_700_000_000)

	// A restart reads the timers back before the socket comes up, so the next
	// reply does not go out unstamped.
	reopened, err := openJournal(db)
	if err != nil {
		t.Fatal(err)
	}
	stored, err := reopened.loadEphemeral()
	if err != nil {
		t.Fatal(err)
	}
	restarted := &Runtime{journal: reopened, ephemeral: stored}
	if got := restarted.ephemeralFor(chat).expiration; got != 604800 {
		t.Fatalf("expected the timer to survive a restart, got %d", got)
	}
}
