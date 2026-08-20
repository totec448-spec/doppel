# Approved image assets

## Purpose

The bot must never turn model text, a WhatsApp command, a filename, or an
Android document URI into an arbitrary file read. Outgoing AI images therefore
use one explicit allow-list owned by the Android app:

1. The owner selects an image with Android's Storage Access Framework
   (`OpenDocument`).
2. `AndroidApprovedMediaImporter` immediately streams the selected content into
   app-private storage. The external URI is not persisted.
3. `ApprovedMediaAssetStore` validates the byte limit and file signature,
   computes SHA-256, assigns a random opaque ID, and atomically publishes data
   plus metadata.
4. Each asset belongs to exactly one persona.
5. Admin actions and AI tools receive only the opaque ID and display metadata.
6. Immediately before upload, the store revalidates the ID, persona, canonical
   path, length, and SHA-256.
7. The existing `BridgeMediaClient` streams the file to the native core over the
   authenticated loopback endpoint; `BridgeWhatsAppActions.sendMedia` performs
   the durable idempotent WhatsApp action and records the persona/conversation
   marker only after the core returns its completed result. Image bytes never
   enter the WebSocket JSON protocol.

AI sends use the normal engine path. A deliberate manual send from the app
reuses the same private store, uploader, transport mapper, sent history,
outbound-safety ledger, Android outbox, and core-side idempotency. It remains a
separate admin entry point and therefore does not create AI assistant history.

## Kinds

One catalogue holds three kinds, and an asset's kind is checked on every resolve,
so a picture can never be used for a purpose it was not approved for:

- `IMAGE` — sendable in a chat, exposed to the AI tools, subject to the
  once-per-chat repeat ledger. Generated images join this kind under a
  descriptive name, so the corpus grows and repeats get cheaper.
- `CHARACTER_REFERENCE` — bounded private bytes attached to an image-generation
  request. Never sendable, never listed to the model.
- `PROFILE_PICTURE` — the faces the account itself wears. Never sendable, never
  attached to a request; resolved only by `ProfilePictureRotator` immediately
  before the avatar is uploaded, and capped at 12 per persona.

Each kind seeds its bundled starter set through the same
`PreinstalledPersonaImages` pass with its own ledger file, which is what makes
the shipped pictures visible the first time a list is opened — and what makes
deleting one of them stick across restarts.

## Profile pictures

`AndroidProfilePictureImporter` crops a SAF-selected image to its largest
centred square, scales it to 640×640, flattens alpha onto white, and re-encodes
it as a JPEG bounded to 200 KiB. Re-encoding drops every piece of metadata the
original carried, including the capture location.

`ApprovedMediaProfilePictures` lists one persona's pictures oldest first, which
is the order the rotation walks them in. The rotator records the picture it put
up by opaque ID rather than by position, so adding or deleting a picture cannot
make the same face come up twice in a row or silently skip one. Only a change
WhatsApp accepted is recorded as live; deleting the live picture leaves the
account untouched — an avatar change is broadcast to every contact and is never
a side effect of editing a list.

## Private layout

The canonical root is:

`filesDir/approved-media/v1`

It is a flat directory. Data and metadata names are derived only from IDs in
the strict form `img_` plus 32 lowercase hexadecimal characters. Persona keys
must match `[a-z0-9_-]{2,40}`. Canonical-child checks are performed even after
those validations, so a symlink or traversal string cannot escape the private
root.

Original filenames are retained only as bounded, control-character-free
display labels. They never select a path.

## Supported content and limits

- JPEG, PNG, GIF, and WebP;
- content is identified by magic bytes, not only the provider MIME type;
- maximum stored/send size: 16 MiB per asset;
- maximum catalogue size: 250 assets per persona;
- imports are streamed with a 16 KiB buffer;
- identical bytes are deduplicated within the same persona;
- list operations read only small metadata files and do not hash image bytes;
- SHA-256 is recomputed only when an image is about to leave the app.

Approved send attempts create an empty marker under a strict
`sent/<asset-id>/<sha256(chat-id)>` path. Thus `block_repeat_images` is durable
without storing a second copy of the image or a plaintext contact ID. The
marker is published only after the durable core action reports success (or
replays that already completed result). A definite pre-effect failure remains
retryable. The core's `idempotency_in_doubt` outcome stays fail-closed and
can require admin review/reset before another attempt. Chat reset, memory clear,
and persona/all-data wipe clear matching markers; deleting an asset clears its
complete marker directory. This marker is image-repeat protection and
confirmation that the core acted, not proof of final WhatsApp delivery/read.

Incomplete import files and unpublished orphan data are cleaned when the
process graph opens the catalogue.

## Admin app

The **Verwalten → Personas & Stimme → Freigegebene Bilder** area provides:

- a Storage Access Framework picker;
- refresh/list for the selected persona;
- an optional bounded caption;
- explicit send to a supplied WhatsApp chat JID while the bridge runtime is
  connected;
- confirmed permanent deletion.

Imports are app-only because a chat command cannot grant Android document
access. Listing continues to use the shared `PersonaImages` admin action.
Deletion and manual send also use typed admin actions. Manual send is restricted
to `AdminOrigin.APP` and a running bridge. Each explicit tap receives one
request UUID which is reused as the safety reservation and transport
idempotency identity. The send is reserved with `admin=true`: hard locks,
review and holds still apply, while policy-defined soft admin velocity bypasses
remain. It does not create assistant history and must not be described as an AI
turn.

## AI tools

`list_sendable_images` is exposed only when the active persona has at least one
eligible approved asset. With repeat blocking enabled it omits assets already
durably sent to the current conversation. It returns ID, display name, MIME
type, and size, never a path, URI, or content hash.

`send_image` accepts only an ID returned by that catalogue. After reply
verification, `TurnActionCommitter` resolves it for the active persona, uploads
through `BridgeMediaClient`, and emits the normal
`PlannedSideEffect.SendMedia`. The engine's existing outbound reservation,
deduplication, and delivery path remains authoritative. When
`block_repeat_images` is enabled, a prior completed send of that asset by
the same persona to the same conversation rejects the pending action before any
upload. Side-effect IDs include the native turn ID, so provider-local tool IDs
cannot collide across turns.

## Verification

`ApprovedMediaAssetStoreTest` covers:

- persona isolation and app-private canonical location;
- traversal and malformed ID rejection;
- signature/MIME spoof rejection;
- streaming size enforcement and partial-file cleanup;
- per-persona deduplication;
- length and hash tamper detection before send;
- durable persona/conversation sent markers and chat reset;
- wrong-persona delete rejection;
- bounded handling of corrupt metadata and unrelated files;
- kind isolation, including a profile picture that neither `openForSend` nor
  `openForReference` will resolve, and its per-persona cap;
- the profile-picture library's oldest-first order and per-persona path scoping.

`ProfilePictureRotatorTest` additionally covers rotation over an editable
catalogue: a picture added ahead of the live one neither repeats nor skips a
face, deleting the live picture makes the next change start over instead of
failing, an emptied library leaves the account alone, and a deleted live picture
stops being projected rather than being replaced by a guess.

Android picker interaction and real bridge delivery require device/runtime
testing. A JVM unit test does not claim either of those.
