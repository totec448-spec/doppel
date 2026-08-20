package de.totec.doppel.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The whole palette, as two ramps and a handful of state colours.
 *
 * Every surface in the app sits on the neutral ramp ([Base] … [Layer3]) and every piece of text on
 * the text ramp. Anything outside those two ramps means *state* — running, waiting, broken — and
 * nothing else is allowed to use it. That rule is the point: the previous palette tinted the
 * background, the avatar, the hero card, section labels and the navigation bar all green, so a
 * green status dot carried no information. Now a spot of green on a screen means one thing.
 */

// ── Neutral ramp ─────────────────────────────────────────────────────────────
// Neutral on purpose. The ramp used to lean blue (#0A0B0D under #98A1AB), which read as a tinted
// theme rather than a chosen one and left every white next to it looking faintly warm by contrast.
// A chat is mostly ground, so the ground is the one thing that must not have an opinion.

/** Page canvas. True black: the phone is an OLED and the chat is mostly canvas. */
internal val Base = Color(0xFF000000)

/** Grouped lists, cards, sheets — the one surface content actually sits on. */
internal val Layer1 = Color(0xFF101010)

/** Inputs, chips, pressed rows: raised above a card. */
internal val Layer2 = Color(0xFF1A1A1A)

/** Selected chips and the navigation indicator. */
internal val Layer3 = Color(0xFF262626)

/** Row separators inside a group. Deliberately barely visible. */
internal val Hairline = Color(0xFF1E1E1E)

internal val OutlineSoft = Color(0xFF333333)

// ── Text ramp ────────────────────────────────────────────────────────────────
internal val TextHigh = Color(0xFFFFFFFF)
internal val TextMid = Color(0xFF8C8C8C)
// Still visibly secondary, but no longer below readable contrast on Layer1.
internal val TextLow = Color(0xFF7D7D7D)

// ── Conversation ─────────────────────────────────────────────────────────────
// The one place radius and brightness are spent. Everything else in the app is instrumentation
// and stays flat and dim; a bubble is the only element that is actually someone talking.

/** The bot's own bubble — held just under pure white so a dark room is not stabbed at 3am. */
internal val BubbleBot = Color(0xFFF2F2F2)
internal val BubbleBotInk = Color(0xFF000000)

/** The contact's bubble: contained, dark, clearly the other side of the conversation. */
internal val BubbleContact = Color(0xFF1C1C1C)
internal val BubbleContactInk = Color(0xFFEDEDED)

// ── State ────────────────────────────────────────────────────────────────────
/** Running / connected / on. The single accent, used at most once per screen. */
internal val Live = Color(0xFF19C160)

/** Text and icons drawn on top of [Live]. */
internal val LiveInk = Color(0xFF00220E)

/** Quiet fill behind [Live] text — badges, the "online" panel tint. */
internal val LiveMuted = Color(0xFF0F2A1A)

/** Connecting, backing off, needs attention — and a pickup window that has not fired yet. */
internal val Waiting = Color(0xFFD9A02B)

/** Stopped with an error, destructive actions. */
internal val Broken = Color(0xFFD8442F)
internal val BrokenInk = Color(0xFF1A0603)
internal val BrokenMuted = Color(0xFF2C1512)

/** Neutral informational accent — network and engine rows in the log. */
internal val Info = Color(0xFF6FA8FF)

/**
 * Offline on purpose: the link is sleeping or dozing.
 *
 * Its own colour rather than a grey, because a link that is down on schedule is a state the bot is
 * *in*, not an absence of one — and its own colour rather than [Broken], because a red dot every
 * night would teach the eye to ignore the one that means something.
 */
internal val Asleep = Color(0xFF5B9DFF)

/** Media and voice rows in the log. */
internal val Media = Color(0xFFC792EA)

/** Transport and inbound rows in the log. */
internal val Inbound = Color(0xFF4ED4DC)

// ── Avatars ──────────────────────────────────────────────────────────────────
// The one deliberate exception to "colour means state". An avatar's colour carries no status — it
// is an identity, held at low saturation so that a roster of forty of them still reads as one list
// and never competes with the single green dot that does mean something.

internal val AvatarPalette =
    listOf(
        Color(0xFF3A4A63),
        Color(0xFF5A4257),
        Color(0xFF3F5449),
        Color(0xFF63523A),
        Color(0xFF44405E),
        Color(0xFF2F5158),
        Color(0xFF5C4040),
        Color(0xFF4A5237),
    )

// ── Light scheme ─────────────────────────────────────────────────────────────
// The app runs dark; these exist so a system-light preview is not unreadable.
internal val PaperBase = Color(0xFFF7F8F9)
internal val PaperLayer1 = Color(0xFFFFFFFF)
internal val PaperLayer2 = Color(0xFFEDEFF2)
internal val PaperHairline = Color(0xFFE2E5E9)
internal val PaperTextHigh = Color(0xFF14171A)
internal val PaperTextMid = Color(0xFF5A626B)
internal val LiveDark = Color(0xFF00733F)
