package nativewa

import (
	"encoding/json"
	"testing"

	waBinary "go.mau.fi/whatsmeow/binary"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

func mustTestJID(t *testing.T, raw string) types.JID {
	t.Helper()
	jid, err := types.ParseJID(raw)
	if err != nil {
		t.Fatalf("parse test JID %q: %v", raw, err)
	}
	return jid
}

func TestBlockCandidatesPreferLIDAndDeduplicate(t *testing.T) {
	pn := mustTestJID(t, "49123456789@s.whatsapp.net")
	lid := mustTestJID(t, "123456789012345@lid")
	ordered := orderedUniqueBlockCandidates([]types.JID{pn, lid, pn})
	if len(ordered) != 2 {
		t.Fatalf("unexpected candidate count: %d", len(ordered))
	}
	if normalizeJID(ordered[0]) != normalizeJID(lid) {
		t.Fatalf("LID was not preferred: %v", ordered)
	}
}

func TestUnblockCandidatesPreferActuallyBlockedIdentity(t *testing.T) {
	pn := mustTestJID(t, "49123456789@s.whatsapp.net")
	lid := mustTestJID(t, "123456789012345@lid")
	ordered := blockedCandidatesFirst([]types.JID{lid, pn}, map[string]bool{normalizeJID(pn): true})
	if normalizeJID(ordered[0]) != normalizeJID(pn) {
		t.Fatalf("blocked identity was not preferred: %v", ordered)
	}
}

func TestBlockPayloadAliasesAreBoundedAndDirectOnly(t *testing.T) {
	raw := make([]any, 0, 20)
	raw = append(raw, "123456789012345@lid", "49123456789@s.whatsapp.net", "group@g.us", "invalid")
	for index := 0; index < 20; index++ {
		raw = append(raw, "491234567"+string(rune('0'+index%10))+"@s.whatsapp.net")
	}
	parsed := payloadDirectJIDs(raw)
	if len(parsed) != 16 {
		t.Fatalf("alias bound = %d, want 16", len(parsed))
	}
	for _, jid := range parsed {
		if !isDirectUserServer(jid.Server) {
			t.Fatalf("non-direct alias survived: %s", jid)
		}
	}
}

func TestBlocklistV2QueryIncludesLIDAndPhoneJID(t *testing.T) {
	pn := mustTestJID(t, "49123456789@s.whatsapp.net")
	lid := mustTestJID(t, "123456789012345@lid")
	query := blocklistUpdateQuery(lid, pn, events.BlocklistChangeActionBlock)
	if query.Namespace != "blocklist" || string(query.Type) != "set" || query.To != types.ServerJID {
		t.Fatalf("unexpected IQ envelope: %#v", query)
	}
	nodes, ok := query.Content.([]waBinary.Node)
	if !ok || len(nodes) != 1 {
		t.Fatalf("unexpected IQ content: %#v", query.Content)
	}
	attrs := nodes[0].Attrs
	if attrs["jid"] != lid || attrs["pn_jid"] != pn || attrs["action"] != "block" {
		t.Fatalf("blocklist-v2 identity pair missing: %#v", attrs)
	}
}

func TestUnblockQueryOmitsPhoneJID(t *testing.T) {
	pn := mustTestJID(t, "49123456789@s.whatsapp.net")
	lid := mustTestJID(t, "123456789012345@lid")
	query := blocklistUpdateQuery(lid, pn, events.BlocklistChangeActionUnblock)
	nodes := query.Content.([]waBinary.Node)
	if _, found := nodes[0].Attrs["pn_jid"]; found {
		t.Fatalf("unblock must not send pn_jid: %#v", nodes[0].Attrs)
	}
}

func TestReachoutTimelockParserAcceptsNestedWhatsAppFields(t *testing.T) {
	raw := json.RawMessage(`{"data":{"xwa2_fetch_account_reachout_timelock":{"is_active":true,"time_enforcement_ends":"1785783600","enforcement_type":"RATE_LIMIT"}}}`)
	parsed, err := parseReachoutTimelock(raw)
	if err != nil {
		t.Fatal(err)
	}
	if parsed["isActive"] != true || parsed["enforcementType"] != "RATE_LIMIT" {
		t.Fatalf("unexpected parsed state: %#v", parsed)
	}
	if parsed["expiresAtMs"] != int64(1785783600000) {
		t.Fatalf("unexpected expiry: %#v", parsed["expiresAtMs"])
	}
}

func TestMessageCapExtractionKeepsNumbersWithoutUnrelatedPrivateData(t *testing.T) {
	raw := json.RawMessage(`{"data":{"cap":{"messages_sent":10,"max_allowed":10,"is_active":true},"profile_name":"Private"}}`)
	parsed := extractSafetyScalars(raw)
	if parsed["data.cap.messages_sent"] != float64(10) || parsed["data.cap.max_allowed"] != float64(10) {
		t.Fatalf("message cap numbers missing: %#v", parsed)
	}
	if _, found := parsed["data.profile_name"]; found {
		t.Fatalf("unrelated private field leaked: %#v", parsed)
	}
	if !inferMessageCapLimited(parsed) {
		t.Fatalf("exhausted cap was not recognized: %#v", parsed)
	}
}
