package nativewa

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"math/rand"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

type messageKey struct {
	ChatID      string `json:"chatId"`
	ID          string `json:"id"`
	FromMe      bool   `json:"fromMe"`
	Participant string `json:"participant"`
	// Optional text of the quoted message. It is only used when the message
	// itself is no longer retained, so a reply still shows a preview.
	Preview string `json:"preview"`
}

var transientActions = map[string]bool{
	"pair":               true,
	"reconnect":          true,
	"link_sleep":         true,
	"link_wake":          true,
	"mark_read":          true,
	"mark_played":        true,
	"presence":           true,
	"set_ingress_policy": true,
	"safety_refresh":     true,
}

var errIdempotencyInDoubt = errors.New("idempotency result is in doubt")

// errActionPanicked is what a caller sees when an action aborted on a bug rather
// than on a WhatsApp answer. It classifies as ambiguous, so the outbox refuses the
// retry instead of risking a duplicate send.
var errActionPanicked = errors.New("native action aborted unexpectedly")

// errWireContacted marks a failure that happened *after* the request was handed
// to WhatsApp.
//
// The idempotency journal reserves a request ID before the action runs so a crash
// cannot replay a send. That reservation is only ever correct to keep when the
// outcome is genuinely unknown: the stanza may have gone out even though no answer
// came back, so repeating it would double-send. Everything that fails earlier — an
// unparseable JID, a missing media upload, an over-long text, a logged-out client —
// provably never touched the account, and leaving a reservation behind for those
// turns the very first retry into `idempotency_in_doubt`, which Android classifies
// as permanent and dead-letters. That made the entire retry ladder unreachable and
// silently lost messages after a single hiccup.
//
// Marking is done at the call sites that talk to WhatsApp rather than at the many
// validation sites, so the default for anything unmarked is the safe one: release
// the reservation and let the retry be a clean first attempt.
var errWireContacted = errors.New("request was already handed to WhatsApp")

// wireContactedError keeps the original error's text intact — classifyActionError
// and whatsmeowServerErrorCode both read it — while adding the marker to the
// errors.Is chain.
type wireContactedError struct{ err error }

func (e wireContactedError) Error() string { return e.err.Error() }

func (e wireContactedError) Unwrap() error { return e.err }

func (e wireContactedError) Is(target error) bool { return target == errWireContacted }

// wireError marks err as having reached WhatsApp. Safe to call with nil.
func wireError(err error) error {
	if err == nil {
		return nil
	}
	return wireContactedError{err: err}
}

// ambiguousActionError reports whether a failed action may still have had an
// effect on the account, and must therefore not be retried automatically.
func ambiguousActionError(err error) bool {
	if errors.Is(err, errWireContacted) || errors.Is(err, errIdempotencyInDoubt) {
		return true
	}
	// Belt and braces for any path that forgets to mark itself: a deadline that
	// expired mid-request is ambiguous by definition.
	code, _ := classifyActionError(err)
	return code == "timeout"
}

// unserializedActions run past the action mutex, in their own lane.
//
// Sends are serialized and slow: send_media allows three minutes, send_text
// ninety seconds. The engine meanwhile refreshes the composing indicator every
// six seconds and pushes read receipts through the same entry point, so a
// stalling upload used to hold the typing indicator, presence changes and
// receipts hostage for minutes — the indicator visibly froze mid-answer, which
// is exactly the signal the human timing model is built on. These actions are
// already exempt from the idempotency journal, so they never needed the
// serialization the journal requires; only pair and reconnect stay in the slow
// lane because they tear the socket down underneath everything else.
var unserializedActions = map[string]bool{
	"mark_read":          true,
	"mark_played":        true,
	"presence":           true,
	"set_ingress_policy": true,
	"safety_refresh":     true,
}

