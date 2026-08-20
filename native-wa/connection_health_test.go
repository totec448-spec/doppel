package nativewa

import (
	"encoding/json"
	"testing"
	"time"
)

// publishedFrames drains everything the runtime published, in order.
func publishedFrames(t *testing.T, runtime *Runtime) []map[string]any {
	t.Helper()
	raw, err := collectReplay(runtime.journal, 0)
	if err != nil {
		t.Fatal(err)
	}
	frames := make([]map[string]any, 0, len(raw))
	for _, entry := range raw {
		var frame map[string]any
		if err := json.Unmarshal(entry, &frame); err != nil {
			t.Fatal(err)
		}
		frames = append(frames, frame)
	}
	return frames
}

func connectionStates(frames []map[string]any) []string {
	states := make([]string, 0, len(frames))
	for _, frame := range frames {
		if frame["type"] != "connection" {
			continue
		}
		state, _ := frame["state"].(string)
		states = append(states, state)
	}
	return states
}

func newHealthRuntime(t *testing.T) *Runtime {
	t.Helper()
	_, journal := testJournal(t)
	return &Runtime{journal: journal}
}

// The regression this whole rewrite exists for. A link that drops faster than the
// old two-minute stability window could never be announced as connected: the
// stability timer compared the connection epoch it captured against the current
// one, and every disconnect bumped that epoch. recentDisconnects was cleared only
// on the success path of that same timer, so the storm latched and the UI sat on
// "verbindet" indefinitely while whatsmeow kept reconnecting fine underneath.
func TestPublishLinkUpReportsConnectedWhileFlapping(t *testing.T) {
	runtime := newHealthRuntime(t)
	now := time.Now()
	// Five drops inside the storm window — far past the storm threshold.
	for i := 0; i < 5; i++ {
		runtime.recordDisconnect(now.Add(time.Duration(i) * time.Minute))
	}

	runtime.publishLinkUp("native_linked_device_connected")

	frames := publishedFrames(t, runtime)
	states := connectionStates(frames)
	if len(states) == 0 || states[len(states)-1] != "connected" {
		t.Fatalf("a flapping link must still be announced as connected, got states %v", states)
	}
	// The instability is not swallowed — it travels as a safety signal instead of
	// blocking the connected state.
	unstable := false
	for _, frame := range frames {
		if frame["type"] == "safety" && frame["kind"] == "connection_unstable" {
			unstable = true
		}
	}
	if !unstable {
		t.Fatal("expected a connection_unstable safety signal alongside the connected state")
	}
}

func TestFatalStreamErrorCodeOnlyForUnrecoverable(t *testing.T) {
	for _, code := range []string{"401", "403", "conflict", "not-authorized"} {
		if !fatalStreamErrorCode(code) {
			t.Fatalf("stream error %q must stop and ask the user", code)
		}
	}
	// 515 is the routine restart-required error right after pairing; treating it
	// as fatal is what stranded the UI on an error state it could not leave.
	for _, code := range []string{"515", "503", "", "something-new"} {
		if fatalStreamErrorCode(code) {
			t.Fatalf("stream error %q must be treated as transient", code)
		}
	}
}

func TestRecentWithinDropsOnlyExpiredDisconnects(t *testing.T) {
	now := time.Unix(10_000, 0)
	values := []time.Time{
		now.Add(-6 * time.Minute),
		now.Add(-4 * time.Minute),
		now.Add(-time.Minute),
	}
	got := recentWithin(values, now, 5*time.Minute)
	if len(got) != 2 || !got[0].Equal(now.Add(-4*time.Minute)) {
		t.Fatalf("unexpected retained disconnects: %#v", got)
	}
}

func TestRecordDisconnectReportsReconnectStorm(t *testing.T) {
	runtime := &Runtime{}
	now := time.Unix(20_000, 0)
	if got := runtime.recordDisconnect(now); got != "native_linked_device_disconnected" {
		t.Fatalf("first disconnect detail = %q", got)
	}
	if got := runtime.recordDisconnect(now.Add(time.Minute)); got != "native_linked_device_disconnected" {
		t.Fatalf("second disconnect detail = %q", got)
	}
	if got := runtime.recordDisconnect(now.Add(2 * time.Minute)); got != "native_linked_device_reconnect_storm" {
		t.Fatalf("third disconnect detail = %q", got)
	}
}
