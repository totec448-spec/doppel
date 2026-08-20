package nativewa

import (
	"database/sql"
	"strings"
	"time"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

// A reply bubble is drawn from the ContextInfo the sender attaches, not from a
// lookup on the receiving side: WhatsApp needs the quoted message id, the JID
// of whoever wrote it, and an embedded copy of that message. Desktop and web
// still show something when parts of that are missing because they resolve the
// id in their own local store; the mobile clients do not and silently render a
// plain message instead.
//
// The bridge therefore keeps the recent messages of every chat, so a reply can
// carry the real quoted content — also after the process was restarted, which
// an in-memory cache would not survive.
const (
	quotableRetention   = 600
	quotableMaxProtoLen = 96 * 1024
)

type quotableMessage struct {
	Participant types.JID
	Message     *waE2E.Message
}

func (r *Runtime) rememberQuotable(chat, stanzaID string, participant types.JID, message *waE2E.Message) {
	if r == nil || r.db == nil || chat == "" || stanzaID == "" || message == nil {
		return
	}
	stored := quotableCopy(message)
	if stored == nil {
		return
	}
	encoded, err := proto.Marshal(stored)
	if err != nil || len(encoded) > quotableMaxProtoLen {
		return
	}
	if _, err = r.db.Exec(
		`INSERT INTO native_quotable_messages
		 (chat_id, stanza_id, participant, message_proto, created_at_ms)
		 VALUES (?, ?, ?, ?, ?)
		 ON CONFLICT(chat_id, stanza_id) DO UPDATE SET
		 participant = excluded.participant,
		 message_proto = excluded.message_proto,
		 created_at_ms = excluded.created_at_ms`,
		chat,
		stanzaID,
		normalizeJID(participant),
		encoded,
		time.Now().UnixMilli(),
	); err != nil {
		return
	}
	_, _ = r.db.Exec(
		`DELETE FROM native_quotable_messages
		 WHERE rowid NOT IN (
		   SELECT rowid FROM native_quotable_messages ORDER BY created_at_ms DESC LIMIT ?
		 )`,
		quotableRetention,
	)
}

func (r *Runtime) lookupQuotable(chat, stanzaID string) (quotableMessage, bool) {
	if r == nil || r.db == nil || chat == "" || stanzaID == "" {
		return quotableMessage{}, false
	}
	var participant string
	var encoded []byte
	err := r.db.QueryRow(
		`SELECT participant, message_proto FROM native_quotable_messages
		 WHERE chat_id = ? AND stanza_id = ?`,
		chat,
		stanzaID,
	).Scan(&participant, &encoded)
	if err == sql.ErrNoRows || err != nil {
		return quotableMessage{}, false
	}
	message := &waE2E.Message{}
	if err = proto.Unmarshal(encoded, message); err != nil {
		return quotableMessage{}, false
	}
	result := quotableMessage{Message: message}
	if jid, parseErr := types.ParseJID(strings.TrimSpace(participant)); parseErr == nil {
		result.Participant = jid.ToNonAD()
	}
	return result, true
}

// quotableCopy keeps what a quote preview needs and drops the rest. Nesting a
// quoted message that itself carries a quote would grow every reply chain, and
// the stored copy is only ever read back to be embedded again.
func quotableCopy(message *waE2E.Message) *waE2E.Message {
	clone, ok := proto.Clone(message).(*waE2E.Message)
	if !ok || clone == nil {
		return nil
	}
	switch {
	case clone.GetExtendedTextMessage() != nil:
		clone.GetExtendedTextMessage().ContextInfo = nil
	case clone.GetImageMessage() != nil:
		clone.GetImageMessage().ContextInfo = nil
	case clone.GetVideoMessage() != nil:
		clone.GetVideoMessage().ContextInfo = nil
	case clone.GetAudioMessage() != nil:
		clone.GetAudioMessage().ContextInfo = nil
	case clone.GetDocumentMessage() != nil:
		clone.GetDocumentMessage().ContextInfo = nil
	case clone.GetStickerMessage() != nil:
		clone.GetStickerMessage().ContextInfo = nil
	case clone.GetConversation() != "":
		// A plain conversation has nothing to strip.
	default:
		// Reactions, receipts and protocol messages are never quoted.
		return nil
	}
	return clone
}

// ownAddressFor answers with the address this account uses inside chat. A chat
// addressed by hidden LID must be answered with our LID; mixing the namespaces
// produces a participant the receiving client cannot resolve.
func (r *Runtime) ownAddressFor(chat types.JID) types.JID {
	if r == nil {
		return types.EmptyJID
	}
	device := r.deviceStore()
	if device == nil {
		return types.EmptyJID
	}
	switch chat.Server {
	case types.HiddenUserServer, types.HostedLIDServer:
		if lid := device.GetLID(); !lid.IsEmpty() {
			return lid.ToNonAD()
		}
	}
	return device.GetJID().ToNonAD()
}

// contextFor builds the reply reference for one outgoing message.
//
// The participant is the decisive field: WhatsApp keys a quoted message by
// chat, id and author, so an empty author makes the whole reference
// unresolvable and the mobile clients drop the quote. In a direct chat the
// author is either this account or the chat partner, which is derivable even
// when the quoted message itself is no longer known.
func (r *Runtime) contextFor(chat types.JID, key messageKey) *waE2E.ContextInfo {
	context := &waE2E.ContextInfo{StanzaID: proto.String(key.ID)}
	participant := types.EmptyJID
	if key.Participant != "" {
		if jid, err := types.ParseJID(key.Participant); err == nil {
			participant = jid.ToNonAD()
		}
	}
	if stored, ok := r.lookupQuotable(normalizeJID(chat), key.ID); ok {
		context.QuotedMessage = stored.Message
		if participant.IsEmpty() && !stored.Participant.IsEmpty() {
			participant = stored.Participant
		}
	}
	if participant.IsEmpty() {
		if key.FromMe {
			participant = r.ownAddressFor(chat)
		} else if chat.Server != types.GroupServer && chat.Server != types.BroadcastServer {
			participant = chat.ToNonAD()
		}
	}
	if participant.IsEmpty() {
		// Without an author the reference would be dropped by the receiving
		// client anyway; sending the text alone at least keeps the reply.
		return nil
	}
	context.Participant = proto.String(participant.String())
	if context.QuotedMessage == nil {
		// The quoted message left the retention window. The id and the author
		// are correct, so a client that still has the original renders the real
		// preview; the placeholder only fills the copy it would embed.
		context.QuotedMessage = &waE2E.Message{
			Conversation: proto.String(quotePlaceholder(key.Preview)),
		}
	}
	return context
}

func quotePlaceholder(preview string) string {
	trimmed := strings.TrimSpace(preview)
	if trimmed == "" {
		return "…"
	}
	return truncate(trimmed, 512)
}