func (r *Runtime) handleAction(session *wsSession, frame clientFrame) {
	if !unserializedActions[frame.Action] {
		r.actions.Lock()
		defer r.actions.Unlock()
	}
	// Registered after the unlock above, so it runs first and the action mutex is
	// still released while unwinding. net/http would swallow a panic here by
	// killing the connection, which drops the whole bridge session and forces a
	// resume for one broken action; answering the request instead keeps the link
	// up. The reservation is deliberately left in flight: a panic gives no
	// evidence about whether the stanza went out.
	defer func() {
		panicked := recover()
		if panicked == nil {
			return
		}
		newAndroidLogger("nativewa-actions", "ERROR").
			Errorf("action %s panicked", frame.Action)
		_ = session.writeJSON(actionFailure(frame.ID, errActionPanicked))
	}()

	payload := "{}"
	if len(frame.Payload) > 0 {
		payload = string(frame.Payload)
	}
	if !transientActions[frame.Action] {
		if cached, found, err := r.journal.actionResult(frame.ID, frame.Action, payload); err != nil {
			_ = session.writeJSON(actionFailure(frame.ID, err))
			return
		} else if found {
			if cached == actionResultInFlight {
				_ = session.writeJSON(actionFailure(frame.ID, errIdempotencyInDoubt))
				return
			}
			var result any
			if json.Unmarshal([]byte(cached), &result) == nil {
				_ = session.writeJSON(actionSuccess(frame.ID, result, true))
				return
			}
		}
	}

	var decoded map[string]any
	if err := json.Unmarshal([]byte(payload), &decoded); err != nil {
		_ = session.writeJSON(actionFailure(frame.ID, fmt.Errorf("invalid action payload")))
		return
	}
	if !transientActions[frame.Action] {
		if err := r.journal.reserveAction(frame.ID, frame.Action, payload); err != nil {
			_ = session.writeJSON(actionFailure(frame.ID, err))
			return
		}
	}
	result, err := r.executeAction(frame.Action, decoded)
	if err != nil {
		if !transientActions[frame.Action] && !ambiguousActionError(err) {
			// The action provably never reached WhatsApp. Releasing the reservation
			// is what makes the retry a clean first attempt instead of a dead letter.
			// Best effort: a stale in-flight row costs one message, failing the
			// action twice costs the operator the reason it failed at all.
			if releaseErr := r.journal.releaseAction(frame.ID, frame.Action, payload); releaseErr != nil {
				newAndroidLogger("nativewa-actions", "WARN").
					Warnf("release idempotency reservation failed: %v", releaseErr)
			}
		}
		r.reportActionFailure(frame.Action, err)
		_ = session.writeJSON(actionFailure(frame.ID, err))
		return
	}
	if !transientActions[frame.Action] {
		if raw, marshalErr := json.Marshal(result); marshalErr == nil {
			if storeErr := r.journal.storeActionResult(frame.ID, frame.Action, payload, string(raw)); storeErr != nil {
				_ = session.writeJSON(actionFailure(frame.ID, storeErr))
				return
			}
		}
	}
	_ = session.writeJSON(actionSuccess(frame.ID, result, false))
}

func (r *Runtime) executeAction(action string, payload map[string]any) (any, error) {
	switch action {
	case "pair":
		return r.pairPhone(requiredString(payload, "phoneNumber"))
	case "reconnect":
		r.wa().Disconnect()
		if err := r.wa().Connect(); err != nil {
			return nil, err
		}
		return r.accountSnapshot(), nil
	case "link_sleep":
		return r.sleepLink()
	case "link_wake":
		return r.wakeLink()
	case "logout":
		if payload["confirm"] != true {
			return nil, fmt.Errorf("logout requires confirmation")
		}
		// Deadline-bounded like every other WhatsApp call in this file. On
		// context.Background() a half-open socket — the ordinary mobile network
		// transition — left Logout waiting for an answer that never came, while
		// handleAction held the action mutex for the whole execution. Every
		// following send, reaction and block then blocked forever, and Stop could
		// not break it either because it waits on this very goroutine.
		logoutCtx, cancelLogout := r.contextWithTimeout(30 * time.Second)
		defer cancelLogout()
		if r.wa().IsConnected() {
			if err := r.wa().Logout(logoutCtx); err != nil {
				return nil, err
			}
		}
		device := r.deviceStore()
		if device == nil {
			return nil, fmt.Errorf("WhatsApp device store missing")
		}
		if err := device.Delete(logoutCtx); err != nil {
			return nil, err
		}
		// A deleted whatsmeow Device is intentionally unusable. Install a brand-new local store and
		// client now so the next PairPhone call can persist the replacement number without restarting
		// the Android service or making another WhatsApp request.
		if err := r.installWhatsAppClient(r.container.NewDevice()); err != nil {
			return nil, fmt.Errorf("prepare replacement WhatsApp client: %w", err)
		}
		return map[string]any{"state": "pairing", "paired": false}, nil
	case "send_text", "send_reply":
		return r.sendText(payload)
	case "send_media":
		return r.sendMedia(payload)
	case "send_reaction":
		return r.sendReaction(payload)
	case "mark_read":
		return r.markRead(payload, types.ReceiptTypeRead)
	case "mark_played":
		return r.markPlayed(payload)
	case "presence":
		return r.setPresence(payload)
	case "set_ingress_policy":
		return r.setIngressPolicy(payload)
	case "safety_refresh":
		return r.refreshSafety()
	case "edit_message":
		return r.editMessage(payload)
	case "delete_message":
		return r.deleteMessage(payload)
	case "set_profile_picture":
		return r.setProfilePicture(payload)
	case "set_status_message":
		return r.setStatusMessage(payload)
	case "set_push_name":
		return r.setPushName(payload)
	case "block":
		return r.updateContactBlock(payload, true)
	case "unblock":
		return r.updateContactBlock(payload, false)
	default:
		return nil, fmt.Errorf("unsupported native transport action")
	}
}

