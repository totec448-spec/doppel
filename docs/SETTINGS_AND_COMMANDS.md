# Settings and admin commands

The registry holds exactly 88 bot settings, 24 active installation values, 31
canonical commands and 71 aliases.

Those numbers describe the validated surface. All 88 live bot keys and all 24
installation values have a concrete consumer in the current source. Live provider
and WhatsApp limits, and paths that are deliberately local diagnostics, are
marked as such below.

`BotSettingsSchema.all` is the executable, test-guarded source of keys, defaults
and bounds. This page explains the groups and the values that matter. The schema
test breaks the moment its 88 live entries drift. `utility_model` and
`leave_on_read` are no longer live settings. The main model path also handles
memory refreshes, and old `leave_on_read` import values are recognised and
discarded.

## Scope and the shared execution path

Ordinary bot settings are global. Three exceptions carry targeted overrides:

- persona per chat or contact;
- proactive level per contact;
- pause per chat or contact.

Bridge, credentials, owner and admins, allowlists and network parameters belong
to the installation.

The app and the chat share one path:

```text
Compose or WhatsApp command
  -> typed AdminAction
  -> NativeAdminActions
  -> SettingsRepository / BotRepository / RuntimeBridgeControl
```

`/off` sets `enabled=false` while keeping the service, the connection and the
admin commands alive. A full stop happens only from the app or the notification.

## Editors in the UI

- boolean: switch
- enum, persona, voice: dropdown
- model: one combined search and slug field. The catalogue loads on open, and
  selecting an entry or pressing enter saves immediately
- small numeric range: slider
- large numeric range: exact number field
- prompt and style: multi-line field
- time and time zone: validated text field for now
- secrets and lists: inline disclosure. The OpenRouter key saves automatically

Model lists load on open and show matching suggestions with paging. Role filters
stop the catalogue from offering the same model for every job unchecked. Real
provider compatibility still has to be tested live.

## The 88 bot settings

### Core, models and context

| Key | Type / default | Validation | Current consumer |
|---|---|---|---|
| `enabled` | bool / `true` | — | engine pause |
| `model` | text / `deepseek/deepseek-v4-pro-0813` | not empty | main reply and memory refresh |
| `media_model` | text / `google/gemini-3.7-flash` | not empty | image, audio and video analysis |
| `first_party_provider_only` | bool / `true` | — | pins the vendor's own provider with fallback disabled for reply, verifier, memory and media |
| `personality` | enum / `human` | 15 catalogue values | persona selection |
| `base_prompt` | text / bundled meta prompt | bounded | stable prompt layer |
| `system_prompt` | text / empty | bounded | custom instructions |
| `temperature` | decimal / `0.7` | `0..2` | OpenRouter |
| `max_tokens` | integer / `10000` | `1000..100000` | total budget of an ordinary writing call including reasoning |
| `memory_char_limit` | integer / `0` | `>=0` | prompt memory bound |
| `history_retention` | integer / `1000` | `0..5000` | write-triggered per-chat retention, additionally capped by a hard database ceiling |
| `reasoning_effort` | enum / `high` | `off,none,minimal,low,medium,high,xhigh,max` | OpenRouter |
| `media_reasoning_effort` | enum / `minimal` | `minimal..max` | separate media reasoning. Video restricts compatible values to at least `minimal` |
| `top_p` | decimal / `1` | `0..1` | OpenRouter |
| `frequency_penalty` | decimal / `0` | `-2..2` | OpenRouter |
| `presence_penalty` | decimal / `0` | `-2..2` | OpenRouter |
| `history_limit` | integer / `10` | `0..100` | messages kept verbatim after a chat memory write. 0 keeps everything |
| `memory_interval` | integer / `150` | `10..500` | new messages between memory writes. The first window is `history_limit + memory_interval` |
| `auto_verify` | bool / `true` | — | reply verifier |
| `verify_model` | text / `deepseek/deepseek-v4-flash-0731` | not empty | verifier |
| `verify_max_retries` | integer / `3` | `0..5` | regeneration limit. 3 allows at most four reply attempts |
| `verify_max_tokens` | integer / `2000` | `32..4000` | output budget of one check call including reasoning |
| `verify_reasoning_effort` | enum / `low` | effort values | reasoning of the checking model |
| `verify_fail_closed` | bool / `false` | — | separates a technical check failure from a negative verdict |

