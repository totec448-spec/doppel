# Performance and background model

The goal is low idle cost with a stable background connection. "Efficient" is
described here as a concrete architecture and a measurement plan, not as an
unevidenced claim of zero CPU and zero battery.

## The idle path on Android

When the bot is started but no chat is active:

- one `remoteMessaging` foreground service is alive;
- one OkHttp WebSocket to the native core stays open (loopback, same process);
- coroutines wait suspended on a StateFlow, a channel, a network callback or a
  socket frame;
- there is no polling loop for messages;
- the CPU and Wi-Fi locks are held only while the power plan says the link should
  really be awake. Low-power doze and quiet hours both release them;
- there is no second Android process;
- Compose is not needed when only the service process is running;
- activity, model and database lists are not loaded periodically;
- OkHttp sends a WebSocket ping roughly every 55 seconds.

When the bot is fully stopped:

- `runRequested=false`;
- the runtime, socket, engine and network callback are closed;
- the foreground notification is removed;
- sticky and boot recovery do not start it again.

A functional pause (`/off` or the app's pause) is not the same as a stop. The
connection and admin control deliberately stay active and keep costing the small
socket and service overhead.

## The idle path in the native core

The core runs inside the same app process and holds:

- one whatsmeow socket to WhatsApp;
- an HTTP/WS server bound exclusively to `127.0.0.1`;
- a 30 second WebSocket liveness timer;
- an hourly media sweep;
- reconnect timers only on failure.

Those timers do no chat or AI work. The journal, idempotency table and media are
bounded. WebSocket compression is disabled. A large resume snapshot is delivered
in full in bounded pages, and `bufferedAmount` backpressure pauses the next
replay page rather than letting memory grow without limit.

The core is therefore small, but not "exactly zero CPU". The Noise-encrypted TCP
connection, pings, WhatsApp protocol traffic and filesystem flushes create real
wakeups and CPU. Because it sits inside the APK, there is no cost from a second
process or a TLS termination hop on top.

## The active message path

### Batching and serialisation

- There is at most one debounce timer per chat.
- A newer message replaces that timer instead of adding more.
- Finished chat turns run through one global worker.
- A different chat waits. The same active chat cancels the old local turn and is
  rescheduled combined.
- Visible normal AI and send paths therefore never run in parallel.

What that buys:

- fewer duplicate model calls when messages arrive quickly;
- more stable WhatsApp presence;
- bounded parallel RAM and media load;
- more human behaviour.

The trade-off: a slow provider call blocks other normal chats. That is
deliberate, and it matches the old "one person, one chat at a time" model.

Admin replies bypass this normal worker, so `/on`, `/off` and `/status` do not
wait behind a long AI turn.

### The proactive scheduler

Proactive uses no interval scan. `DueWorkScheduler` holds exactly one timer for
the earliest persisted due time. Inbound messages or real settings changes write
or revise that due time and rearm the timer. Due chats are claimed with an
expiring lease and put into the same global turn worker. When nothing is due at
all, the scheduler waits suspended and creates no chat or database loop.

A persisted global not-before value allows only one chat per tick and then leaves
3 to 12 minutes of spacing after a send, or 15 to 45 minutes after a deliberate
silence. A backlog of due chats is never worked off as a burst.

Startup reconciliation is bounded to 1,000 proactive states. That avoids unbounded
work, and it also means very large installations would need a paginated recovery
path.

### Prompt and provider

Prompt assembly is bounded by:

- the current batch at 48,000 characters;
- history, through the configurable memory overlap (ten by default), the pointer
  held until the next successful memory write, and the database hard limit;
- memory, optionally through `memory_char_limit`;
- the tool loop at ten rounds by default, with a hard schema maximum of 16;
- verifier retries at `0..5`.

The history read removes the current batch, holds the oldest overlap entry until
the next chat memory revision, and lets the history grow only at the new end.
After a write, the newest `history_limit` entries are re-anchored, and when the
limit is hit exactly the pointer is set immediately. The older prompt sequence
therefore stays byte-identical instead of sliding with every message.

The wire order is: tools, the stable system and persona prefix, persona and
global memory, chat memory, group and chat history, the current inbound message,
and last the clock, mood and sleep tail. Any follow-up after a visible bot output
even freezes that tail and inserts only the already confirmed assistant lines
directly before it. The system prompt therefore stays completely identical and
the second call hits the largest possible shared prefix.

The largest active cost is the remote model call, not Kotlin. `auto_verify=true`,
tools, TTS and media analysis can add provider calls. Visible text, image and
voice outputs create at most four continuation calls, and each one starts only
after a confirmed send and a durable chat log entry. `[no reply]` ends the loop.
There are no speculative calls on pending tool results.

A verified `request_chat_memory_refresh` adds exactly one utility call. After
successful visible sends, a cheap write-free cadence check runs as well. It
derives its thresholds from `history_limit` and `memory_interval`: by default a
fresh chat waits for `10 + 150 = 160` provider messages, and every write after
that waits for 150 new messages. Below the threshold no model call happens at
all. Every third chat memory write across a persona synthesises persona and
global memory from all chat memories. The source is bounded by repository row,
message, character and per-message limits, and the executable constants in
`MemoryRefreshService` and `RepositoryMemoryRefreshStore` are the source of
truth. With no new message it is skipped without a provider call. This raises the
latency of the triggering turn but creates no idle worker.

### Database

SQLite uses:

- WAL;
- foreign keys;
- `synchronous=NORMAL`;
- indexes on time, chat, expiry, settings, due work and outbox;
- paginated reads;
- hard retention;
- maintenance only after writes, and no more often than a bounded interval.

Hard ceilings:

| Area | Limit |
|---|---:|
| Messages total | 100,000 |
| Messages per chat | 5,000 |
| Processed events | 50,000 |
| Scoped settings | 10,000 |
| Media analyses | 2,000 |
| Outbound ledger | 50,000 |
| Activity | 20,000 |
| Active outbox | 50,000 |
| Completed outbox | 10,000 |

Known scaling limits:

- startup recovery can inspect only the newest 1,000 chats;
- some safety and flood queries scan up to 5,000 messages of a chat;
- cross-chat search checks a bounded chat list and currently up to 200 messages
  per target;
- UI activity is event driven, but each update reloads from SQLite.

For the private bot this is built for, those limits are deliberately simpler and
cheaper than a second search or queue database. Very large installations would
need FTS, keyset-paginated chat cursors and aggregated safety counters.

## Media

Media bytes never travel through WebSocket JSON:

- the native core streams into opaque files;
- Android streams the download straight into memory. Incoming media is read
  exactly once, so a decrypted photo or voice note is never written to app
  storage and there is nothing left behind if the process dies mid-turn;
- the download enforces the maximum size and optionally SHA-256, and aborts the
  transfer the moment it would cross the ceiling, so the array cannot outgrow it;
- the result is cached by hash plus model.

Current peak-RAM points:

- `IncomingMediaAnalyzer` holds the downloaded clip as one byte array;
- audio decoding feeds `MediaExtractor` from that array via a `MediaDataSource`
  adapter and collects PCM in a `ByteArrayOutputStream`;
- image, audio and video are assembled as a byte array and base64 for the
  provider request;
- the default analysis ceiling is 20 MiB;
- voice TTS briefly holds PCM plus processed PCM plus OGG.

If normal audio analysis fails, exactly one native HTTPS multipart STT call can
optionally follow. It is on demand, bounded to 20 MiB of input, 1 MiB of response
and 12,000 transcript characters, and creates no idle cost.

None of this is an idle problem, but large media can create noticeable peak RAM
and GC load. `max_video_bytes` should stay conservative. A later optimisation
could stream the provider upload and encoding end to end, if the provider API
allows it.

TTS:

- exactly one speech call;
- one native DSP pass;
- one native OGG/Opus encode;
- no FFmpeg subprocess chain;
- serialised by a mutex;
- Android 10+ required. Only API 28 is fail-closed in the encoder.

Approved images:

- import and upload are streamed with a 16 KiB buffer;
- at most 16 MiB per asset;
- identical bytes are deduplicated per persona;
- listing reads only small metadata files;
- SHA-256 is computed at import and immediately before the send, not on every UI
  refresh;
- there is no image scan or hashing in the idle path.

## Network and reconnect

- A NetworkCallback instead of a periodic connectivity poll.
- Losing validation or a change of default network ends the old session.
- Retry uses full jitter and is capped.
- After a healthy online state the attempt counter is reset.
- Terminal auth and protocol errors wait suspended for a user reconnect or a
  stop, instead of spinning aggressively.
- The native core's reconnect also uses capped jitter.

## What is still a performance risk

- The active database outbox dispatcher is signal driven with no polling. On
  transport failures, full-jitter retries create additional but bounded wakeups,
  and a complete turn is still not one transaction.
- Outbound safety runs as a preflight before the main model and again with the
  payload hash before the send. TTS, media and side effects start only after the
  final reservation, and facts-to-reserve is formally still not atomic.
- Incoming reactions and empty stickers create no unnecessary AI call, and edits
  and deletes only mutate the target history. The old random 30 % reaction
  semantics is deliberately absent.
- Android pushes a fail-closed ingress policy before replay and intake, so
  rejected senders trigger no media download in the core.
- Transient presence, read and played actions bypass the durable idempotency
  store, and the WSS liveness path has a single bounded heartbeat.
- Large incoming message batches run through a bounded serial queue. Backpressure
  waits instead of losing events beyond a slice.
- Memory refresh runs synchronously with the triggering turn or the post-send
  cadence event, with no periodic timer or scan.
- Proactive schedules only the next real due time in source. Restarts, many due
  chats and long device and doze runs still need measurement and integration
  tests.
- The model catalogue is bounded and refreshed only explicitly. That saves idle
  load but can show stale capabilities.
- Default keeps the link reachable with the CPU and Wi-Fi locks it needs.
  Low power drops it between sessions and uses an exact `AlarmManager` wake, so
  the intended reachability and battery mode stays explicit.

## The reality of Android backgrounds

A foreground service lowers the chance of the runtime being killed, but
guarantees no immortality:

- the user can stop or force-stop the app;
- Android or the OEM can restrict the network or the process;
- doze can delay socket traffic;
- boot start can fail because of manufacturer policy;
- a missing notification permission affects visibility and control.

The app therefore persists the cursor, the desired run state and the bot state.
That persistence is a recovery aid, not a substitute for a real 24 to 72 hour
test.

## Measurement plan on the target device

Before any load-bearing claim, debug and the final release have to be measured
separately.

### Idle scenarios

1. Bot stopped, 30 minutes.
2. Bot started and connected, no chats, 2 hours.
3. Bot functionally paused, 2 hours.
4. Screen off plus doze.
5. Wi-Fi to mobile to Wi-Fi.
6. 24 to 72 hours of endurance.

Record:

- process PSS and the Java and native heap;
- CPU time and wakeups;
- network bytes;
- the batterystats difference;
- the number of reconnects;
- duplicate or lost replies;
- growth of the native core's journal.

### Active scenarios

- 1, 10 and 50 fast text messages in the same chat;
- two chats during one slow call;
- a same-chat interrupt;
- an image close to the size limit;
- long audio and video;
- a TTS voice note;
- provider 429, 5xx and timeout;
- an Android process kill inside the action window.

### Example commands

Only on a device you are explicitly authorised to use:

```powershell
$adb = '$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe'
& $adb shell dumpsys meminfo de.totec.doppel
& $adb shell dumpsys batterystats --reset
& $adb shell dumpsys batterystats de.totec.doppel
```

Perfetto and the Android Studio profiler should complement this. They should not
be replaced by subjective impressions of the UI.

## What is actually evidenced right now

- The architecture was checked statically for polling, lock lifetime and a second
  process.
- Full jitter, network handover and the resume decision have JVM tests.
- The full JVM run is green.
- There is no Perfetto, batterystats, PSS, doze or 24 to 72 hour evidence.

So "event driven and bounded" is a fair thing to say right now. "Proven to barely
use battery" is not.
