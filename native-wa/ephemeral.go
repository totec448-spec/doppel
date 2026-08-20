package nativewa

import (
	"time"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

// Disappearing messages are a per-chat setting the contact can turn on, and
// WhatsApp expects every participant to honour it: each message carries the
// expiry it was sent under. whatsmeow does not stamp outgoing messages by
// itself, so without this the contact's messages vanished on schedule while the
// bot's stayed in the thread forever — visible in the chat, and not something a
// real client does.
//
// The setting is learned from what arrives (the contact's own messages carry it,
// and a setting change arrives as its own event) and persisted, so a reconnect
// does not silently send the next reply unstamped.

type ephemeralSetting struct {
	expiration   uint32
	settingStamp int64
}

func (j *eventJournal) loadEphemeral() (map[string]ephemeralSetting, error) {
	rows, err := j.db.Query(`SELECT chat_id, expiration_seconds, setting_stamp FROM native_ephemeral`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make(map[string]ephemeralSetting)
	for rows.Next() {
		var chat string
		var setting ephemeralSetting
		if err = rows.Scan(&chat, &setting.expiration, &setting.settingStamp); err != nil {
			return nil, err
		}
		result[chat] = setting
	}
	return result, rows.Err()
}

func (j *eventJournal) storeEphemeral(chat string, setting ephemeralSetting) error {
	_, err := j.db.Exec(
		`INSERT INTO native_ephemeral(chat_id, expiration_seconds, setting_stamp, updated_at_ms)
		 VALUES (?, ?, ?, ?)
		 ON CONFLICT(chat_id) DO UPDATE SET
		   expiration_seconds = excluded.expiration_seconds,
		   setting_stamp      = excluded.setting_stamp,
		   updated_at_ms      = excluded.updated_at_ms`,
		chat,
		setting.expiration,
		setting.settingStamp,
		time.Now().UnixMilli(),
	)
	return err
}

// rememberEphemeral records a chat's disappearing-message timer. An older
// setting stamp than the one already stored is ignored: messages can arrive out
// of order, and a stale one must not undo a newer change.
func (r *Runtime) rememberEphemeral(chat types.JID, expiration uint32, settingStamp int64) {
	key := normalizeJID(chat)
	if key == "" {
		return
	}
	setting := ephemeralSetting{expiration: expiration, settingStamp: settingStamp}
	r.mu.Lock()
	if r.ephemeral == nil {
		r.ephemeral = make(map[string]ephemeralSetting)
	}
	current, known := r.ephemeral[key]
	if known && current.settingStamp > settingStamp {
		r.mu.Unlock()
		return
	}
	if known && current == setting {
		r.mu.Unlock()
		return
	}
	r.ephemeral[key] = setting
	r.mu.Unlock()
	_ = r.journal.storeEphemeral(key, setting)
}

func (r *Runtime) ephemeralFor(chat types.JID) ephemeralSetting {
	key := normalizeJID(chat)
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.ephemeral[key]
}

// learnEphemeralFromIncoming picks the timer up from ordinary traffic: a message
// sent in a disappearing chat carries the expiry in its context, and an explicit
// change arrives as a protocol message.
func (r *Runtime) learnEphemeralFromIncoming(chat types.JID, message *waE2E.Message, fallbackStamp int64) {
	if message == nil {
		return
	}
	if protocolMessage := message.GetProtocolMessage(); protocolMessage != nil &&
		protocolMessage.GetType() == waE2E.ProtocolMessage_EPHEMERAL_SETTING {
		stamp := protocolMessage.GetEphemeralSettingTimestamp()
		if stamp == 0 {
			stamp = fallbackStamp
		}
		r.rememberEphemeral(chat, protocolMessage.GetEphemeralExpiration(), stamp)
		return
	}
	context := messageContext(message)
	if context == nil {
		return
	}
	expiration := context.GetExpiration()
	stamp := context.GetEphemeralSettingTimestamp()
	if expiration == 0 && stamp == 0 {
		// Nothing said about the setting either way — a plain message in a chat
		// that never had the feature on. Silence is not "turned off".
		return
	}
	if stamp == 0 {
		stamp = fallbackStamp
	}
	r.rememberEphemeral(chat, expiration, stamp)
}

// applyEphemeral stamps an outgoing message with the chat's timer. A plain
// Conversation string has nowhere to carry context, so it is promoted to an
// ExtendedTextMessage — which is what official clients send in these chats.
func (r *Runtime) applyEphemeral(chat types.JID, message *waE2E.Message) *waE2E.Message {
	setting := r.ephemeralFor(chat)
	if setting.expiration == 0 || message == nil {
		return message
	}
	if text := message.GetConversation(); text != "" {
		message = &waE2E.Message{
			ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String(text)},
		}
	}
	context := ensureMessageContext(message)
	if context == nil {
		return message
	}
	context.Expiration = proto.Uint32(setting.expiration)
	if setting.settingStamp > 0 {
		context.EphemeralSettingTimestamp = proto.Int64(setting.settingStamp)
	}
	return message
}

// ensureMessageContext returns the message's context, creating it when absent.
// [messageContext] only reads, and a getter on a nil context yields nil — which
// is fine for inspection and useless for stamping. An existing context is
// returned untouched so a quoted reply keeps its quote.
func ensureMessageContext(message *waE2E.Message) *waE2E.ContextInfo {
	ensure := func(current *waE2E.ContextInfo, assign func(*waE2E.ContextInfo)) *waE2E.ContextInfo {
		if current == nil {
			current = &waE2E.ContextInfo{}
			assign(current)
		}
		return current
	}
	switch {
	case message.GetExtendedTextMessage() != nil:
		value := message.GetExtendedTextMessage()
		return ensure(value.GetContextInfo(), func(c *waE2E.ContextInfo) { value.ContextInfo = c })
	case message.GetImageMessage() != nil:
		value := message.GetImageMessage()
		return ensure(value.GetContextInfo(), func(c *waE2E.ContextInfo) { value.ContextInfo = c })
	case message.GetVideoMessage() != nil:
		value := message.GetVideoMessage()
		return ensure(value.GetContextInfo(), func(c *waE2E.ContextInfo) { value.ContextInfo = c })
	case message.GetAudioMessage() != nil:
		value := message.GetAudioMessage()
		return ensure(value.GetContextInfo(), func(c *waE2E.ContextInfo) { value.ContextInfo = c })
	case message.GetDocumentMessage() != nil:
		value := message.GetDocumentMessage()
		return ensure(value.GetContextInfo(), func(c *waE2E.ContextInfo) { value.ContextInfo = c })
	default:
		return nil
	}
}
