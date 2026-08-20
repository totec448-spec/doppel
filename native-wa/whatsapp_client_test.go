package nativewa

import (
	"testing"
	"time"

	waStore "go.mau.fi/whatsmeow/store"
)

// Logout deletes a whatsmeow Device permanently. The replacement must own both the Runtime store
// pointer and the client before another PairPhone call starts, and account-scoped caches must not
// survive into the new phone number.
func TestInstallWhatsAppClientReplacesDeletedAccountState(t *testing.T) {
	first := &waStore.Device{}
	runtime := &Runtime{
		device:          first,
		groupNames:      map[string]string{"old@g.us": "old"},
		incomingSeen:    map[string]time.Time{"old": time.Now()},
		blocklistMirror: map[string]bool{"old@s.whatsapp.net": true},
		safetyCached:    map[string]any{"old": true},
		handledCalls:    map[string]time.Time{"old": time.Now()},
	}

	second := &waStore.Device{}
	if err := runtime.installWhatsAppClient(second); err != nil {
		t.Fatal(err)
	}
	if runtime.device != second || runtime.waClient == nil || runtime.waClient.Store != second {
		t.Fatal("replacement device and client were not installed atomically")
	}
	if len(runtime.groupNames) != 0 || runtime.groupCatalogLoaded {
		t.Fatal("group cache survived account replacement")
	}
	if len(runtime.incomingSeen) != 0 || len(runtime.handledCalls) != 0 {
		t.Fatal("message or call dedupe survived account replacement")
	}
	if runtime.blocklistMirror != nil || runtime.safetyCached != nil {
		t.Fatal("safety state survived account replacement")
	}
}
