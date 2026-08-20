package nativewa

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	waBinary "go.mau.fi/whatsmeow/binary"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// These IDs come from the pinned whatsmeow release's argo query registry.
// Keeping them beside the parser makes a protocol update an explicit, tested
// change instead of silently borrowing IDs from the Baileys implementation.
const (
	reachoutTimelockQueryID = "9462376090546244"
	messageCappingQueryID   = "25275321118723031"
	safetyQueryTimeout      = 20 * time.Second
	safetyRefreshCacheTTL   = 30 * time.Second
)

var errInvalidBlockTarget = errors.New("invalid block target")

// blocklistCacheTTL is how long the mirrored blocklist is trusted without asking
// WhatsApp again. It only bounds drift from a change this device never saw; every
// change it does see keeps the mirror exact and resets the clock.
const blocklistCacheTTL = 6 * time.Hour

// rememberBlocklist mirrors an authoritative blocklist answer.
//
// The mirror is what removes the request burst around a block. A native client
// keeps the list locally and learns changes from the push it already receives;
// this build used to re-read the whole list before the change, again to verify it,
// and a third time from the Blocklist event the change itself produced — four to
// five info queries for one block, repeated for every auto-block threshold hit.
// That shape is distinctive, and it is exactly the kind of pattern an account
// under review is measured on.
func (r *Runtime) rememberBlocklist(list *types.Blocklist) {
	if list == nil {
		return
	}
	mirror := make(map[string]bool, len(list.JIDs))
	for _, jid := range list.JIDs {
		if normalized := normalizeJID(jid); normalized != "" {
			mirror[normalized] = true
		}
	}
	r.mu.Lock()
	r.blocklistMirror = mirror
	r.blocklistDHash = list.DHash
	r.blocklistMirroredAt = time.Now()
	r.mu.Unlock()
}

// cachedBlocklist returns the mirrored list, or false when it is missing or stale.
func (r *Runtime) cachedBlocklist() (*types.Blocklist, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if r.blocklistMirror == nil || time.Since(r.blocklistMirroredAt) > blocklistCacheTTL {
		return nil, false
	}
	return blocklistFromMirror(r.blocklistMirror, r.blocklistDHash), true
}

func blocklistFromMirror(mirror map[string]bool, dhash string) *types.Blocklist {
	jids := make([]types.JID, 0, len(mirror))
	for raw := range mirror {
		if jid, err := types.ParseJID(raw); err == nil {
			jids = append(jids, jid)
		}
	}
	return &types.Blocklist{JIDs: jids, DHash: dhash}
}

