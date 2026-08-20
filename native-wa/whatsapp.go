package nativewa

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"hash/fnv"
	"io"
	"math/rand"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	waStore "go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/reflect/protoreflect"
)

const maxIncomingTextBytes = 100_000

func (r *Runtime) createWhatsAppClient() error {
	device := r.deviceStore()
	if device == nil {
		return fmt.Errorf("WhatsApp device store missing")
	}
	return r.installWhatsAppClient(device)
}

// installWhatsAppClient atomically replaces both halves of a linked-device client. Logout marks
// the old store as permanently deleted, so reusing that Device would make the next pairing code
// appear to work but fail as soon as whatsmeow tried to persist its keys.
func (r *Runtime) installWhatsAppClient(device *waStore.Device) error {
	if device == nil {
		return fmt.Errorf("WhatsApp device store missing")
	}
	logger := newAndroidLogger("nativewa-client", "WARN")
	client := whatsmeow.NewClient(device, logger)
	configureWhatsAppClient(client)
	client.AddEventHandler(r.handleWhatsAppEvent)
	r.mu.Lock()
	r.device = device
	r.waClient = client
	r.linkDown = false
	r.groupNames = make(map[string]string)
	r.groupCatalogLoaded = false
	r.blocklistMirror = nil
	r.blocklistDHash = ""
	r.incomingSeen = make(map[string]time.Time)
	r.handledCalls = nil
	r.mu.Unlock()
	r.safetyRefreshMu.Lock()
	r.safetyCached = nil
	r.safetyCachedAt = time.Time{}
	r.lastLimitProbe = time.Time{}
	r.safetyRefreshMu.Unlock()
	return nil
}

// configureWhatsAppClient keeps the linked device usable without a desktop or
// an operator-driven Signal identity approval UI. Whatsmeow defaults to
// trusting a replaced remote identity; disabling that default makes incoming
// messages permanently undecryptable after a contact reinstalls or relinks
// WhatsApp. We retain a privacy-safe IdentityChange event below so the key
// transition remains auditable without logging a JID or message content.
func configureWhatsAppClient(client *whatsmeow.Client) {
	client.EnableAutoReconnect = true
	client.AutoTrustIdentity = true
	client.AutomaticMessageRerequestFromPhone = true
	client.SetForceActiveDeliveryReceipts(true)
	// When a contact cannot decrypt one of our messages their client asks for it
	// again, and the only way to answer is to re-encrypt the original plaintext.
	// Whatsmeow otherwise keeps just 256 sent messages in memory, which is gone
	// the moment the process restarts — precisely when the retry happens, because
	// a fresh session after a restart is what makes the first decrypt fail. The
	// contact is then stuck with a message they can never read. The store is
	// whatsmeow's own table and it prunes itself after seven days.
	client.UseRetryMessageStore = true
}

func (r *Runtime) connectWhatsApp() {
	r.publish(map[string]any{
		"type":    "connection",
		"state":   "connecting",
		"detail":  "native_linked_device_connecting",
		"account": r.accountSnapshot(),
	})
	if err := r.wa().Connect(); err != nil {
		r.publish(map[string]any{
			"type":    "connection",
			"state":   "reconnecting",
			"detail":  "native_linked_device_connect_failed",
			"account": r.accountSnapshot(),
		})
	}
}