// sleepLink puts the linked device offline without ending the link.
//
// This is emphatically NOT a logout and NOT a runtime shutdown. The device store,
// the Signal sessions and the app-state keys all stay exactly where they are, so
// waking is a plain reconnect rather than a relink — no noise handshake against a
// fresh identity, no app-state resync, no safety IQ round. That distinction is the
// whole reason low power mode is affordable at all: WhatsApp treats a companion
// that comes and goes as a companion, and a companion that re-pairs as a new device.
//
// Auto-reconnect is disabled first, on purpose. Whatsmeow only reconnects by itself
// when the *server* closed the socket, but the read loop and this call race on a
// flaky network: a drop that lands a millisecond before Disconnect would otherwise
// start a reconnect goroutine that quietly brings the link back up minutes into a
// sleep the caller believes is running, and the phone never suspends.
func (r *Runtime) sleepLink() (any, error) {
	client := r.wa()
	client.EnableAutoReconnect = false
	client.Disconnect()
	return map[string]any{"link": "asleep", "connected": client.IsConnected()}, nil
}

// wakeLink brings the same client back up. Already-connected is success, not an
// error: an alarm can fire while a retry has already reconnected, and failing here
// would report a broken link that is in fact fine.
func (r *Runtime) wakeLink() (any, error) {
	client := r.wa()
	client.EnableAutoReconnect = true
	if err := client.Connect(); err != nil && !errors.Is(err, whatsmeow.ErrAlreadyConnected) {
		return nil, err
	}
	return map[string]any{"link": "awake", "account": r.accountSnapshot()}, nil
}

func (r *Runtime) pairPhone(raw string) (any, error) {
	phone := digitsOnly(raw)
	if len(phone) < 6 || len(phone) > 15 {
		return nil, fmt.Errorf("invalid phone number")
	}
	device := r.deviceStore()
	if device != nil && device.ID != nil {
		return nil, fmt.Errorf("WhatsApp is already paired")
	}
	ctx, cancel := r.contextWithTimeout(3 * time.Minute)
	qrEvents, err := r.preparePairingConnection(ctx)
	if err != nil {
		cancel()
		return nil, err
	}
	code, err := r.wa().PairPhone(
		ctx,
		phone,
		true,
		whatsmeow.PairClientChrome,
		pairClientDisplayName,
	)
	if err != nil {
		cancel()
		return nil, err
	}
	// GetQRChannel owns the unpaired login socket. Its context must remain
	// alive until WhatsApp reports success/error/timeout; cancelling here
	// disconnects the socket immediately after the code is shown and leaves
	// the primary phone stuck on "Logging in…".
	if !r.launch(func() {
		defer cancel()
		for range qrEvents {
		}
	}) {
		// launch refuses once the runtime is stopping, and the return value used to
		// be ignored — leaking the three-minute context and its timer.
		cancel()
	}
	expires := time.Now().Add(2 * time.Minute).UnixMilli()
	r.publish(map[string]any{
		"type":        "pairing_code",
		"available":   true,
		"expiresAtMs": expires,
	})
	return map[string]any{
		"code":        code,
		"expiresAtMs": expires,
	}, nil
}

