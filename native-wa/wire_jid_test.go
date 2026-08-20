package nativewa

import (
	"testing"

	"go.mau.fi/whatsmeow/types"
)

// A published event is journalled durably, so an address the Android decoder
// rejects is replayed into the same rejection on every reconnect. These are the
// senders that used to end up there.
func TestWireJIDRejectsAddressesTheContractCannotCarry(t *testing.T) {
	cases := []struct {
		name string
		jid  types.JID
		want string
	}{
		{"user", types.NewJID("4915112345678", types.DefaultUserServer), "4915112345678@s.whatsapp.net"},
		{"hidden user", types.NewJID("123456789", types.HiddenUserServer), "123456789@lid"},
		{"group", types.NewJID("120363000000000000", types.GroupServer), "120363000000000000@g.us"},
		{"status update", types.StatusBroadcastJID, ""},
		{"broadcast list", types.NewJID("4915112345678", types.BroadcastServer), ""},
		{"channel", types.NewJID("120363000000000000", types.NewsletterServer), ""},
		{"meta ai", types.NewMetaAIJID, ""},
		{"hosted business", types.NewJID("4915112345678", types.HostedServer), ""},
		{"empty", types.EmptyJID, ""},
	}
	for _, item := range cases {
		t.Run(item.name, func(t *testing.T) {
			if got := wireJID(item.jid); got != item.want {
				t.Fatalf("wireJID(%s) = %q, want %q", item.jid, got, item.want)
			}
		})
	}
}

// IsBroadcastList deliberately excludes the status address, which is why the
// incoming filter alone never kept status updates out of the journal.
func TestStatusBroadcastIsNotCoveredByBroadcastListFilter(t *testing.T) {
	if types.StatusBroadcastJID.IsBroadcastList() {
		t.Fatal("status updates are expected to slip past IsBroadcastList")
	}
	if wireJID(types.StatusBroadcastJID) != "" {
		t.Fatal("status updates must not reach the wire contract")
	}
}