func (r *Runtime) handleWhatsAppEvent(raw any) {
	r.mu.RLock()
	stopped := r.stopped
	r.mu.RUnlock()
	if stopped {
		return
	}
	switch event := raw.(type) {
	case *events.Connected:
		r.publishLinkUp("native_linked_device_connected")
		r.launch(r.loadGroupNamesOnce)
	case *events.Disconnected:
		detail := r.recordDisconnect(time.Now())
		r.publish(map[string]any{
			"type":    "connection",
			"state":   "reconnecting",
			"detail":  detail,
			"account": r.accountSnapshot(),
		})
	case *events.KeepAliveTimeout:
		r.publish(map[string]any{
			"type":       "safety",
			"kind":       "connection_keepalive_timeout",
			"detail":     "WhatsApp keepalive response timed out",
			"errorCount": event.ErrorCount,
		})
	case *events.KeepAliveRestored:
		r.publishLinkUp("native_linked_device_keepalive_restored")
	case *events.TemporaryBan:
		expiresAt := time.Now().Add(event.Expire).UnixMilli()
		r.publish(map[string]any{
			"type":        "safety",
			"kind":        "temporary_ban",
			"detail":      "native_whatsapp_temporary_ban",
			"reasonCode":  int(event.Code),
			"durationMs":  event.Expire.Milliseconds(),
			"expiresAtMs": expiresAt,
		})
		r.publishConnectionError("native_whatsapp_temporary_ban")
	case *events.ConnectFailure:
		// Only a failure the account cannot recover from on its own is a hard stop
		// that needs the user. Everything else (server errors, throttling, generic
		// 400s) is transient and whatsmeow retries it — reporting those as `error`
		// is what previously left the UI parked on a dead-looking state while the
		// link was busy coming back.
		fatal := event.Reason.IsLoggedOut() ||
			event.Reason == events.ConnectFailureTempBanned ||
			event.Reason == events.ConnectFailureClientOutdated ||
			event.Reason == events.ConnectFailureBadUserAgent
		r.publish(map[string]any{
			"type":       "safety",
			"kind":       connectionSafetyKind(fatal),
			"detail":     "native_whatsapp_connect_failure",
			"reasonCode": int(event.Reason),
		})
		if fatal {
			r.publishConnectionError("native_whatsapp_connect_failure")
		} else {
			r.publishReconnecting("native_whatsapp_connect_failure_transient")
		}
	case *events.StreamError:
		fatal := fatalStreamErrorCode(event.Code)
		r.publish(map[string]any{
			"type":       "safety",
			"kind":       connectionSafetyKind(fatal),
			"detail":     "native_whatsapp_stream_error",
			"code":       truncate(event.Code, 40),
			"statusCode": streamStatusCode(event.Code),
		})
		if fatal {
			r.publishConnectionError("native_whatsapp_stream_error")
		} else {
			// The common one here is 515 (restart required) right after pairing.
			// whatsmeow reconnects itself; announcing `error` would strand the UI.
			r.publishReconnecting("native_whatsapp_stream_error_transient")
		}
	case *events.LoggedOut:
		r.publish(map[string]any{
			"type":   "safety",
			"kind":   "connection_hard_stop",
			"detail": "native_whatsapp_logged_out",
		})
		r.publish(map[string]any{
			"type":    "connection",
			"state":   "pairing",
			"detail":  "logged_out",
			"account": r.accountSnapshot(),
		})
	case *events.StreamReplaced:
		r.publish(map[string]any{
			"type":   "safety",
			"kind":   "connection_hard_stop",
			"detail": "linked_device_stream_replaced",
		})
	case *events.PairSuccess:
		r.publish(map[string]any{
			"type":    "connection",
			"state":   "connected",
			"detail":  "pairing_succeeded",
			"account": r.accountSnapshot(),
		})
	case *events.NotifyAccountReachoutTimelock:
		r.publishSafety("reachout_timelock", reachoutEventPayload(event))
	case *events.Blocklist:
		r.launch(func() { r.publishBlocklistChange(event) })
	case *events.Message:
		r.handleIncomingMessage(event)
	case *events.GroupInfo:
		if event.Name != nil {
			r.rememberGroupName(event.JID, event.Name.Name)
		}
		// A group's disappearing-message timer is changed through group
		// metadata, not through a message, so it only arrives here.
		if event.Ephemeral != nil {
			expiration := uint32(0)
			if event.Ephemeral.IsEphemeral {
				expiration = event.Ephemeral.DisappearingTimer
			}
			r.rememberEphemeral(event.JID, expiration, event.Timestamp.Unix())
		}
	case *events.JoinedGroup:
		r.rememberGroupName(event.JID, event.Name)
	case *events.CallOffer:
		r.launch(func() { r.handleIncomingCall(event.BasicCallMeta, "audio") })
	case *events.CallOfferNotice:
		r.launch(func() { r.handleIncomingCall(event.BasicCallMeta, event.Media) })
	case *events.Receipt:
		r.handleReceipt(event)
	case *events.UndecryptableMessage:
		r.publish(map[string]any{
			"type":   "safety",
			"kind":   "message_decrypt_retry",
			"detail": "WhatsApp requested an encrypted message retry",
		})
	case *events.IdentityChange:
		r.publish(map[string]any{
			"type":     "safety",
			"kind":     "identity_key_changed",
			"detail":   "WhatsApp contact identity key changed",
			"implicit": event.Implicit,
		})
	case *events.ClientOutdated:
		r.publish(map[string]any{
			"type":   "safety",
			"kind":   "connection_hard_stop",
			"detail": "native WhatsApp protocol is outdated",
		})
	}
}

// loadGroupNamesOnce is the sole proactive group-metadata request. One joined-groups IQ after the
// first connection is normal client behaviour and replaces an N-per-row lookup pattern. A failed
// attempt is not retried in a reconnect storm; later GroupInfo/JoinedGroup events still fill the
// cache naturally.
func (r *Runtime) loadGroupNamesOnce() {
	r.mu.Lock()
	if r.stopped || r.groupCatalogLoaded || r.waClient == nil {
		r.mu.Unlock()
		return
	}
	r.groupCatalogLoaded = true
	client := r.waClient
	r.mu.Unlock()

	ctx, cancel := r.contextWithTimeout(30 * time.Second)
	defer cancel()
	groups, err := client.GetJoinedGroups(ctx)
	if err != nil {
		return
	}
	for _, group := range groups {
		if group != nil {
			r.rememberGroupName(group.JID, group.Name)
		}
	}
}

func (r *Runtime) rememberGroupName(jid types.JID, name string) {
	key := wireJID(jid)
	value := strings.TrimSpace(name)
	if key == "" || value == "" {
		return
	}
	r.mu.Lock()
	if r.groupNames == nil {
		r.groupNames = make(map[string]string)
	}
	r.groupNames[key] = truncate(value, 300)
	r.mu.Unlock()
}

func (r *Runtime) groupName(jid string) string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.groupNames[jid]
}

const (
	reconnectStormWindow = 5 * time.Minute
	reconnectStormLimit  = 3
)

// recordDisconnect keeps a tiny in-memory health window. It is a *health signal*
// only: a flapping link is still a usable link, so it never gates the connected
// state. See publishLinkUp for why that distinction matters.
func (r *Runtime) recordDisconnect(now time.Time) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.connectionEpoch++
	r.linkDown = true
	r.recentDisconnects = recentWithin(r.recentDisconnects, now, reconnectStormWindow)
	r.recentDisconnects = append(r.recentDisconnects, now)
	if len(r.recentDisconnects) >= reconnectStormLimit {
		return "native_linked_device_reconnect_storm"
	}
	return "native_linked_device_disconnected"
}