### Media and TTS

| Key | Type / default | Current consumer |
|---|---|---|
| `vision_enabled` | bool / `true` | incoming image analysis |
| `stt_enabled` | bool / `true` | incoming audio analysis |
| `video_enabled` | bool / `true` | incoming video analysis |
| `image_analysis_prompt` | text | editable image analysis prompt plus a fixed plain-text contract |
| `video_analysis_prompt` | text | editable video analysis prompt |
| `voice_analysis_prompt` | text | editable voice prompt. `Tone:` and `Transcription:` stay mandatory fields |
| `image_generation_enabled` | bool / `true` | gate for the model tool `generate_image` |
| `image_generation_model` | text / `openai/gpt-image-2` | OpenRouter images API |
| `image_generation_quality` | enum / `very_low` | quality and compression step |
| `image_generation_prefix` | text | stable unpolished phone-gallery style |
| `block_repeat_images` | bool / `true` | a durable asset, persona and chat marker is reserved immediately before transport. Failed or ambiguous attempts stay fail-closed until reset |
| `tts_enabled` | bool / `true` | voice tool gate |
| `tts_model` | text / `google/gemini-3.1-flash-tts-preview` | speech endpoint |
| `tts_voice` | text / `Kore` | default voice |
| `tts_quality` | integer / `6` | `1..10`, DSP and Opus bitrate |
| `tts_style_prompt` | text / casual phone style | speech instruction |

`tts_script_model` is only a legacy import alias, not a live setting. The native
voice note does not first generate a script with a second model.

### Timing, proactive and realism

| Key | Type / default | Current consumer |
|---|---|---|
| `batch_window_ms` | integer / `2000` | chat batching, `0..60000` |
| `reply_preset` | enum / `instant` | `human` or `instant` |
| `typing_speed_wpm` | integer / `70` | duration of the typing signal, `10..200` |
| `reading_speed_wps` | integer / `10` | minimum reading time with a length ramp, `1..60` |
| `typing_stop_delay_ms` | integer / `2000` | natural decay after an interrupt, `0..10000` |
| `proactive_level` | integer / `0` | persisted due scheduler, contact override and outbound policy. `0` disables it |
| `proactive_mode` | enum / `fixed` | `fixed`, or a deterministic daily `hot_cold` walk across levels |
| `cross_chat_search` | bool / `false` | tool gate |
| `enable_reactions` | bool / `true` | outgoing reaction |
| `enable_quote_reply` | bool / `true` | outgoing quote. Context resolution is still partial |
| `mark_read` | bool / `true` | read and played |
| `self_edit_enabled` | bool / `false` | optional best-effort edit after a confirmed original send |
| `self_edit_chance` | decimal / `0.03` | deterministic chance `0..1`. A failed edit can never duplicate the original send |
| `mood_enabled` | bool / `true` | shared mood prompt |
| `profile_picture_enabled` | bool / `true` | profile picture change on persona switch plus periodic rotation. Pictures come from the persona's profile catalogue (Settings, Persona, Profile pictures) |
| `profile_picture_interval_days` | integer / `30` | rotation interval `1..180` days, editable in the same panel as the pictures |
| `error_reports` | bool / `true` | redacted turn error in the local activity log. No unchecked error text goes to WhatsApp |
| `sleep_start` | time / `00:30` | human pickup |
| `sleep_end` | time / `08:30` | human pickup |
| `timezone` | IANA / `Europe/Berlin` | mood and timing |
| `power_mode` | enum / `default` | `default` keeps the link reachable, `low` drops it between sessions. Presence and human timing stay separate |
| `low_listen_minutes` | integer / `10` | low-power listening window `2..30`, internally jittered by ±20 % |

### The nine traits

All are integers `-3..3`, default `0`, and are folded into the persona prompt:

