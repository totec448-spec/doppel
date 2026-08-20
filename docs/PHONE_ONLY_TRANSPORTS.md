# Phone-only WhatsApp transport decision

Date: 2026-07-30, revised 2026-08-12

## Outcome

The only transport is a native linked-device core compiled into the APK. The
off-device companion was removed on 2026-08-06 and the notification-listener
fallback on 2026-08-12, both for the same reason: the app's whole premise is
that it runs on the phone, and a transport nobody selected still cost a second
WhatsApp implementation, a second credential path and — for the companion — an
outward-facing socket. The feature matrix below is why the fallback was never
selected; it is kept as the record of what a notification-only build could and
could not have done.

Shipping a JavaScript WhatsApp library inside the APK was rejected even earlier.
A second JavaScript runtime adds memory, startup, packaging and background
lifecycle cost without making WhatsApp more native, and the published
nodejs-mobile binaries lag the Node version those libraries require.

Primary references:

- [nodejs-mobile releases](https://github.com/nodejs-mobile/nodejs-mobile/releases)
- [whatsmeow source and API](https://github.com/tulir/whatsmeow)
- [Official Go mobile AAR guide](https://go.dev/wiki/Mobile)
- [WACI on-device AAR implementation, PR 28](https://github.com/avikalpg/whatsapp-ai-filter/pull/28)
- [WACI rebuilt AAR and history fixes, PR 39](https://github.com/avikalpg/whatsapp-ai-filter/pull/39)
- [Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService.html)
- [Android notification semantic actions](https://developer.android.com/reference/android/app/Notification.Action)
- [Android background execution limits](https://developer.android.com/about/versions/oreo/background.html)

## Feature matrix (historical)

| Capability | Native linked device | Notification only (removed) |
|---|---:|---:|
| Runs with no PC/server | Yes | Yes |
| Receives text | Yes | Only notified messages |
| Sends text/replies | Yes | Only while quick-reply action exists |
| Receives/sends media | Yes | No bot media send |
| Sends PTT voice notes | Yes | No |
| Read/played receipts | Yes | Mark-read action when exposed |
| Available/typing/recording presence | Yes | No |
| Reactions/edit/delete | Yes | No |
| Block/unblock | Yes, as a real blocklist update | No |
| History/replay after process restart | Persistent local journal | Notification replay only while retained |
| Idle cost | Persistent linked-device socket + FGS | Notification callbacks + bot FGS |

## Native architecture

`native-wa/` is a Go package bound into `app/libs/nativewa.aar` with
`gomobile bind`. It uses a pinned whatsmeow commit and exposes only:

- `Start(dbPath, mediaDir, token, port)`
- `Runtime.BaseURL()`
- `Runtime.Stop()`

The core binds only `127.0.0.1:18787`, checks a 256-bit bearer token, and
implements the existing versioned bridge protocol. Kotlin therefore reuses the
same bot engine, durable outbox, message normalization, media analysis,
rate-limits, idempotency keys, setup, and administrative paths — none of which
were ever transport-specific.

The linked-device database, replay journal, idempotent action results, and
downloaded media live below Android app-private storage. Media is capped at
64 MiB, uses safe generated IDs, is SHA-256 recorded, and is cleaned after 48
hours. No native transport port is reachable from Wi-Fi or mobile data.

The foreground service remains intentional. Android background limits make an
invisible, permanently connected process unreliable; the visible
`remoteMessaging` foreground service provides honest lifecycle semantics. The
implementation does not poll and does not hold a wake lock.

## Notification-only architecture and limits (removed 2026-08-12)

The code described here no longer exists. `WhatsAppNotificationListenerService`,
`NotificationBridgeTransport`, `TransportMode` and the notification-listener
manifest entry were deleted after the mode had gone untested through the entire
life of the build; the limits below are the reason nobody ever switched to it.

It accepted only `com.whatsapp` and `com.whatsapp.w4b`, ignored group-summary
notifications, deduplicated content, and converted individual MessagingStyle
bundles into the normal bot event pipeline. It stored the exact `PendingIntent`
actions supplied by WhatsApp:

- a `RemoteInput` quick reply for text output;
- `SEMANTIC_ACTION_MARK_AS_READ` for a read signal.

Those actions expired when WhatsApp replaced or dismissed the notification.
They were never an API guarantee. There was no typing signal, protocol receipt,
voice-note upload, full chat history, or reliable phone-number/JID identity in
this mode, and synthetic JIDs meant access control effectively had to be opened
up — which is the point at which the mode stopped being worth keeping.

## Security and account risk

A linked-device core is an unofficial WhatsApp client. It avoids official
Business API charges, but it does not remove WhatsApp's ability to
change the protocol, log a linked device out, rate-limit automated behavior, or
restrict an account. Start with conservative outbound limits and a non-critical
account.

The dependency `go.mau.fi/whatsmeow` is MPL-2.0 licensed. Its unmodified source
is linked in `THIRD_PARTY_NOTICES.md`; this repository includes all original
native integration source under `native-wa/`. Distribution must retain notices
and comply with the dependency's license.