func (r *Runtime) preparePairingConnection(ctx context.Context) (<-chan whatsmeow.QRChannelItem, error) {
	if r.wa().IsConnected() {
		return nil, fmt.Errorf("WhatsApp pairing socket is already connected")
	}
	qrEvents, err := r.wa().GetQRChannel(ctx)
	if err != nil {
		return nil, fmt.Errorf("prepare WhatsApp pairing channel: %w", err)
	}
	if err = r.wa().Connect(); err != nil && !errors.Is(err, whatsmeow.ErrAlreadyConnected) {
		return nil, fmt.Errorf("connect WhatsApp pairing socket: %w", err)
	}
	select {
	case event, open := <-qrEvents:
		if !open {
			return nil, fmt.Errorf("WhatsApp pairing channel closed before it became ready")
		}
		if event.Event != "code" {
			if event.Error != nil {
				return nil, fmt.Errorf("WhatsApp pairing handshake %s: %w", event.Event, event.Error)
			}
			return nil, fmt.Errorf("WhatsApp pairing handshake ended with %s", event.Event)
		}
		return qrEvents, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (r *Runtime) sendText(payload map[string]any) (any, error) {
	chat, err := parseJID(requiredString(payload, "chatId"))
	if err != nil {
		return nil, err
	}
	text, err := outgoingText(payload)
	if err != nil {
		return nil, err
	}
	message := &waE2E.Message{Conversation: proto.String(text)}
	if rawQuote, ok := payload["replyTo"].(map[string]any); ok {
		key, parseErr := parseMessageKey(rawQuote)
		if parseErr != nil {
			return nil, parseErr
		}
		if context := r.contextFor(chat, key); context != nil {
			message = &waE2E.Message{
				ExtendedTextMessage: &waE2E.ExtendedTextMessage{
					Text:        proto.String(text),
					ContextInfo: context,
				},
			}
		}
	}
	message = r.applyEphemeral(chat, message)
	ctx, cancel := r.contextWithTimeout(90 * time.Second)
	defer cancel()
	response, err := r.wa().SendMessage(ctx, chat, message)
	if err != nil {
		return nil, wireError(err)
	}
	// Our own message can be the target of a later reply just like an incoming
	// one, and the engine does quote back into its own bubbles.
	r.rememberQuotable(normalizeJID(chat), string(response.ID), r.ownAddressFor(chat), message)
	return messageResult(chat, response.ID), nil
}

func (r *Runtime) sendReaction(payload map[string]any) (any, error) {
	raw, ok := payload["message"].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("message key missing")
	}
	key, err := parseMessageKey(raw)
	if err != nil {
		return nil, err
	}
	chat, _ := parseJID(key.ChatID)
	sender := chat
	if key.Participant != "" {
		sender, err = parseJID(key.Participant)
		if err != nil {
			return nil, err
		}
	}
	message := r.wa().BuildReaction(chat, sender, types.MessageID(key.ID), stringValue(payload["emoji"]))
	ctx, cancel := r.contextWithTimeout(90 * time.Second)
	defer cancel()
	response, err := r.wa().SendMessage(ctx, chat, message)
	if err != nil {
		return nil, wireError(err)
	}
	return messageResult(chat, response.ID), nil
}

// markRead sends one receipt per (chat, participant), not one per message.
//
// A native client acknowledges a backlog with a single receipt stanza carrying
// the whole ID list; emitting ten stanzas back to back for ten unread messages
// is a pattern no real client produces. whatsmeow already takes the list, so
// the batching is purely a matter of grouping the keys first.
func (r *Runtime) markRead(payload map[string]any, receiptType types.ReceiptType) (any, error) {
	groups, count, err := groupReceipts(payload["messages"])
	if err != nil {
		return nil, err
	}
	for _, group := range groups {
		ctx, cancel := r.contextWithTimeout(30 * time.Second)
		err := r.wa().MarkRead(ctx, group.ids, time.Now(), group.chat, group.sender, receiptType)
		cancel()
		if err != nil {
			return nil, err
		}
	}
	return map[string]any{"count": count, "receipts": len(groups)}, nil
}

type receiptGroup struct {
	chat   types.JID
	sender types.JID
	ids    []types.MessageID
}

func groupReceipts(raw any) ([]receiptGroup, int, error) {
	rawMessages, ok := raw.([]any)
	if !ok || len(rawMessages) == 0 || len(rawMessages) > 100 {
		return nil, 0, fmt.Errorf("messages must contain 1..100 keys")
	}
	// Slice plus index: the map decides membership, the slice keeps the receipts
	// in the order the engine listed the messages.
	groups := make([]receiptGroup, 0, 2)
	index := make(map[string]int, 2)
	count := 0
	for _, entry := range rawMessages {
		value, ok := entry.(map[string]any)
		if !ok {
			return nil, 0, fmt.Errorf("invalid message key")
		}
		key, err := parseMessageKey(value)
		if err != nil {
			return nil, 0, err
		}
		chat, _ := parseJID(key.ChatID)
		sender := types.EmptyJID
		if key.Participant != "" {
			sender, err = parseJID(key.Participant)
			if err != nil {
				return nil, 0, err
			}
		}
		lookup := chat.String() + "\x00" + sender.String()
		position, known := index[lookup]
		if !known {
			position = len(groups)
			index[lookup] = position
			groups = append(groups, receiptGroup{chat: chat, sender: sender})
		}
		groups[position].ids = append(groups[position].ids, types.MessageID(key.ID))
		count++
	}
	return groups, count, nil
}

func (r *Runtime) markPlayed(payload map[string]any) (any, error) {
	raw, ok := payload["message"].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("message key missing")
	}
	return r.markRead(map[string]any{"messages": []any{raw}}, types.ReceiptTypePlayed)
}

func (r *Runtime) setPresence(payload map[string]any) (any, error) {
	state := requiredString(payload, "state")
	chatID := stringValue(payload["chatId"])
	ctx, cancel := r.contextWithTimeout(30 * time.Second)
	defer cancel()
	switch state {
	case "available":
		if err := r.wa().SendPresence(ctx, types.PresenceAvailable); err != nil {
			return nil, err
		}
	case "unavailable":
		if err := r.wa().SendPresence(ctx, types.PresenceUnavailable); err != nil {
			return nil, err
		}
	case "composing", "paused", "recording":
		chat, err := parseJID(chatID)
		if err != nil {
			return nil, err
		}
		presence := types.ChatPresenceComposing
		media := types.ChatPresenceMediaText
		if state == "paused" {
			presence = types.ChatPresencePaused
		} else if state == "recording" {
			media = types.ChatPresenceMediaAudio
		}
		if err = r.wa().SendChatPresence(ctx, chat, presence, media); err != nil {
			return nil, err
		}
	default:
		return nil, fmt.Errorf("unsupported presence state")
	}
	return map[string]any{"state": state, "chatId": chatID}, nil
}

