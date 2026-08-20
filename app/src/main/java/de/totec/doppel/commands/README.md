# Native admin command boundary

This package is a pure Kotlin, event-driven command layer. It starts no worker,
timer, coroutine or polling loop and therefore has no idle CPU cost.

## Single command source

`CommandRegistry` is the only command catalogue. Its 31 canonical names and 71
aliases drive both parsing and `/help`; help text cannot drift into a separate
hand-maintained command list. Names are resolved case-insensitively and the
prefix is supplied at execution time.

## Routing contract

`CommandExecutor.execute` has four routing outcomes:

- ordinary text: `NotACommand`;
- unknown prefixed text: `UnknownCommandFallThrough`;
- a recognized command from a non-admin: `NotAuthorizedFallThrough`;
- an executed admin command: `Replied`.

Both fall-through results leave the original message available to the normal AI
engine. Admin identity must be resolved before calling this package from every
known PN/LID identity. API-key commands additionally require a direct chat.
Admin commands are deliberately evaluated before the bot's functional pause so
`/on`, `/status` and the remaining administration continue to work.

## Shared application port

Chat commands, Compose screens and notification controls call the same
`AdminActions` port with a typed `AdminOrigin`. The adapter owns persistence,
WhatsApp transport and runtime coordination. Important adapter invariants:

- validate every member of `SetSettings` before committing its complete map in
  one transaction;
- an empty `ChangeAccess(REPLACE)` clears that list;
- resolve allow/admin identities across PN and LID forms;
- `PauseBot` suppresses normal/proactive work but keeps the foreground runtime,
  WhatsApp connection and admin path alive;
- `ResetChat(resetGlobalSettings = true)` resets the actual global settings
  scope atomically with the chat reset;
- `Wipe` removes only the documented memory/history/proactive/image-dedup state,
  never definitions, settings or access lists.

`SecretInput` redacts `toString`, lends only an automatically zeroed temporary
buffer and is closed by the executor immediately after the synchronous call.
`SecretStatus` can carry at most a four-character suffix, preventing an adapter
from accidentally returning a complete credential for display.

## Destructive confirmation

`WipeChallengeManager` keeps six-digit challenges in memory only. Every
challenge is bound to the issuing actor, originating chat, immutable target and
expiry. A process restart safely cancels pending wipes.

## WhatsApp output

Long help/settings output is split at readable line boundaries into messages of
at most 3,500 UTF-16 code units, without splitting surrogate pairs. All command
references in native replies use the configured prefix.