// applyBlocklistChanges folds a change push into the mirror without a round trip.
// It reports false when the push cannot be applied safely — an empty change list,
// a "modify" action, or a previous-hash that does not match what we mirror — in
// which case the caller has to re-read the real list.
func (r *Runtime) applyBlocklistChanges(event *events.Blocklist) (*types.Blocklist, bool) {
	if event == nil || event.Action != "" || len(event.Changes) == 0 {
		return nil, false
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.blocklistMirror == nil {
		return nil, false
	}
	if event.PrevDHash != "" && r.blocklistDHash != "" && event.PrevDHash != r.blocklistDHash {
		// Our mirror missed a change; only a full read can resync it.
		return nil, false
	}
	for _, change := range event.Changes {
		normalized := normalizeJID(change.JID)
		if normalized == "" {
			continue
		}
		if change.Action == events.BlocklistChangeActionBlock {
			r.blocklistMirror[normalized] = true
		} else {
			delete(r.blocklistMirror, normalized)
		}
	}
	if event.DHash != "" {
		r.blocklistDHash = event.DHash
	}
	r.blocklistMirroredAt = time.Now()
	return blocklistFromMirror(r.blocklistMirror, r.blocklistDHash), true
}

// noteLocalBlockChange folds a change this device just made into the mirror, so the
// result can be reported without reading the list back.
func (r *Runtime) noteLocalBlockChange(targets []types.JID, blocked bool) *types.Blocklist {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.blocklistMirror == nil {
		r.blocklistMirror = make(map[string]bool, len(targets))
	}
	for _, jid := range targets {
		normalized := normalizeJID(jid)
		if normalized == "" {
			continue
		}
		if blocked {
			r.blocklistMirror[normalized] = true
		} else {
			delete(r.blocklistMirror, normalized)
		}
	}
	// The hash is WhatsApp's, not ours: invalidate it so the next change push
	// cannot believe our mirror is in sync when it may not be.
	r.blocklistDHash = ""
	r.blocklistMirroredAt = time.Now()
	return blocklistFromMirror(r.blocklistMirror, "")
}

func (r *Runtime) updateContactBlock(payload map[string]any, blocked bool) (any, error) {
	jid, err := parseJID(requiredString(payload, "chatId"))
	if err != nil {
		return nil, err
	}
	if !isDirectUserServer(jid.Server) {
		return nil, fmt.Errorf("contact block requires a direct-user JID")
	}

	supplied := payloadDirectJIDs(payload["aliases"])
	aliases := r.resolvedAliases(jid, supplied...)
	candidates := append([]types.JID{jid}, supplied...)
	for _, raw := range aliases {
		if alias, parseErr := parseJID(raw); parseErr == nil {
			candidates = append(candidates, alias)
		}
	}
	candidates = orderedUniqueBlockCandidates(candidates)

	ctx, cancel := r.contextWithTimeout(safetyQueryTimeout)
	defer cancel()
	// The pre-read is what makes this idempotent — blocking an already-blocked
	// contact must not send a second stanza. Served from the mirror when it is
	// warm, which is the normal case: the link-up safety probe fills it and every
	// change push keeps it exact.
	before, cached := r.cachedBlocklist()
	if !cached {
		fetched, fetchErr := r.wa().GetBlocklist(ctx)
		if fetchErr != nil {
			return nil, fmt.Errorf("read WhatsApp blocklist before update: %w", wireError(fetchErr))
		}
		r.rememberBlocklist(fetched)
		before = fetched
	}
	beforeSet := jidSet(before.JIDs)
	action := events.BlocklistChangeActionBlock
	if !blocked {
		action = events.BlocklistChangeActionUnblock
		candidates = blockedCandidatesFirst(candidates, beforeSet)
	}
	if blocked && containsAnyCandidate(beforeSet, candidates) {
		return r.confirmedBlockResult(jid, before, "block", nil), nil
	}

	lid, pn, err := r.resolveBlockIdentity(ctx, candidates, blocked)
	if err != nil {
		return nil, err
	}
	candidates = orderedUniqueBlockCandidates(append(candidates, lid, pn))
	if blocked && containsAnyCandidate(beforeSet, candidates) {
		return r.confirmedBlockResult(jid, before, "block", nil), nil
	}
	if !blocked && !containsAnyCandidate(beforeSet, candidates) {
		return r.confirmedBlockResult(jid, before, "unblock", nil), nil
	}
	if err = r.sendBlocklistUpdate(ctx, lid, pn, action); err != nil {
		if whatsmeowServerErrorCode(err) == 400 {
			return nil, fmt.Errorf("%w: WhatsApp rejected the LID/PN block identity", errInvalidBlockTarget)
		}
		return nil, fmt.Errorf("update WhatsApp blocklist: %w", wireError(err))
	}

	// No read-back. The update stanza either succeeded or returned an error, and
	// WhatsApp confirms it a second time through the Blocklist push that follows —
	// which this build now folds into the mirror without a request of its own. The
	// old verify read doubled the query count per block to prove something the
	// error path had already ruled out.
	after := r.noteLocalBlockChange([]types.JID{lid, pn}, blocked)

	operation := "unblock"
	if blocked {
		operation = "block"
	}
	r.clearSafetyCache()
	return r.confirmedBlockResult(jid, after, operation, []string{normalizeJID(lid)}), nil
}

func (r *Runtime) clearSafetyCache() {
	r.safetyRefreshMu.Lock()
	r.safetyCached = nil
	r.safetyCachedAt = time.Time{}
	r.safetyRefreshMu.Unlock()
}

func (r *Runtime) confirmedBlockResult(jid types.JID, blocklist *types.Blocklist, operation string, updated []string) any {
	if updated == nil {
		updated = []string{}
	}
	result := blocklistPayload(blocklist)
	result["operation"] = operation
	result["target"] = normalizeJID(jid)
	result["updatedJids"] = updated
	result["whatsappConfirmed"] = true
	r.publishSafety("blocklist_update", result)
	return result
}

func payloadDirectJIDs(raw any) []types.JID {
	values, ok := raw.([]any)
	if !ok {
		return nil
	}
	capacity := len(values)
	if capacity > 16 {
		capacity = 16
	}
	result := make([]types.JID, 0, capacity)
	for _, value := range values {
		if len(result) >= 16 {
			break
		}
		jid, err := parseJID(stringValue(value))
		if err != nil || !isDirectUserServer(jid.Server) {
			continue
		}
		result = append(result, jid)
	}
	return result
}

func isDirectUserServer(server string) bool {
	return server == types.DefaultUserServer || server == types.HiddenUserServer ||
		server == types.HostedServer || server == types.HostedLIDServer
}

func (r *Runtime) resolveBlockIdentity(ctx context.Context, candidates []types.JID, requirePN bool) (types.JID, types.JID, error) {
	var lid, pn types.JID
	for _, candidate := range candidates {
		switch candidate.Server {
		case types.HiddenUserServer, types.HostedLIDServer:
			if lid.IsEmpty() {
				lid = candidate.ToNonAD()
			}
		case types.DefaultUserServer, types.HostedServer:
			if pn.IsEmpty() {
				pn = candidate.ToNonAD()
			}
		}
	}

	device := r.deviceStore()
	if device != nil && device.LIDs != nil {
		if lid.IsEmpty() && !pn.IsEmpty() {
			if mapped, mapErr := device.LIDs.GetLIDForPN(ctx, pn); mapErr == nil && !mapped.IsEmpty() {
				lid = mapped.ToNonAD()
			}
		}
		if pn.IsEmpty() && !lid.IsEmpty() {
			if mapped, mapErr := device.LIDs.GetPNForLID(ctx, lid); mapErr == nil && !mapped.IsEmpty() {
				pn = mapped.ToNonAD()
			}
		}
	}

	// Fresh contacts may not have a durable PN->LID mapping yet. USync
	// returns the authoritative LID and persists the mapping in whatsmeow.
	if lid.IsEmpty() && !pn.IsEmpty() {
		infos, infoErr := r.wa().GetUserInfo(ctx, []types.JID{pn})
		if infoErr != nil {
			return types.EmptyJID, types.EmptyJID, fmt.Errorf("%w: resolve WhatsApp LID: %v", errInvalidBlockTarget, infoErr)
		}
		for _, info := range infos {
			if !info.LID.IsEmpty() {
				lid = info.LID.ToNonAD()
				break
			}
		}
	}
	if lid.IsEmpty() {
		return types.EmptyJID, types.EmptyJID, fmt.Errorf("%w: WhatsApp LID is unavailable", errInvalidBlockTarget)
	}
	if requirePN && pn.IsEmpty() {
		return types.EmptyJID, types.EmptyJID, fmt.Errorf("%w: phone-number JID is unavailable", errInvalidBlockTarget)
	}
	return lid, pn, nil
}

func (r *Runtime) sendBlocklistUpdate(ctx context.Context, lid, pn types.JID, action events.BlocklistChangeAction) error {
	_, err := r.wa().DangerousInternals().SendIQ(ctx, blocklistUpdateQuery(lid, pn, action))
	return err
}

func blocklistUpdateQuery(lid, pn types.JID, action events.BlocklistChangeAction) whatsmeow.DangerousInfoQuery {
	attrs := waBinary.Attrs{
		"jid":    lid,
		"action": string(action),
	}
	if action == events.BlocklistChangeActionBlock {
		attrs["pn_jid"] = pn
	}
	return whatsmeow.DangerousInfoQuery{
		Namespace: "blocklist",
		Type:      whatsmeow.DangerousInfoQueryType("set"),
		To:        types.ServerJID,
		Content: []waBinary.Node{{
			Tag:   "item",
			Attrs: attrs,
		}},
	}
}

func orderedUniqueBlockCandidates(values []types.JID) []types.JID {
	seen := make(map[string]bool, len(values))
	result := make([]types.JID, 0, len(values))
	for _, value := range values {
		key := normalizeJID(value)
		if key == "" || seen[key] {
			continue
		}
		seen[key] = true
		result = append(result, value.ToNonAD())
	}
	sort.SliceStable(result, func(i, j int) bool {
		return isLIDServer(result[i].Server) && !isLIDServer(result[j].Server)
	})
	return result
}

func blockedCandidatesFirst(values []types.JID, blocked map[string]bool) []types.JID {
	result := append([]types.JID(nil), values...)
	sort.SliceStable(result, func(i, j int) bool {
		return blocked[normalizeJID(result[i])] && !blocked[normalizeJID(result[j])]
	})
	return result
}

func isLIDServer(server string) bool {
	return server == types.HiddenUserServer || server == types.HostedLIDServer
}

func jidSet(values []types.JID) map[string]bool {
	result := make(map[string]bool, len(values))
	for _, value := range values {
		if normalized := normalizeJID(value); normalized != "" {
			result[normalized] = true
		}
	}
	return result
}

func containsAnyCandidate(values map[string]bool, candidates []types.JID) bool {
	for _, candidate := range candidates {
		if values[normalizeJID(candidate)] {
			return true
		}
	}
	return false
}

func blocklistPayload(blocklist *types.Blocklist) map[string]any {
	jids := make([]string, 0, len(blocklist.JIDs))
	for _, jid := range blocklist.JIDs {
		if normalized := normalizeJID(jid); normalized != "" {
			jids = append(jids, normalized)
		}
	}
	sort.Strings(jids)
	return map[string]any{
		"count": len(jids),
		"jids":  jids,
		"dhash": truncate(blocklist.DHash, 200),
	}
}

// publishBlocklistChange answers a change push from the mirror when it can, and
// only falls back to a full read when the push does not carry enough to stay in
// sync. That is what turns the third query of every block into no query at all.
func (r *Runtime) publishBlocklistChange(event *events.Blocklist) {
	if updated, ok := r.applyBlocklistChanges(event); ok {
		payload := blocklistPayload(updated)
		payload["source"] = "blocklist_event"
		payload["checkedAtMs"] = time.Now().UnixMilli()
		r.publishSafety("blocklist_set", payload)
		return
	}
	r.publishCurrentBlocklist("blocklist_event")
}

func (r *Runtime) publishCurrentBlocklist(source string) {
	ctx, cancel := r.contextWithTimeout(safetyQueryTimeout)
	defer cancel()
	blocklist, err := r.wa().GetBlocklist(ctx)
	if err != nil {
		r.publishSafety("blocklist_refresh_failed", map[string]any{
			"source":    truncate(source, 80),
			"errorCode": classifyRefreshError(err),
		})
		return
	}
	r.rememberBlocklist(blocklist)
	payload := blocklistPayload(blocklist)
	payload["source"] = truncate(source, 80)
	payload["checkedAtMs"] = time.Now().UnixMilli()
	r.publishSafety("blocklist_set", payload)
}

func (r *Runtime) refreshSafety() (any, error) {
	r.safetyRefreshMu.Lock()
	defer r.safetyRefreshMu.Unlock()
	if r.safetyCached != nil && time.Since(r.safetyCachedAt) < safetyRefreshCacheTTL {
		return r.safetyCached, nil
	}
	if !r.wa().IsLoggedIn() {
		return nil, fmt.Errorf("WhatsApp linked device is not logged in")
	}
	ctx, cancel := r.contextWithTimeout(safetyQueryTimeout)
	defer cancel()

	checkedAt := time.Now().UnixMilli()
	result := map[string]any{"checkedAtMs": checkedAt}
	errorsByCheck := map[string]string{}
	if blocklist, err := r.wa().GetBlocklist(ctx); err != nil {
		errorsByCheck["blocklist"] = classifyRefreshError(err)
	} else {
		r.rememberBlocklist(blocklist)
		payload := blocklistPayload(blocklist)
		payload["checkedAtMs"] = checkedAt
		result["blocklist"] = payload
		r.publishSafety("blocklist_set", payload)
	}

	if raw, err := r.wa().DangerousInternals().SendMexIQ(ctx, reachoutTimelockQueryID, map[string]any{}); err != nil {
		errorsByCheck["reachoutTimelock"] = classifyRefreshError(err)
	} else if reachout, parseErr := parseReachoutTimelock(raw); parseErr != nil {
		errorsByCheck["reachoutTimelock"] = "response_parse_failed"
	} else {
		reachout["checkedAtMs"] = checkedAt
		result["reachoutTimelock"] = reachout
		r.publishSafety("reachout_timelock", reachout)
	}

	variables := map[string]any{"input": map[string]any{"type": "INDIVIDUAL_NEW_CHAT_MSG"}}
	if raw, err := r.wa().DangerousInternals().SendMexIQ(ctx, messageCappingQueryID, variables); err != nil {
		errorsByCheck["messageCapping"] = classifyRefreshError(err)
	} else {
		capping := extractSafetyScalars(raw)
		capping["limited"] = inferMessageCapLimited(capping)
		capping["checkedAtMs"] = checkedAt
		result["messageCapping"] = capping
		r.publishSafety("message_capping_status", capping)
	}

	result["errors"] = errorsByCheck
	result["complete"] = len(errorsByCheck) == 0
	r.safetyCached = result
	r.safetyCachedAt = time.Now()
	return result, nil
}

func inferMessageCapLimited(values map[string]any) bool {
	var sent, maximum *int64
	for key, value := range values {
		lower := strings.ToLower(key)
		if (strings.Contains(lower, "capped") || strings.Contains(lower, "reached")) && boolValue(value) {
			return true
		}
		parsed := int64Value(value)
		if strings.Contains(lower, "remaining") && parsed <= 0 {
			return true
		}
		if strings.Contains(lower, "sent") || strings.Contains(lower, "current_count") {
			copy := parsed
			sent = &copy
		}
		if strings.Contains(lower, "max_allowed") || strings.HasSuffix(lower, ".limit") {
			copy := parsed
			maximum = &copy
		}
	}
	return sent != nil && maximum != nil && *maximum > 0 && *sent >= *maximum
}

func classifyRefreshError(err error) string {
	code, _ := classifyActionError(err)
	return code
}

func parseReachoutTimelock(raw json.RawMessage) (map[string]any, error) {
	var root any
	if err := json.Unmarshal(raw, &root); err != nil {
		return nil, err
	}
	active, activeFound := recursiveScalar(root, "is_active", "isActive")
	ends, endsFound := recursiveScalar(root, "time_enforcement_ends", "timeEnforcementEnds")
	enforcement, enforcementFound := recursiveScalar(root, "enforcement_type", "enforcementType")
	if !activeFound && !endsFound && !enforcementFound {
		return nil, fmt.Errorf("reachout timelock fields missing")
	}
	result := map[string]any{
		"isActive":        boolValue(active),
		"enforcementType": stringValue(enforcement),
	}
	if seconds := int64Value(ends); seconds > 0 {
		result["expiresAtMs"] = seconds * 1000
	}
	return result, nil
}

func recursiveScalar(value any, keys ...string) (any, bool) {
	wanted := make(map[string]bool, len(keys))
	for _, key := range keys {
		wanted[key] = true
	}
	var walk func(any) (any, bool)
	walk = func(current any) (any, bool) {
		switch typed := current.(type) {
		case map[string]any:
			for key, nested := range typed {
				if wanted[key] {
					return nested, true
				}
			}
			for _, nested := range typed {
				if found, ok := walk(nested); ok {
					return found, true
				}
			}
		case []any:
			for _, nested := range typed {
				if found, ok := walk(nested); ok {
					return found, true
				}
			}
		}
		return nil, false
	}
	return walk(value)
}

// Preserve the useful numeric/boolean account-cap fields without returning
// WhatsApp's full private MEX response through the Android admin surface.
func extractSafetyScalars(raw json.RawMessage) map[string]any {
	var root any
	if json.Unmarshal(raw, &root) != nil {
		return map[string]any{"available": false, "parseError": true}
	}
	result := map[string]any{"available": true}
	var walk func(string, any)
	walk = func(path string, value any) {
		if len(result) >= 50 {
			return
		}
		switch typed := value.(type) {
		case map[string]any:
			for key, nested := range typed {
				next := key
				if path != "" {
					next = path + "." + key
				}
				walk(next, nested)
			}
		case []any:
			for index, nested := range typed {
				walk(fmt.Sprintf("%s[%d]", path, index), nested)
			}
		case bool, float64, string:
			lower := strings.ToLower(path)
			if strings.Contains(lower, "cap") || strings.Contains(lower, "limit") ||
				strings.Contains(lower, "count") || strings.Contains(lower, "remaining") ||
				strings.Contains(lower, "sent") || strings.Contains(lower, "active") ||
				strings.Contains(lower, "enforcement") || strings.Contains(lower, "time") {
				result[path] = typed
			}
		}
	}
	walk("", root)
	return result
}

func boolValue(value any) bool {
	switch typed := value.(type) {
	case bool:
		return typed
	case string:
		return strings.EqualFold(typed, "true") || typed == "1"
	case float64:
		return typed != 0
	default:
		return false
	}
}

func int64Value(value any) int64 {
	switch typed := value.(type) {
	case float64:
		return int64(typed)
	case json.Number:
		parsed, _ := typed.Int64()
		return parsed
	case string:
		var parsed int64
		_, _ = fmt.Sscan(typed, &parsed)
		return parsed
	default:
		return 0
	}
}

func (r *Runtime) publishSafety(kind string, values map[string]any) {
	event := make(map[string]any, len(values)+2)
	event["type"] = "safety"
	event["kind"] = kind
	for key, value := range values {
		event[key] = value
	}
	r.publish(event)
}

func reachoutEventPayload(event *events.NotifyAccountReachoutTimelock) map[string]any {
	result := map[string]any{
		"isActive":        event.IsActive,
		"enforcementType": truncate(event.EnforcementType, 120),
		"receivedAtMs":    time.Now().UnixMilli(),
	}
	if !event.TimeEnforcementEnds.IsZero() {
		result["expiresAtMs"] = event.TimeEnforcementEnds.UnixMilli()
	}
	return result
}
