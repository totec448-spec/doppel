# Native WhatsApp safety

## Server-side contact blocks

`block_contact` is deliberately argumentless. The engine binds the action to
the verified sender of the current direct-message turn, rejects group turns and
protected admins, and carries the turn's bounded PN/LID alias set to the native
transport. The native side completes missing aliases from whatsmeow's device
store or a user-sync lookup, then writes WhatsApp's blocklist-v2 item with the
LID as `jid` and the phone identity as `pn_jid`. It reads `GetBlocklist` back;
the tool commit is successful only if the requested identity is present in
WhatsApp's returned blocklist. The local deny entry is written only after that
confirmation.

Unblock uses the same resolved LID without `pn_jid`, matching WhatsApp's
asymmetric blocklist-v2 contract. A 400 identity rejection is exposed as the
privacy-safe permanent code `invalid_block_target`, so the durable outbox does
not retry an unresolvable identity repeatedly.

Manual block/unblock uses the same durable transport action. A transport error
is returned as an admin failure instead of being displayed as a confirmed
WhatsApp block. WhatsApp blocklist events and safety refreshes synchronize
confirmed JIDs into the local block view under the label held in
`REMOTE_BLOCK_LABEL`. That constant is also the match key for rows already
persisted, so changing its text needs a migration rather than an edit.

## Account signals and decisions

The embedded whatsmeow bridge emits structured safety events for:

- reachout timelock activation, type and absolute expiry;
- new-chat message-cap fields and the normalized `limited` decision;
- temporary-ban reason, duration and absolute expiry;
- stream/connect failures including numeric 401/4xx status where available;
- 463 timelock, 419/429 capping, ambiguous send timeouts and unknown 4xx restrictions;
- full confirmed blocklist count and JIDs.

Android persists the latest structured snapshot, logs every transport event and
routes it into the existing outbound policy. Reachout and temporary-ban locks
are global and expire automatically when WhatsApp supplies an expiry. Message
capping is scoped to new/cold proactive chats, so it does not incorrectly lock
ordinary replies. Unknown restrictions and hard connection failures require an
admin review. Ambiguous sends create a ten-minute hold.

The native runtime refreshes the server snapshot on a connected session and
approximately every 45 minutes while the foreground runtime is already alive.
It does not schedule a separate Android wake-up. `/safety refresh` and
`/reachout` request the same refresh manually, and opening the safety panel in
the app runs it as a single-flight recheck. All three report block count,
reachout state, expiry and type, cap numbers, and any check that the pinned
WhatsApp protocol could not retrieve.

## Current-chat model tools

`scroll_current_chat` has no arguments. Its internal cursor is scoped to the AI
turn, and every repeated call returns the next 20 older persona-owned messages.
The model never sees or has to copy a database cursor.

`search_current_chat` accepts exactly one `query` of at most 100 characters.
It scans the bounded current-chat history and returns up to ten matches with two
nearby messages on either side, plus explicit match/context counts. The tool
registry is the source for OpenRouter's JSON schema; the prompt library and the
conservative bracket fallback use the same zero/one-argument syntax.

Both are ordinary provider tool turns: the assistant tool-call message is
followed immediately by a `tool` role message carrying the same call ID. The
result JSON names its `resultType`, includes a short explanation and then the
messages, so the next model call sees exactly what was searched or scrolled.

## Verification boundary

Unit tests cover native capability parity, bounded alias propagation, the exact
LID/`pn_jid` block query, the asymmetric unblock query, WhatsApp status parsing,
reachout parsing, cap-number filtering and restriction classification. A green
build proves source and packaged-AAR compatibility. The 2026-08-03 phone gate
also proved real propagation: the app logged `blocklist_update`, the durable
action received server confirmation, and WhatsApp displayed the contact as
blocked.