func recentWithin(values []time.Time, now time.Time, window time.Duration) []time.Time {
	cutoff := now.Add(-window)
	kept := values[:0]
	for _, value := range values {
		if !value.Before(cutoff) {
			kept = append(kept, value)
		}
	}
	return kept
}

// publishLinkUp reports the link as usable the moment WhatsApp accepted the
// session, and reports instability separately.
//
// This used to withhold "connected" during a reconnect storm and publish only
// "authenticating" until the socket had survived a two-minute quiet window. That
// window was unreachable by construction: the timer confirmed stability by
// comparing the connection epoch it captured against the current one, but every
// disconnect bumps that epoch — so a link dropping faster than the quiet window
// (the observed failure was a drop roughly once a minute) could never satisfy it.
// recentDisconnects was cleared only inside that same unreachable branch, so the
// storm latched permanently and the UI sat on "verbindet" forever while whatsmeow
// was in fact reconnecting successfully the whole time.
//
// A flapping link is degraded, not dead. The parent build makes the same call: a
// socket that reports open is announced ready immediately, and health is a
// separate concern. Callers that care get the instability as a safety signal.
func (r *Runtime) publishLinkUp(detail string) {
	now := time.Now()
	r.mu.Lock()
	r.connectionEpoch++
	r.linkDown = false
	r.recentDisconnects = recentWithin(r.recentDisconnects, now, reconnectStormWindow)
	drops := len(r.recentDisconnects)
	r.mu.Unlock()

	if drops >= reconnectStormLimit {
		r.publish(map[string]any{
			"type":          "safety",
			"kind":          "connection_unstable",
			"detail":        "native_linked_device_reconnect_storm",
			"drops":         drops,
			"windowMinutes": int(reconnectStormWindow / time.Minute),
		})
	}
	r.publishConnected(detail)
}

func (r *Runtime) publishConnected(detail string) {
	r.publish(map[string]any{
		"type":    "connection",
		"state":   "connected",
		"detail":  detail,
		"account": r.accountSnapshot(),
	})
}

// fatalStreamErrorCode separates stream errors that need the user from the ones
// whatsmeow recovers from by itself. Unknown codes are treated as transient: a
// wrong "reconnecting" self-corrects on the next event, a wrong "error" does not.
func fatalStreamErrorCode(code string) bool {
	switch code {
	case "401", "403", "405", "conflict", "not-authorized":
		return true
	default:
		return false
	}
}

func streamStatusCode(code string) int {
	var result int
	_, _ = fmt.Sscan(code, &result)
	return result
}

func connectionSafetyKind(fatal bool) string {
	if fatal {
		return "connection_hard_stop"
	}
	return "connection_transient_failure"
}

func (r *Runtime) publishReconnecting(detail string) {
	r.publish(map[string]any{
		"type":    "connection",
		"state":   "reconnecting",
		"detail":  detail,
		"account": r.accountSnapshot(),
	})
}

func (r *Runtime) publishConnectionError(detail string) {
	r.publish(map[string]any{
		"type":    "connection",
		"state":   "error",
		"detail":  detail,
		"account": r.accountSnapshot(),
	})
}

const (
	// How long an incoming call is allowed to ring before it is declined. A
	// linked device that rejects within milliseconds is not something a person
	// with a phone in their hand can produce; a few seconds of ringing is.
	callRingMinimum = 5 * time.Second
	callRingSpread  = 10 * time.Second
	// Calls stay remembered only long enough to cover repeated offers for the
	// same ring; a later callback is a new call and rings again.
	callMemory = 10 * time.Minute
	// Bound on how many message IDs one delivery frame carries.
	maxDeliveryIDsPerFrame = 256
)

// handleIncomingCall answers a call the way the rest of this bridge answers
// messages: not instantly, and never with silence.
//
// An unanswered call used to ring out into nothing — no rejection, no trace,
// no reaction from the persona. For a contact who otherwise gets a reply within
// minutes that is a conspicuous break, and "just call her" is the obvious test.
// The call is therefore declined after a short ring and handed to the engine as
// a normal inbound event, so the persona picks it up through the usual timing
// path and can say something about it in her own words.
func (r *Runtime) handleIncomingCall(meta types.BasicCallMeta, media string) {
	if meta.CallID == "" || !r.claimCall(meta.CallID) {
		return
	}
	caller := meta.CallCreator
	if caller.IsEmpty() {
		caller = meta.From
	}
	time.Sleep(callRingMinimum + time.Duration(rand.Int63n(int64(callRingSpread))))

	r.mu.RLock()
	client := r.waClient
	stopped := r.stopped
	r.mu.RUnlock()
	if stopped || client == nil {
		return
	}
	ctx, cancel := r.contextWithTimeout(30 * time.Second)
	err := client.RejectCall(ctx, caller, meta.CallID)
	cancel()
	if err != nil {
		r.publish(map[string]any{
			"type":   "safety",
			"kind":   "call_reject_failed",
			"detail": "native_whatsapp_call_reject_failed",
		})
	}
	// Group calls are declined like any other, but they are not published: a
	// group ring is not a contact reaching out to this bot, and answering one in
	// the group chat would be noise.
	chat := wireJID(caller)
	if chat == "" || !meta.GroupJID.IsEmpty() || caller.Server == types.GroupServer {
		return
	}
	kind := "audio"
	if media == "video" {
		kind = "video"
	}
	callID := truncate(meta.CallID, 160)
	timestamp := meta.Timestamp
	if timestamp.IsZero() {
		timestamp = time.Now()
	}
	payload := map[string]any{
		"eventId":     "call_missed:" + chat + ":" + callID,
		"kind":        "call_missed",
		"messageId":   "call:" + callID,
		"chatJid":     chat,
		"isGroup":     false,
		"senderJid":   chat,
		"fromMe":      false,
		"timestampMs": timestamp.UnixMilli(),
		"text":        "",
		"category":    "call",
		"callMedia":   kind,
	}
	// The access decision is taken against these, so a caller whose primary
	// address is a LID still has to match the allowlist by phone number.
	aliases := r.resolvedAliases(caller, meta.CallCreatorAlt)
	if len(aliases) > 0 {
		payload["senderAliases"] = aliases
		payload["chatAliases"] = aliases
	}
	r.publish(map[string]any{
		"type":  "incoming",
		"event": payload,
	})
}