func (r *Runtime) setIngressPolicy(payload map[string]any) (any, error) {
	allowed := make(map[string]bool)
	if values, ok := payload["allowedJids"].([]any); ok {
		if len(values) > maxIngressJIDs {
			return nil, fmt.Errorf("too many ingress identities")
		}
		for _, value := range values {
			raw := stringValue(value)
			if raw == "" {
				continue
			}
			jid, err := parseJID(raw)
			if err != nil {
				return nil, fmt.Errorf("invalid ingress identity")
			}
			allowed[jid.String()] = true
		}
	}
	kinds := make(map[string]bool)
	if values, ok := payload["mediaKinds"].([]any); ok {
		for _, value := range values {
			kind := stringValue(value)
			if kind == "image" || kind == "audio" || kind == "video" {
				kinds[kind] = true
			}
		}
	}
	r.mu.Lock()
	r.ingress = ingressPolicy{
		Configured: true,
		AllowAll:   payload["allowAll"] == true,
		Allowed:    allowed,
		MediaKinds: kinds,
	}
	r.mu.Unlock()
	sortedKinds := make([]string, 0, len(kinds))
	for kind := range kinds {
		sortedKinds = append(sortedKinds, kind)
	}
	sort.Strings(sortedKinds)
	return map[string]any{
		"configured":      true,
		"allowedJidCount": len(allowed),
		"mediaKinds":      sortedKinds,
	}, nil
}

const maxIngressJIDs = 2048

