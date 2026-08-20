package nativewa

import (
	"testing"
	"time"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

// testIncomingShards builds the same lane set production uses, with a settable
// depth so the backpressure test can fill one.
func testIncomingShards(depth int) []chan *events.Message {
	lanes := make([]chan *events.Message, incomingShards)
	for index := range lanes {
		lanes[index] = make(chan *events.Message, depth)
	}
	return lanes
}

// queuedIncoming counts everything waiting across all lanes.
func queuedIncoming(runtime *Runtime) int {
	total := 0
	for _, lane := range runtime.incoming {
		total += len(lane)
	}
	return total
}

// laneFor returns the lane an event was routed to, which for a single chat is
// always the same one — that is exactly the ordering guarantee under test.
func laneFor(runtime *Runtime, event *events.Message) chan *events.Message {
	return runtime.incoming[incomingShardFor(event.Info.Chat)]
}

func testIncomingEvent(id string) *events.Message {
	sender := types.NewJID("491234567890", types.DefaultUserServer)
	return &events.Message{
		Info: types.MessageInfo{
			MessageSource: types.MessageSource{Chat: sender, Sender: sender},
			ID:            types.MessageID(id),
			Timestamp:     time.Unix(1_700_000_000, 0),
		},
		Message: &waE2E.Message{Conversation: proto.String("hello")},
	}
}

// whatsmeow's handler queue processes one node at a time and waits on the
// handler, so normalization — which downloads media synchronously — must not run
// on that goroutine. The handler only hands the event over.
func TestHandleIncomingMessageHandsOffWithoutNormalizing(t *testing.T) {
	runtime := &Runtime{
		incoming: testIncomingShards(incomingQueueDepth),
		shutdown: make(chan struct{}),
	}

	first := testIncomingEvent("first")
	runtime.handleIncomingMessage(first)
	runtime.handleIncomingMessage(testIncomingEvent("second"))

	if queuedIncoming(runtime) != 2 {
		t.Fatalf("expected both events queued, got %d", queuedIncoming(runtime))
	}
	// Order is what the client's sequence contract depends on. Sharding is by
	// chat, so one conversation stays on one lane and keeps its order.
	lane := laneFor(runtime, first)
	if len(lane) != 2 {
		t.Fatalf("one chat must stay on one lane, got %d of 2 there", len(lane))
	}
	if id := (<-lane).Info.ID; id != "first" {
		t.Fatalf("expected the first event first, got %q", id)
	}
	if id := (<-lane).Info.ID; id != "second" {
		t.Fatalf("expected the second event second, got %q", id)
	}
}

// Two different chats must be able to make progress independently — that is the
// whole point of the lanes, since one slow media download used to stall every
// other conversation behind it.
func TestIncomingShardingSeparatesChats(t *testing.T) {
	seen := map[int]bool{}
	for _, number := range []string{"491234567890", "4915112345", "4917699999", "436601111", "3312345678", "12025550123"} {
		seen[incomingShardFor(types.NewJID(number, types.DefaultUserServer))] = true
	}
	if len(seen) < 2 {
		t.Fatalf("every chat landed on the same lane: %v", seen)
	}
	// A device-suffixed JID is the same conversation and must not jump lanes,
	// or replies would overtake the message they answer.
	chat := types.NewJID("491234567890", types.DefaultUserServer)
	withDevice := chat
	withDevice.Device = 3
	if incomingShardFor(chat) != incomingShardFor(withDevice) {
		t.Fatal("a device-suffixed JID was routed to a different lane than its chat")
	}
}

func TestHandleIncomingMessageDeduplicatesTheSameWhatsAppEvent(t *testing.T) {
	runtime := &Runtime{
		incoming: testIncomingShards(incomingQueueDepth),
		shutdown: make(chan struct{}),
	}
	event := testIncomingEvent("duplicate")

	runtime.handleIncomingMessage(event)
	runtime.handleIncomingMessage(event)

	if queuedIncoming(runtime) != 1 {
		t.Fatalf("expected one queued copy, got %d", queuedIncoming(runtime))
	}
}

func TestIncomingDedupeEntryExpires(t *testing.T) {
	runtime := &Runtime{}
	event := testIncomingEvent("expired")
	if !runtime.claimIncoming(event) {
		t.Fatal("expected the first event to be claimed")
	}

	runtime.mu.Lock()
	for key := range runtime.incomingSeen {
		runtime.incomingSeen[key] = time.Now().Add(-incomingMemory - time.Second)
	}
	runtime.mu.Unlock()

	if !runtime.claimIncoming(event) {
		t.Fatal("expected an expired event key to be claimable again")
	}
}

func TestHandleIncomingMessageRejectsUncarriableEventsWithoutQueueing(t *testing.T) {
	runtime := &Runtime{
		incoming: testIncomingShards(incomingQueueDepth),
		shutdown: make(chan struct{}),
	}
	broadcastList := testIncomingEvent("broadcast")
	broadcastList.Info.Chat = types.NewJID("123", types.BroadcastServer)

	runtime.handleIncomingMessage(nil)
	runtime.handleIncomingMessage(&events.Message{})
	runtime.handleIncomingMessage(broadcastList)

	if queuedIncoming(runtime) != 0 {
		t.Fatalf("expected nothing queued, got %d", queuedIncoming(runtime))
	}
}

// A full queue applies backpressure, but shutdown has to win — otherwise the
// enqueue would pin whatsmeow's handler goroutine while the runtime is closing.
func TestHandleIncomingMessageGivesUpOnShutdown(t *testing.T) {
	stop := make(chan struct{})
	runtime := &Runtime{
		incoming: testIncomingShards(1),
		shutdown: stop,
	}
	runtime.handleIncomingMessage(testIncomingEvent("fills-the-queue"))
	close(stop)
	dropped := testIncomingEvent("dropped")

	done := make(chan struct{})
	go func() {
		runtime.handleIncomingMessage(dropped)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("enqueue blocked past shutdown")
	}
	if !runtime.claimIncoming(dropped) {
		t.Fatal("shutdown must release the unqueued dedupe claim")
	}
}