// claimCall reports whether this call still has to be answered. WhatsApp can
// announce the same ring more than once (an offer and an offer notice), and
// declining twice is a stanza a real client never sends.
func (r *Runtime) claimCall(callID string) bool {
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.stopped {
		return false
	}
	if r.handledCalls == nil {
		r.handledCalls = make(map[string]time.Time)
	}
	for id, seen := range r.handledCalls {
		if now.Sub(seen) > callMemory {
			delete(r.handledCalls, id)
		}
	}
	if _, known := r.handledCalls[callID]; known {
		return false
	}
	r.handledCalls[callID] = now
	return true
}

// handleIncomingMessage must return promptly: whatsmeow's handler queue runs one
// node at a time and waits on the handler, so normalizing here — which downloads
// media synchronously — stalled every following message and receipt. Only the
// cheap rejects stay on this goroutine; the rest is handed to [runIncomingLoop].
func (r *Runtime) handleIncomingMessage(event *events.Message) {
	if event == nil || event.Message == nil || event.Info.Chat.IsBroadcastList() {
		return
	}
	if !r.claimIncoming(event) {
		return
	}
	select {
	case r.incoming[incomingShardFor(event.Info.Chat)] <- event:
	case <-r.shutdown:
		r.forgetIncoming(event)
	}
}

// incomingShardFor picks the lane a chat's messages are processed on.
//
// One global lane kept publication order but also kept the head-of-line block it
// was meant to remove: normalization downloads media inline with a two-minute
// deadline, so a single slow attachment delayed every other chat's messages,
// receipts and replies by up to two minutes. Those then arrived in one burst,
// which is a more machine-like pattern than the delay it was avoiding.
//
// Sharding by chat keeps the only ordering that carries meaning — messages within
// one conversation stay in order, because a chat always hashes to the same lane —
// while a slow download in one chat no longer gates another. It does not increase
// WhatsApp traffic: the same downloads happen, just up to incomingShards of them
// at once, which is ordinary client behaviour.
func incomingShardFor(chat types.JID) int {
	digest := fnv.New32a()
	_, _ = digest.Write([]byte(chat.ToNonAD().String()))
	return int(digest.Sum32() % incomingShards)
}

func (r *Runtime) claimIncoming(event *events.Message) bool {
	key := incomingKey(event)
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.incomingSeen == nil {
		r.incomingSeen = make(map[string]time.Time)
	}
	if seenAt, exists := r.incomingSeen[key]; exists {
		if now.Sub(seenAt) <= incomingMemory {
			return false
		}
		delete(r.incomingSeen, key)
	}
	if len(r.incomingSeen) >= maxRememberedIncoming {
		cutoff := now.Add(-incomingMemory)
		var oldestKey string
		var oldestAt time.Time
		for candidate, seenAt := range r.incomingSeen {
			if seenAt.Before(cutoff) {
				delete(r.incomingSeen, candidate)
				continue
			}
			if oldestKey == "" || seenAt.Before(oldestAt) {
				oldestKey, oldestAt = candidate, seenAt
			}
		}
		if len(r.incomingSeen) >= maxRememberedIncoming && oldestKey != "" {
			delete(r.incomingSeen, oldestKey)
		}
	}
	r.incomingSeen[key] = now
	return true
}

func (r *Runtime) forgetIncoming(event *events.Message) {
	r.mu.Lock()
	delete(r.incomingSeen, incomingKey(event))
	r.mu.Unlock()
}

func incomingKey(event *events.Message) string {
	return event.Info.Chat.String() + "\x00" + event.Info.Sender.String() + "\x00" +
		string(event.Info.ID) + "\x00" + fmt.Sprint(event.Info.Edit)
}

// runIncomingLoop normalizes and publishes one shard's inbound messages, one at a
// time, so every chat on this lane keeps the order whatsmeow delivered it in.
func (r *Runtime) runIncomingLoop(ctx context.Context, shard int) {
	for {
		select {
		case <-ctx.Done():
			return
		case event := <-r.incoming[shard]:
			r.publishIncoming(ctx, event)
		}
	}
}