func (r *Runtime) sendMedia(payload map[string]any) (any, error) {
	chat, err := parseJID(requiredString(payload, "chatId"))
	if err != nil {
		return nil, err
	}
	record, err := r.media.get(requiredString(payload, "mediaId"))
	if err != nil {
		return nil, fmt.Errorf("media upload not found")
	}
	kind := requiredString(payload, "kind")
	// A photo is normalized to what a native client would have sent before a
	// single byte is encrypted: the upload has to carry the JPEG itself.
	photoSource := preparedImage{Path: record.Path, Mimetype: record.MimeType}
	if kind == "image" {
		prepared, release := prepareOutgoingImage(record.Path, record.MimeType, r.media.root)
		defer release()
		photoSource = prepared
	}
	file, err := os.Open(photoSource.Path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	mediaType := whatsmeow.MediaDocument
	switch kind {
	case "image":
		mediaType = whatsmeow.MediaImage
	case "video":
		mediaType = whatsmeow.MediaVideo
	case "audio":
		mediaType = whatsmeow.MediaAudio
	case "document":
		mediaType = whatsmeow.MediaDocument
	default:
		return nil, fmt.Errorf("unsupported media kind")
	}
	ctx, cancel := r.contextWithTimeout(3 * time.Minute)
	defer cancel()
	// whatsmeow creates its fallback temp file through os.TempDir(). In a
	// gomobile Android process that resolves to /data/local/tmp, which is not
	// writable by the app UID. Keep encrypted upload staging inside the already
	// validated app-private media root and always remove it after UploadReader.
	uploadTemp, err := createPrivateUploadTemp(r.media.root)
	if err != nil {
		return nil, err
	}
	uploadTempPath := uploadTemp.Name()
	defer func() {
		_ = uploadTemp.Close()
		_ = os.Remove(uploadTempPath)
	}()
	uploaded, err := r.wa().UploadReader(ctx, file, uploadTemp, mediaType)
	if err != nil {
		// An upload that failed did not deliver a message, but it did reach
		// WhatsApp's media servers — a 419/429 here is an account-level answer that
		// must be classified, not retried blind.
		return nil, wireError(err)
	}
	caption := stringValue(payload["caption"])
	fileName := stringValue(payload["fileName"])
	if fileName == "" {
		fileName = record.OriginalName
	}
	var message *waE2E.Message
	switch kind {
	case "image":
		photo := &waE2E.ImageMessage{
			URL: &uploaded.URL, DirectPath: &uploaded.DirectPath, MediaKey: uploaded.MediaKey,
			FileEncSHA256: uploaded.FileEncSHA256, FileSHA256: uploaded.FileSHA256,
			FileLength: &uploaded.FileLength, Mimetype: proto.String(photoSource.Mimetype),
			Caption:           proto.String(caption),
			MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
		}
		// Pixel size and inline preview are what make the bubble render like a
		// photo instead of an undecided placeholder with a download button.
		if photoSource.Width > 0 && photoSource.Height > 0 {
			photo.Width = proto.Uint32(photoSource.Width)
			photo.Height = proto.Uint32(photoSource.Height)
		}
		if len(photoSource.Thumbnail) > 0 {
			photo.JPEGThumbnail = photoSource.Thumbnail
		}
		message = &waE2E.Message{ImageMessage: photo}
	case "video":
		message = &waE2E.Message{VideoMessage: &waE2E.VideoMessage{
			URL: &uploaded.URL, DirectPath: &uploaded.DirectPath, MediaKey: uploaded.MediaKey,
			FileEncSHA256: uploaded.FileEncSHA256, FileSHA256: uploaded.FileSHA256,
			FileLength: &uploaded.FileLength, Mimetype: proto.String(record.MimeType),
			Caption: proto.String(caption),
		}}
	case "audio":
		ptt := payload["ptt"] == true
		mimetype := record.MimeType
		seconds := uint32(0)
		var waveform []byte
		if ptt {
			mimetype = "audio/ogg; codecs=opus"
			seconds = voiceDurationSeconds(payload["durationSeconds"])
			waveform = voiceWaveform(payload["waveform"], record.SHA256)
		}
		message = &waE2E.Message{AudioMessage: &waE2E.AudioMessage{
			URL: &uploaded.URL, DirectPath: &uploaded.DirectPath, MediaKey: uploaded.MediaKey,
			FileEncSHA256: uploaded.FileEncSHA256, FileSHA256: uploaded.FileSHA256,
			FileLength: &uploaded.FileLength, Mimetype: proto.String(mimetype),
			PTT: proto.Bool(ptt), Seconds: proto.Uint32(seconds), Waveform: waveform,
		}}
	case "document":
		message = &waE2E.Message{DocumentMessage: &waE2E.DocumentMessage{
			URL: &uploaded.URL, DirectPath: &uploaded.DirectPath, MediaKey: uploaded.MediaKey,
			FileEncSHA256: uploaded.FileEncSHA256, FileSHA256: uploaded.FileSHA256,
			FileLength: &uploaded.FileLength, Mimetype: proto.String(record.MimeType),
			Title: proto.String(fileName), FileName: proto.String(fileName), Caption: proto.String(caption),
		}}
	}
	message = r.applyEphemeral(chat, message)
	response, err := r.wa().SendMessage(ctx, chat, message)
	if err != nil {
		return nil, wireError(err)
	}
	r.rememberQuotable(normalizeJID(chat), string(response.ID), r.ownAddressFor(chat), message)
	result := messageResult(chat, response.ID)
	result.(map[string]any)["mediaId"] = record.ID
	return result, nil
}

func createPrivateUploadTemp(root string) (*os.File, error) {
	file, err := os.CreateTemp(root, ".whatsmeow-upload-*")
	if err != nil {
		return nil, fmt.Errorf("failed to create private upload temp: %w", err)
	}
	if err = file.Chmod(0o600); err != nil {
		_ = file.Close()
		_ = os.Remove(file.Name())
		return nil, fmt.Errorf("failed to restrict private upload temp: %w", err)
	}
	return file, nil
}

// WhatsApp renders native PTT messages from the explicit duration and a
// 64-sample, 0..100 amplitude waveform. The Android encoder supplies both: it
// holds the PCM anyway, so it measures the real envelope there instead of
// decoding the finished OGG again here, which would cost CPU, memory and
// another codec dependency.
func voiceDurationSeconds(value any) uint32 {
	seconds, ok := value.(float64)
	if !ok || math.IsNaN(seconds) || math.IsInf(seconds, 0) || seconds < 1 {
		return 1
	}
	return uint32(math.Min(24*60*60, math.Round(seconds)))
}

const voiceWaveformSamples = 64

// voiceWaveform prefers the measured envelope the device sent along and falls
// back only for audio this process did not synthesize.
func voiceWaveform(value any, seed string) []byte {
	if measured, ok := decodeVoiceWaveform(stringValue(value)); ok {
		return measured
	}
	return fallbackVoiceWaveform(seed)
}

func decodeVoiceWaveform(encoded string) ([]byte, bool) {
	encoded = strings.TrimSpace(encoded)
	if encoded == "" {
		return nil, false
	}
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil || len(raw) != voiceWaveformSamples {
		return nil, false
	}
	for index, sample := range raw {
		if sample > 100 {
			raw[index] = 100
		}
	}
	return raw, true
}

// fallbackVoiceWaveform draws a plausible speech envelope for audio that
// arrived without a measured one.
//
// It is seeded from the upload digest rather than being a constant: one fixed
// 64-byte pattern under every single voice note is a machine-readable
// fingerprint, because no two real recordings look alike. Seeding from the
// digest keeps a retry of the same upload byte-identical to the shape already
// on the wire while giving every distinct recording its own.
func fallbackVoiceWaveform(seed string) []byte {
	digest := sha256.Sum256([]byte(seed))
	random := rand.New(rand.NewSource(int64(binary.BigEndian.Uint64(digest[:8]))))
	waveform := make([]byte, voiceWaveformSamples)
	loudness := 34.0 + random.Float64()*30.0
	for index := range waveform {
		arc := math.Sin(float64(index)/float64(voiceWaveformSamples-1)*math.Pi) * loudness
		jitter := random.Float64()*26.0 - 8.0
		waveform[index] = byte(math.Max(4, math.Min(100, math.Round(arc+jitter+14.0))))
	}
	return waveform
}

func (r *Runtime) editMessage(payload map[string]any) (any, error) {
	raw, ok := payload["message"].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("message key missing")
	}
	key, err := parseMessageKey(raw)
	if err != nil {
		return nil, err
	}
	chat, _ := parseJID(key.ChatID)
	text, err := outgoingText(payload)
	if err != nil {
		return nil, err
	}
	edited := &waE2E.Message{Conversation: proto.String(text)}
	message := r.wa().BuildEdit(chat, types.MessageID(key.ID), edited)
	ctx, cancel := r.contextWithTimeout(90 * time.Second)
	defer cancel()
	response, err := r.wa().SendMessage(ctx, chat, message)
	if err != nil {
		return nil, wireError(err)
	}
	return messageResult(chat, response.ID), nil
}

