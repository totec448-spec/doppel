# Privacy

Doppel is a standalone Android application. It has no application
backend, analytics SDK, advertising SDK, telemetry account, or companion server.

## Data kept on the phone

The app stores linked-device credentials, chat transcripts, contact and group
identifiers, personas, settings, memories, delivery state, safety state, and
bounded technical activity records in app-private storage. OpenRouter and other
configured provider keys are encrypted with Android Keystore-backed AES-GCM.
Android cloud backup and device-transfer backup are disabled for all app data.

The operator can inspect and delete conversation, persona, memory, media, or all
app-owned data through the in-app controls. Uninstalling the app removes its
private Android data, but the linked device should also be removed in WhatsApp
when the installation is intentionally retired.

## Data sent off the phone

- The embedded linked-device client exchanges WhatsApp protocol traffic with
  WhatsApp to receive and send the operator-authorized messages and media.
- The app sends the configured prompt context and selected media analysis input
  to OpenRouter and the chosen model provider when an AI feature runs.
- If you configure a separate transcription endpoint, the raw audio of an
  incoming voice note is uploaded directly to that endpoint over HTTPS,
  authenticated with the separate transcription credential. This route does not
  pass through OpenRouter, and the endpoint operator's retention terms apply to
  the voice recording. Leaving the endpoint unset keeps transcription on the
  OpenRouter path.
- It makes no periodic contact/profile/chat polling for cosmetic UI state.

Provider and WhatsApp processing is governed by their respective terms and
privacy policies. This app cannot guarantee account acceptance or prevent remote
services from retaining traffic under their own policies.

## The people on the other end

Everyone in this document so far is the operator. The other party to a
conversation is not, and two things are true about them that they did not agree
to individually.

Their messages leave the phone. Whatever a contact writes, and the voice notes,
images and video they send, become prompt context or analysis input for
OpenRouter and the selected provider under the terms in the section above. They
are not asked, and the app has no way to ask them.

They are told they are talking to an AI. Before the first generated reply in a
chat, that chat receives one message saying so — sent on its own, once per chat,
recorded in the database so that a crash cannot repeat it. This is on by
default and is the one behaviour in the app that works against the realism
machinery rather than with it. `ai_disclosure_enabled` turns it off and
`ai_disclosure_text` changes the wording; both are the operator's choice and
both are the operator's responsibility. Undisclosed automated messaging is
regulated in some jurisdictions, and switching the notice off does not move that
obligation onto this project.

## Clipboard and local sharing

The pairing screen copies only the short pairing-code characters to the normal
Android clipboard at the operator's explicit tap. Android or keyboard software
may expose clipboard history according to device settings. Diagnostics, exports,
and approved media leave app-private storage only through an explicit operator
action.

## Logs and repository policy

Normal structured activity records avoid message bodies, prompts, responses,
API keys, raw exception messages, and contact-level block-list values. They can
retain app-private chat identifiers and bounded technical metadata needed to
associate an event with its chat. Native warnings apply best-effort redaction to
common JIDs, phone-like values, credentials, pairing codes, and personal paths
before logcat. Logcat, exports, and diagnostics must still be treated as private
data. Runtime databases, chat exports, screenshots, logs, keys, keystores, and
personal build paths are forbidden from source control by the release-hygiene
gate.