// maxIncomingPublishAttempts bounds the retry when the durable journal itself
// refuses the write (disk full, corruption, a lock held past the busy timeout).
//
// This used to retry forever on the only inbound goroutine, so a broken journal
// stopped every inbound message with no ceiling, no safety event and no visible
// cause: the bridge looked alive and simply went deaf. Eight attempts with the
// existing backoff is roughly two minutes of trying before the frame is given up
// on and the operator is told.
const maxIncomingPublishAttempts = 8

func (r *Runtime) publishIncoming(ctx context.Context, event *events.Message) {
	payload := r.normalizedIncoming(event)
	if payload == nil {
		return
	}
	frame := map[string]any{
		"type":  "incoming",
		"event": payload,
	}
	backoff := 100 * time.Millisecond
	for attempt := 1; ; attempt++ {
		if err := r.publish(frame); err == nil {
			return
		} else {
			newAndroidLogger("nativewa-incoming", "WARN").
				Errorf("durable publish failed (attempt %d/%d): %v", attempt, maxIncomingPublishAttempts, err)
		}
		if attempt >= maxIncomingPublishAttempts {
			// Forgotten on purpose: whatsmeow replays undelivered stanzas around a
			// reconnect, and this message was never handed to Android, so letting a
			// repeat be processed is the only path back.
			r.forgetIncoming(event)
			// Best effort — if the journal is what is broken this cannot land either,
			// but the log line above is already on record.
			r.publish(map[string]any{
				"type":     "safety",
				"kind":     "inbound_pipeline_failed",
				"detail":   "native_inbound_journal_write_failed",
				"attempts": attempt,
			})
			return
		}
		timer := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			timer.Stop()
			r.forgetIncoming(event)
			return
		case <-timer.C:
		}
		backoff = min(backoff*2, 30*time.Second)
	}
}

func (r *Runtime) normalizedIncoming(event *events.Message) map[string]any {
	info := event.Info
	message := event.Message
	kind := "message"
	text := truncate(redactIncomingSecrets(textOf(message)), maxIncomingTextBytes)
	var reactionEmoji, targetMessageID string

	if reaction := message.GetReactionMessage(); reaction != nil {
		kind = "reaction"
		reactionEmoji = reaction.GetText()
		targetMessageID = reaction.GetKey().GetID()
		text = ""
	} else if event.IsEdit || info.Edit == types.EditAttributeMessageEdit {
		kind = "edit"
		targetMessageID = message.GetProtocolMessage().GetKey().GetID()
		if targetMessageID == "" {
			targetMessageID = string(info.ID)
		}
	} else if protocol := message.GetProtocolMessage(); protocol != nil &&
		protocol.GetType() == waE2E.ProtocolMessage_REVOKE {
		kind = "delete"
		targetMessageID = protocol.GetKey().GetID()
		text = ""
	}

	// Learned before the carriability check below: the disappearing-message timer
	// is worth keeping even for a message the client contract cannot carry.
	r.learnEphemeralFromIncoming(info.Chat, message, info.Timestamp.Unix())

	// The first group message after a restart often rides in on a payload that
	// only redistributes the sender key; whatsmeow consumed it above and the
	// sender's own chat shows nothing, so surfacing it would render a ghost
	// "[Unsupported WhatsApp message]" bubble the persona then replies to.
	if kind == "message" && protocolOnly(message) {
		return nil
	}

	chat := wireJID(info.Chat)
	sender := wireJID(info.Sender)
	if chat == "" || sender == "" || info.ID == "" {
		return nil
	}
	result := map[string]any{
		"eventId":     fmt.Sprintf("%s:%s:%s", kind, chat, info.ID),
		"kind":        kind,
		"messageId":   string(info.ID),
		"chatJid":     chat,
		"isGroup":     info.IsGroup,
		"senderJid":   sender,
		"fromMe":      info.IsFromMe,
		"timestampMs": info.Timestamp.UnixMilli(),
		"text":        text,
		"category":    categoryOf(message),
	}
	if info.PushName != "" {
		result["senderName"] = truncate(info.PushName, 300)
		if !info.IsGroup && !info.IsFromMe {
			// Reuse the push name already attached to the inbound stanza. This is the contact alias
			// WhatsApp gave us, with zero extra network requests.
			result["chatName"] = truncate(info.PushName, 300)
		}
	}
	if info.IsGroup {
		if name := r.groupName(chat); name != "" {
			result["chatName"] = name
		}
	}
	senderAliases := r.resolvedAliases(info.Sender, info.SenderAlt)
	if len(senderAliases) > 0 {
		result["senderAliases"] = senderAliases
	}
	if !info.IsGroup {
		// For incoming DMs the chat is the sender, so SenderAlt is also the
		// alternate chat address. RecipientAlt is the corresponding alias for
		// messages sent from one of our own devices.
		chatAlt := info.SenderAlt
		if info.IsFromMe {
			chatAlt = info.RecipientAlt
		}
		chatAliases := r.resolvedAliases(info.Chat, chatAlt)
		if len(chatAliases) > 0 {
			result["chatAliases"] = chatAliases
		}
	}
	if reactionEmoji != "" || kind == "reaction" {
		result["reactionEmoji"] = truncate(reactionEmoji, 32)
	}
	if targetMessageID != "" {
		result["targetMessageId"] = truncate(targetMessageID, 192)
	}
	if quoted := quotedOf(message); quoted != nil {
		result["quoted"] = quoted
	}
	if kind == "message" {
		// Retained so a later reply can embed the real quoted message.
		r.rememberQuotable(chat, string(info.ID), info.Sender, message)
	}
	if media := r.captureIncomingMedia(event); media != nil {
		result["media"] = media
	}
	return result
}