func (r *Runtime) deleteMessage(payload map[string]any) (any, error) {
	raw, ok := payload["message"].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("message key missing")
	}
	key, err := parseMessageKey(raw)
	if err != nil {
		return nil, err
	}
	chat, _ := parseJID(key.ChatID)
	// Device.ID is nil before pairing and after logout. Every other call site
	// checks it; without the check here a delete_message arriving while unpaired
	// dereferenced nil.
	device := r.deviceStore()
	if device == nil || device.ID == nil {
		return nil, fmt.Errorf("WhatsApp is not paired")
	}
	sender := device.ID.ToNonAD()
	message := r.wa().BuildRevoke(chat, sender, types.MessageID(key.ID))
	ctx, cancel := r.contextWithTimeout(90 * time.Second)
	defer cancel()
	response, err := r.wa().SendMessage(ctx, chat, message)
	if err != nil {
		return nil, wireError(err)
	}
	return messageResult(chat, response.ID), nil
}

func actionSuccess(id string, result any, duplicate bool) map[string]any {
	return map[string]any{
		"v":         protocolVersion,
		"type":      "action_result",
		"id":        id,
		"ok":        true,
		"duplicate": duplicate,
		"result":    result,
	}
}

func actionFailure(id string, err error) map[string]any {
	return map[string]any{
		"v":     protocolVersion,
		"type":  "action_result",
		"id":    id,
		"ok":    false,
		"error": publicError(err),
	}
}

var outboundActions = map[string]bool{
	"send_text":      true,
	"send_reply":     true,
	"send_media":     true,
	"send_reaction":  true,
	"edit_message":   true,
	"delete_message": true,
}

// reportActionFailure turns account-level send failures into the same durable,
// fail-closed safety path used for connection restrictions. It deliberately
// publishes only bounded classifications, never a JID, payload or raw error.
func (r *Runtime) reportActionFailure(action string, err error) {
	if !outboundActions[action] {
		return
	}
	code, _ := classifyActionError(err)
	kind := ""
	switch code {
	case "timelock_463":
		kind = "timelock"
	case "server_error_419", "server_error_429":
		kind = "message_capping"
	case "not_logged_in", "server_error_401", "server_error_403":
		kind = "connection_hard_stop"
	case "timeout":
		kind = "outbound_timeout"
	default:
		if strings.HasPrefix(code, "server_error_4") {
			kind = "restriction"
		}
	}
	if kind == "" {
		return
	}
	r.publish(map[string]any{
		"type":      "safety",
		"kind":      kind,
		"detail":    "native_" + truncate(action, 32) + "_" + code,
		"errorCode": code,
	})
	// A send rejection tells us *that* the account is limited, never for how long.
	// Asking WhatsApp turns an open-ended block into a lock with a real expiry, so
	// the bot waits exactly as long as it has to instead of either muting itself
	// forever or hammering into a live restriction. The probe is the same one the
	// link-up path runs, is cached for safetyRefreshCacheTTL, and runs off this
	// goroutine because it must not extend the action mutex.
	if kind == "timelock" || kind == "message_capping" {
		r.launch(func() { r.probeAccountLimits() })
	}
}

// probeAccountLimits re-reads the account's reach-out time lock and new-chat cap
// and publishes the result, which is what lets those locks clear themselves.
//
// Rate-limited on its own clock rather than on the read cache: a restriction
// usually rejects several queued sends in a row, and answering each of them with
// a fresh pair of MEX queries would be its own suspicious burst. One probe per
// limitProbeInterval is enough — the answer carries an expiry, so nothing is
// gained by asking again sooner.
func (r *Runtime) probeAccountLimits() {
	now := time.Now()
	r.safetyRefreshMu.Lock()
	if !r.lastLimitProbe.IsZero() && now.Sub(r.lastLimitProbe) < limitProbeInterval {
		r.safetyRefreshMu.Unlock()
		return
	}
	r.lastLimitProbe = now
	// Drop the read cache: the point is to learn about a limit that appeared after
	// the last probe.
	r.safetyCached = nil
	r.safetyRefreshMu.Unlock()
	if _, err := r.refreshSafety(); err != nil {
		newAndroidLogger("nativewa-safety", "WARN").
			Warnf("account limit probe failed: %v", err)
	}
}