`trait_obedience`, `trait_flirt`, `trait_lewd`, `trait_meanness`,
`trait_initiative`, `trait_openness`, `trait_suspicion`,
`trait_playfulness`, `trait_chaos`.

### Safety and blocking

| Key | Type / default | Current consumer |
|---|---|---|
| `max_sends_per_hour` | integer / `25` | OutboundPolicy |
| `max_sends_per_day` | integer / `120` | OutboundPolicy |
| `proactive_daily_cap` | integer / `2` | proactive policy |
| `proactive_reply_ratio_max` | decimal / `0.1` | proactive policy |
| `cold_monthly_cap` | integer / `30` | proactive policy |
| `autoblock_enabled` | bool / `true` | flood and rate windows |
| `autoblock_per_min` | integer / `10` | flood window, 0 disables |
| `autoblock_per_5min` | integer / `30` | flood window, 0 disables |
| `autoblock_per_10min` | integer / `50` | flood window, 0 disables |
| `block_tool_enabled` | bool / `true` | model block tool |
| `ai_disclosure_enabled` | bool / `true` | one-off AI notice before the first generated reply in a chat |
| `ai_disclosure_text` | text / bundled notice | the exact wording of that notice |

The disclosure is the one setting that works against every realism feature
rather than with it. When it is on, a chat receives a single message saying it
is talking to an AI before the first generated reply ever reaches it, sent as
its own message and never merged into an answer. The send is claimed through the
`ai_disclosure_sent_at` column, so a crash between sending and recording cannot
produce a second notice. Turning it off makes undisclosed automated messaging
the operator's own responsibility, and in several jurisdictions that is a legal
question rather than a stylistic one.

The policy runs once as a preflight before read, presence and the model, then
again with the real text hash immediately before the reservation. Locks, holds
and budgets that are already known therefore save the main call. Visible side
effects stay inert until after the protocol check, the verifier and the final
reservation, and TTS and media materialisation only start after that. Reading
facts and reserving are still not one single SQLite transaction.

## The 24 active installation values

| Group | Keys | Status |
|---|---|---|
| Connection | `autostart`, `bridge_token_ref` | active. The token only protects the loopback port of the bundled core |
| OpenRouter | `openrouter_base_url`, `openrouter_referer`, `openrouter_title`, `openrouter_timeout_ms`, `openrouter_api_key_ref` | active |
| Access | `owner_numbers`, `admin_numbers`, `allowlist_numbers`, `group_allowlist`, `allow_all`, `command_prefix`, `group_trigger` | active. The trigger is stripped before command or model text |
| Intake | `rate_limit_max`, `rate_limit_window_ms` | active soft per-chat drop window. It never causes a permanent block on its own |
| Legacy STT | `stt_fallback_url`, `stt_fallback_model`, `stt_api_key_ref` | optional native HTTPS multipart fallback, used only after normal audio analysis has failed |
| Media | `max_video_bytes` | active |
| Account protection | `warmup_enabled`, `warmup_days`, `recovery_warmup_days` | warm-up scales budgets and the global send spacing. Cross-chat has only its own explicit bot setting |
| Privacy | `hide_sensitive_data` | display only. Phone numbers on screen keep their dialling prefix and lose the rest. Nothing stored, logged or sent changes, and a name never stands in for a masked number |

`bridge_url`, `bridge_mode`, `connection_mode` and `transport_mode` survive only
as migration constants and are discarded on import. They do not appear in the
live schema, because the transport runs exclusively inside the APK as a native
linked-device core. There is no notification-only mode, no off-device mode and no
FCM or adaptive mode any more.

Secrets are never stored as raw values in settings or SQLite. Live settings
accept fixed secret references only. The values themselves sit in private shared
preferences, encrypted with AES-GCM under an Android keystore key.

## The 31 admin commands

The configured prefix is shown as `/` below. Names are case-insensitive, and
prompt or reason arguments are preserved exactly.