var openRouterSecretPattern = regexp.MustCompile(`(?i)\bsk-or-(?:v1-)?[a-z0-9_-]{16,}\b`)

func redactIncomingSecrets(text string) string {
	return openRouterSecretPattern.ReplaceAllString(text, "[redacted-openrouter-key]")
}

// resolvedAliases merges the address supplied on the live message with the
// durable LID mapping learned by whatsmeow. Recent WhatsApp clients often use
// a hidden LID as the primary address; access checks still need the matching
// phone-number JID without weakening the allowlist to allow-all.
func (r *Runtime) resolvedAliases(primary types.JID, supplied ...types.JID) []string {
	primaryValue := wireJID(primary)
	values := make([]string, 0, len(supplied)+1)
	seen := map[string]bool{primaryValue: true, "": true}
	add := func(jid types.JID) {
		value := wireJID(jid)
		if !seen[value] {
			seen[value] = true
			values = append(values, value)
		}
	}
	for _, jid := range supplied {
		add(jid)
	}
	device := r.deviceStore()
	if device != nil && device.LIDs != nil {
		ctx, cancel := r.contextWithTimeout(2 * time.Second)
		defer cancel()
		var mapped types.JID
		var err error
		switch primary.Server {
		case types.HiddenUserServer, types.HostedLIDServer:
			mapped, err = device.LIDs.GetPNForLID(ctx, primary.ToNonAD())
		case types.DefaultUserServer, types.HostedServer:
			mapped, err = device.LIDs.GetLIDForPN(ctx, primary.ToNonAD())
		}
		if err == nil {
			add(mapped)
		}
	}
	return values
}

// The encrypted download can be slightly larger than the decrypted payload due to block padding
// and its MAC. Wrapping the file also prevents whatsmeow from preallocating an untrusted HTTP
// Content-Length because the concrete writer is no longer exposed as *os.File.
type boundedMediaFile struct{ *os.File }

func (file *boundedMediaFile) Write(data []byte) (int, error) {
	offset, err := file.File.Seek(0, io.SeekCurrent)
	if err != nil {
		return 0, err
	}
	if offset < 0 || offset > maxMediaBytes+32 || int64(len(data)) > maxMediaBytes+32-offset {
		return 0, fmt.Errorf("media exceeds download limit")
	}
	return file.File.Write(data)
}

func (file *boundedMediaFile) WriteAt(data []byte, offset int64) (int, error) {
	if offset < 0 || offset > maxMediaBytes+32 || int64(len(data)) > maxMediaBytes+32-offset {
		return 0, fmt.Errorf("media exceeds download limit")
	}
	return file.File.WriteAt(data, offset)
}

func (file *boundedMediaFile) Truncate(size int64) error {
	if size < 0 || size > maxMediaBytes+32 {
		return fmt.Errorf("media exceeds download limit")
	}
	return file.File.Truncate(size)
}

func (r *Runtime) captureIncomingMedia(event *events.Message) map[string]any {
	if event.Info.IsFromMe {
		return nil
	}
	kind, mimeType, fileName, caption, declared, seconds, downloadable := mediaOf(event.Message)
	if downloadable == nil {
		return nil
	}
	r.mu.RLock()
	allowed := r.ingressAllowsLocked(event.Info, kind)
	r.mu.RUnlock()
	if !allowed || declared > uint64(maxMediaBytes) {
		stage := "ingress_disabled"
		if declared > uint64(maxMediaBytes) {
			stage = "declared_too_large"
		}
		r.reportMediaCaptureFailure(kind, stage)
		return nil
	}
	id, err := randomID(24)
	if err != nil {
		r.reportMediaCaptureFailure(kind, "id_generation_failed")
		return nil
	}
	temporary := filepath.Join(r.media.root, id+".part")
	file, err := os.OpenFile(temporary, os.O_CREATE|os.O_EXCL|os.O_RDWR, 0600)
	if err != nil {
		r.reportMediaCaptureFailure(kind, "cache_open_failed")
		return nil
	}
	ctx, cancel := r.contextWithTimeout(2 * time.Minute)
	err = r.wa().DownloadToFile(ctx, downloadable, &boundedMediaFile{File: file})
	cancel()
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(temporary)
		r.reportMediaCaptureFailure(kind, "whatsapp_download_failed")
		return nil
	}
	info, err := os.Stat(temporary)
	if err != nil || info.Size() <= 0 || info.Size() > maxMediaBytes {
		_ = os.Remove(temporary)
		r.reportMediaCaptureFailure(kind, "download_validation_failed")
		return nil
	}
	finalPath := filepath.Join(r.media.root, id+".bin")
	if err = os.Rename(temporary, finalPath); err != nil {
		_ = os.Remove(temporary)
		r.reportMediaCaptureFailure(kind, "cache_commit_failed")
		return nil
	}
	hash, err := fileSHA256(finalPath)
	if err != nil {
		_ = os.Remove(finalPath)
		r.reportMediaCaptureFailure(kind, "hash_failed")
		return nil
	}
	record := &mediaRecord{
		ID:           id,
		Path:         finalPath,
		MimeType:     normalizedMime(mimeType),
		OriginalName: safeFileName(fileName),
		Size:         info.Size(),
		SHA256:       hash,
	}
	_, err = r.db.Exec(
		`INSERT INTO native_media(id, path, mime_type, original_name, size_bytes, sha256, created_at_ms)
		 VALUES (?, ?, ?, ?, ?, ?, ?)`,
		record.ID,
		record.Path,
		record.MimeType,
		record.OriginalName,
		record.Size,
		record.SHA256,
		time.Now().UnixMilli(),
	)
	if err != nil {
		_ = os.Remove(finalPath)
		r.reportMediaCaptureFailure(kind, "database_commit_failed")
		return nil
	}
	result := map[string]any{
		"id":        record.ID,
		"kind":      kind,
		"mimeType":  record.MimeType,
		"sizeBytes": record.Size,
		"sha256":    record.SHA256,
		"fileName":  record.OriginalName,
	}
	if caption != "" {
		result["caption"] = truncate(caption, 100000)
	}
	// Clamped because the field is attacker-controlled: a peer can claim a
	// one-hour voice note and would otherwise stall the reply for an hour.
	if seconds > 0 {
		result["durationSeconds"] = min(seconds, maxMediaDurationSeconds)
	}
	return result
}