const limitProbeInterval = 2 * time.Minute

func classifyActionError(err error) (string, string) {
	if errors.Is(err, errIdempotencyInDoubt) {
		return "idempotency_in_doubt", "WhatsApp operation outcome is unknown; it will not be repeated"
	}
	if errors.Is(err, errActionPanicked) {
		return "native_action_aborted", "On-device WhatsApp action aborted unexpectedly"
	}
	if errors.Is(err, errInvalidBlockTarget) {
		return "invalid_block_target", "WhatsApp could not resolve the contact block identity"
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, whatsmeow.ErrMessageTimedOut) {
		return "timeout", "On-device WhatsApp operation timed out"
	}
	if errors.Is(err, whatsmeow.ErrNotLoggedIn) {
		return "not_logged_in", "WhatsApp linked device is logged out"
	}
	if code := whatsmeowServerErrorCode(err); code != 0 {
		if code == 463 {
			return "timelock_463", "WhatsApp restricted outbound messaging"
		}
		return fmt.Sprintf("server_error_%d", code), "WhatsApp rejected the operation"
	}
	return "native_transport_failed", "On-device WhatsApp operation failed"
}

// whatsmeowServerErrorCode recovers the WhatsApp status code from a failed
// operation. Everything account-level hangs off this number: 463 arms the
// reach-out time lock, 419/429 the new-chat cap, 401/403 the connection lock. If
// it silently returns 0 the bot keeps sending into a restriction, which is exactly
// what turns one strike into a ban — so the typed carrier is preferred and the
// string parse is kept only as a fallback for the one shape that has none.
func whatsmeowServerErrorCode(err error) int {
	// Info queries (blocklist, MEX safety probes) carry the code as a field.
	var iqErr *whatsmeow.IQError
	if errors.As(err, &iqErr) && iqErr.Code != 0 {
		return iqErr.Code
	}
	// Message sends have no typed carrier: whatsmeow formats them as
	// "server returned error <code>". Gated on the sentinel so an unrelated error
	// text can never be misread as a status code.
	if !errors.Is(err, whatsmeow.ErrServerReturnedError) {
		return 0
	}
	parts := strings.Fields(err.Error())
	for index, part := range parts {
		if part != "status" || index+1 >= len(parts) {
			continue
		}
		code, _ := strconv.Atoi(strings.TrimRight(parts[index+1], ":,"))
		if code != 0 {
			return code
		}
	}
	if len(parts) > 0 {
		code, _ := strconv.Atoi(strings.TrimRight(parts[len(parts)-1], ":,"))
		return code
	}
	return 0
}

func messageResult(chat types.JID, id types.MessageID) any {
	return map[string]any{
		"message": map[string]any{
			"chatId": normalizeJID(chat),
			"id":     string(id),
			"fromMe": true,
		},
	}
}

func parseMessageKey(raw map[string]any) (messageKey, error) {
	value := messageKey{
		ChatID:      requiredString(raw, "chatId"),
		ID:          requiredString(raw, "id"),
		FromMe:      raw["fromMe"] == true,
		Participant: stringValue(raw["participant"]),
		Preview:     stringValue(raw["preview"]),
	}
	if _, err := parseJID(value.ChatID); err != nil {
		return messageKey{}, err
	}
	if value.Participant != "" {
		if _, err := parseJID(value.Participant); err != nil {
			return messageKey{}, err
		}
	}
	return value, nil
}

func parseJID(raw string) (types.JID, error) {
	jid, err := types.ParseJID(strings.TrimSpace(raw))
	if err != nil || jid.User == "" || jid.Server == "" {
		return types.EmptyJID, fmt.Errorf("invalid WhatsApp JID")
	}
	return jid.ToNonAD(), nil
}

func requiredString(values map[string]any, key string) string {
	return strings.TrimSpace(stringValue(values[key]))
}

func outgoingText(payload map[string]any) (string, error) {
	text := requiredString(payload, "text")
	if len(text) > 16384 {
		return "", fmt.Errorf("text is too long")
	}
	return text, nil
}

func stringValue(value any) string {
	if result, ok := value.(string); ok {
		return result
	}
	return ""
}

func digitsOnly(value string) string {
	var builder strings.Builder
	for _, char := range value {
		if char >= '0' && char <= '9' {
			builder.WriteRune(char)
		}
	}
	return builder.String()
}