| Command | Aliases | What it actually does |
|---|---|---|
| `/help` | `tutorial,start,anleitung,h,commands` | help generated from the registry |
| `/status` | `stat,state,info` | compact runtime, bot, model and persona status |
| `/mood [on\|off]` | `stimmung` | status and toggle |
| `/meta` | `metabot,behavior,verhalten` | compact database and settings counts, not a full explanation of behaviour |
| `/allowlist` | `allowed` | list, add, remove, set |
| `/groupallowlist` | `groupallowedlist,groupsallowed,grouplist` | list, add, remove, set |
| `/apikey` | `key,orkey` | status, set, clear. Admin DM only. The next provider call uses the new keystore value without a restart |
| `/reset [all]` | `clear,neu` | delete chat messages and proactive state. `all` also resets global bot settings |
| `/clearmemory` | `clear_memory,forgetme,forget` | delete chat messages, chat memory and proactive state |
| `/clearall` | `wipe,clearallmemory,wipeall` | persona or full wipe behind a six-digit code bound to actor, chat, target and expiry |
| `/settings` | `config,cfg` | every live value |
| `/set <key> <value>` | — | typed global update |
| `/get <key>` | — | value, default and override |
| `/model [slug]` | — | main model |
| `/media_model [slug]` | `mediamodel,visionmodel,audiomodel` | media model |
| `/history [n\|all]` | `hist,history_limit` | `history_limit` |
| `/system [text]` | `sys,prompt` | custom system text |
| `/personality [...]` | `personalities,vibe,char` | global persona or custom |
| `/off` | `aus,pause,stop,mute` | functional pause |
| `/on` | `an,resume,unmute` | functional resume |
| `/crosschat [on\|off]` | `crosschatsearch,chatsuche,datenschutz,privacy` | tool gate |
| `/voice ...` | `tts,voicenote,sprachnachricht` | toggle, quality and global voice |
| `/traits ...` | `stats,trait,charakter` | list, get, set, reset across the nine values |
| `/persona ...` | `personas,personae` | list, create, assign, unassign, images, delete. Import itself stays app-only, because only the app has SAF access |
| `/block [number reason]` | `blockieren` | a server block confirmed by WhatsApp first, then a local deny. Admins and owners are protected |
| `/unblock <number>` | `entblocken,deblock` | an unblock confirmed by WhatsApp first, then local release |
| `/autoblock ...` | `flood,antispam` | toggle, tool, and the three limits written atomically |
| `/blocktool [on\|off]` | `blockmodel,modelblock` | model tool gate |
| `/proactive ...` | `proaktiv,proactivity` | global level and contact override. Changes rearm the persisted single-due scheduler |
| `/reachout` | `timelock,reachoutlock,463` | read-only WhatsApp recheck of the reachout timelock and new-chat cap, with a 30 s single-flight cache |
| `/safety ...` | `safe,locks` | status, events, hold, ack, clear. `refresh` runs the same bounded WhatsApp recheck |

## Corrections against the older source

- The registry and the help text come from the same source.
- `/apikey set <OpenRouter key>` is a live admin command. The key appears neither
  in the reply nor in the log.
- The prefix is not hard-wired to `/`.
- Proactive accepts concrete integers `0..10` only.
- Auto-block limits are written as one validated batch.
- An explicitly empty allowlist can be saved on purpose.
- Invalid persona targets no longer fall back silently to the current chat.
- Wipe codes are bound to admin, chat, operation, target and expiry.
- Tool side effects stay pending until after the protocol check and the verifier.
- `tts_script_model` is not reported as working.
- Bridge and API secrets are not held in plain text in the bot database.
- The old claim that every setting is per chat was removed. The native surface is
  global, with the three kinds of override named above.

## Remaining limits of the semantics

- Reset and wipe work partly on the real chat ID for messages, proactive state
  and image markers, and are not guaranteed to be fine grained enough for several
  persona threads inside one chat.
- Reachout and the new-chat cap are read-only and account dependent. A passing
  source or fake test is not proof for every WhatsApp account.
- Cross-chat tool results are persona filtered and use opaque IDs salted per
  process. After a process restart, old model IDs are deliberately unusable.
- `error_reports` writes redacted entries to the activity log. A separate
  WhatsApp alert to the owner has no live evidence behind it.