// Bounded transport diagnostics only: no contact, message ID, local path, or
// underlying network error ever crosses into the Android activity feed.
func (r *Runtime) reportMediaCaptureFailure(kind, stage string) {
	r.publish(map[string]any{
		"type":   "safety",
		"kind":   "media_capture_failed",
		"detail": "native_media_" + truncate(kind, 16) + "_" + truncate(stage, 48),
	})
}

func (r *Runtime) ingressAllowsLocked(info types.MessageInfo, kind string) bool {
	if !r.ingress.Configured || !r.ingress.MediaKinds[kind] {
		return false
	}
	if r.ingress.AllowAll && !info.IsGroup {
		return true
	}
	candidates := []string{
		normalizeJID(info.Chat),
		normalizeJID(info.Sender),
		normalizeJID(info.SenderAlt),
		normalizeJID(info.RecipientAlt),
	}
	for _, candidate := range candidates {
		if r.ingress.Allowed[candidate] {
			return true
		}
	}
	return false
}

func (r *Runtime) handleReceipt(event *events.Receipt) {
	if event == nil {
		return
	}
	status := "delivered"
	switch event.Type {
	case types.ReceiptTypeRead, types.ReceiptTypeReadSelf:
		status = "read"
	case types.ReceiptTypePlayed:
		status = "played"
	case types.ReceiptTypeDelivered:
		status = "delivered"
	}
	// One frame per receipt stanza, not per message ID. Every publish is a
	// separate SQLite transaction with a MAX scan and a retention delete, so a
	// read receipt covering twenty messages used to cost twenty transactions.
	ids := make([]string, 0, len(event.MessageIDs))
	for _, id := range event.MessageIDs {
		if value := string(id); value != "" {
			ids = append(ids, value)
		}
	}
	for start := 0; start < len(ids); start += maxDeliveryIDsPerFrame {
		end := min(start+maxDeliveryIDsPerFrame, len(ids))
		batch := ids[start:end]
		r.publish(map[string]any{
			"type": "delivery",
			// The single-ID field stays populated: it is what the wire contract
			// has always required, and dropping it would make every frame here
			// undecodable for a client that has not learned the array yet.
			"messageId":   batch[0],
			"messageIds":  batch,
			"status":      status,
			"timestampMs": event.Timestamp.UnixMilli(),
		})
	}
}

func normalizeJID(jid types.JID) string {
	if jid.User == "" || jid.Server == "" {
		return ""
	}
	return jid.ToNonAD().String()
}

// wireJIDServers is the exact address space the bridge protocol carries, and it
// matches the JID pattern the Android decoder enforces.
var wireJIDServers = map[string]bool{
	types.DefaultUserServer: true,
	types.HiddenUserServer:  true,
	types.GroupServer:       true,
}

// wireJID is normalizeJID restricted to addresses the wire contract accepts.
//
// Everything else — a status update (status@broadcast, which IsBroadcastList
// deliberately does not cover), a channel post (@newsletter), a Meta AI reply
// (@bot), a hosted business address — is not an addressable chat for this bot.
// Publishing one anyway was fatal rather than useless: the Android decoder
// rejects the frame, and because the frame is journalled durably it is replayed
// into the same rejection on every reconnect, so a single status update killed
// the link until the journal was wiped by re-pairing. Filtering here keeps such
// an event out of the journal instead of poisoning it.
func wireJID(jid types.JID) string {
	if !wireJIDServers[jid.Server] {
		return ""
	}
	return normalizeJID(jid)
}

func textOf(message *waE2E.Message) string {
	if message == nil {
		return ""
	}
	switch {
	case message.GetConversation() != "":
		return message.GetConversation()
	case message.GetExtendedTextMessage() != nil:
		return message.GetExtendedTextMessage().GetText()
	case message.GetImageMessage() != nil:
		return message.GetImageMessage().GetCaption()
	case message.GetVideoMessage() != nil:
		return message.GetVideoMessage().GetCaption()
	case message.GetDocumentMessage() != nil:
		return message.GetDocumentMessage().GetCaption()
	case message.GetButtonsResponseMessage() != nil:
		return message.GetButtonsResponseMessage().GetSelectedDisplayText()
	case message.GetListResponseMessage() != nil:
		return message.GetListResponseMessage().GetTitle()
	default:
		return ""
	}
}

func categoryOf(message *waE2E.Message) string {
	switch {
	case message.GetConversation() != "" || message.GetExtendedTextMessage() != nil:
		return "text"
	case message.GetImageMessage() != nil:
		return "image"
	case message.GetVideoMessage() != nil:
		return "video"
	case message.GetAudioMessage() != nil:
		return "audio"
	case message.GetDocumentMessage() != nil:
		return "document"
	case message.GetStickerMessage() != nil:
		return "sticker"
	case message.GetContactMessage() != nil || message.GetContactsArrayMessage() != nil:
		return "contact"
	case message.GetLocationMessage() != nil || message.GetLiveLocationMessage() != nil:
		return "location"
	case message.GetPollCreationMessage() != nil || message.GetPollCreationMessageV3() != nil:
		return "poll"
	default:
		return "unknown"
	}
}

// protocolOnly reports whether a decrypted payload carries no user-visible
// content: every populated field is plumbing whatsmeow already processed
// (sender-key distribution, message secrets, protocol bookkeeping). Field
// presence is checked by reflection so any real content field — including
// types this file has never heard of — keeps the event alive as "unknown"
// instead of silently vanishing.
func protocolOnly(message *waE2E.Message) bool {
	if message == nil {
		return true
	}
	plumbing := map[string]bool{
		"senderKeyDistributionMessage": true,
		"messageContextInfo":           true,
		"protocolMessage":              true,
	}
	only := true
	message.ProtoReflect().Range(func(field protoreflect.FieldDescriptor, _ protoreflect.Value) bool {
		if !plumbing[string(field.Name())] {
			only = false
			return false
		}
		return true
	})
	return only
}

func quotedOf(message *waE2E.Message) map[string]any {
	contextInfo := messageContext(message)
	if contextInfo == nil || contextInfo.GetStanzaID() == "" {
		return nil
	}
	result := map[string]any{
		"messageId": truncate(contextInfo.GetStanzaID(), 192),
		"text":      truncate(redactIncomingSecrets(textOf(contextInfo.GetQuotedMessage())), maxIncomingTextBytes),
	}
	if participant := strings.TrimSpace(contextInfo.GetParticipant()); participant != "" {
		// Omitted rather than sent empty for an address the contract cannot
		// carry: the quote itself is still useful, only its author is unknown.
		if jid, err := types.ParseJID(participant); err == nil {
			if value := wireJID(jid); value != "" {
				result["senderJid"] = value
			}
		}
	}
	return result
}

func messageContext(message *waE2E.Message) *waE2E.ContextInfo {
	switch {
	case message.GetExtendedTextMessage() != nil:
		return message.GetExtendedTextMessage().GetContextInfo()
	case message.GetImageMessage() != nil:
		return message.GetImageMessage().GetContextInfo()
	case message.GetVideoMessage() != nil:
		return message.GetVideoMessage().GetContextInfo()
	case message.GetAudioMessage() != nil:
		return message.GetAudioMessage().GetContextInfo()
	case message.GetDocumentMessage() != nil:
		return message.GetDocumentMessage().GetContextInfo()
	case message.GetStickerMessage() != nil:
		return message.GetStickerMessage().GetContextInfo()
	default:
		return nil
	}
}

// The duration is what lets the engine pace play receipts: a voice note may only
// be marked as heard once it could plausibly have been listened to end to end.
// Only audio and video carry one; everything else reports 0 for "unknown".
func mediaOf(message *waE2E.Message) (kind, mimeType, fileName, caption string, declared uint64, seconds int64, downloadable whatsmeow.DownloadableMessage) {
	switch {
	case message.GetImageMessage() != nil:
		value := message.GetImageMessage()
		return "image", value.GetMimetype(), "image", value.GetCaption(), value.GetFileLength(), 0, value
	case message.GetVideoMessage() != nil:
		value := message.GetVideoMessage()
		return "video", value.GetMimetype(), "video", value.GetCaption(), value.GetFileLength(), int64(value.GetSeconds()), value
	case message.GetAudioMessage() != nil:
		value := message.GetAudioMessage()
		return "audio", value.GetMimetype(), "audio", "", value.GetFileLength(), int64(value.GetSeconds()), value
	case message.GetDocumentMessage() != nil:
		value := message.GetDocumentMessage()
		return "document", value.GetMimetype(), value.GetFileName(), value.GetCaption(), value.GetFileLength(), 0, value
	case message.GetStickerMessage() != nil:
		value := message.GetStickerMessage()
		return "sticker", value.GetMimetype(), "sticker", "", value.GetFileLength(), 0, value
	default:
		return "", "", "", "", 0, 0, nil
	}
}

func fileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err = io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

// truncate caps a string at a byte budget without splitting a rune. Cutting mid
// sequence turned a trailing umlaut or emoji in a sender name or caption into
// U+FFFD, and that replacement character then travelled into history and the
// model prompt.
func truncate(value string, maximum int) string {
	if len(value) <= maximum {
		return value
	}
	if maximum <= 0 {
		return ""
	}
	cut := maximum
	for cut > 0 && !utf8.RuneStart(value[cut]) {
		cut--
	}
	return value[:cut]
}
