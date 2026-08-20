package de.totec.doppel.commands

import java.util.Locale
import de.totec.doppel.settings.parseBooleanInput

internal fun parseOnOff(value: String?): Boolean? = value?.let(::parseBooleanInput)

internal fun remainderAfterFirstToken(raw: String): String {
    val trimmed = raw.trimStart()
    val splitAt = trimmed.indexOfFirst(Char::isWhitespace)
    return if (splitAt < 0) "" else trimmed.substring(splitAt).trimStart()
}

internal fun normalizePhoneNumber(raw: String): String? {
    val phonePart = raw.substringBefore('@').substringBefore(':').trim()
    if (!PHONE_INPUT.matches(phonePart)) return null
    val digits = phonePart.filter(Char::isDigit)
    return digits.takeIf { it.length in 7..15 }
}

internal fun parsePhoneEntries(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val explicitParts = raw.split(Regex("[,;\\n]+")).map(String::trim).filter(String::isNotBlank)
    val whitespaceParts = raw.trim().split(Regex("\\s+"))
    val parts = when {
        explicitParts.size > 1 -> explicitParts
        whitespaceParts.size > 1 && whitespaceParts.all { normalizePhoneNumber(it) != null } -> {
            whitespaceParts
        }
        else -> listOf(raw)
    }
    return parts.mapNotNull(::normalizePhoneNumber).distinct()
}

internal fun parseGroupEntries(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw
        .split(Regex("[,;\\n]+"))
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
}

internal data class ParsedPhoneAndReason(
    val number: String,
    val reason: String,
)

internal fun parseLeadingPhoneAndReason(raw: String): ParsedPhoneAndReason? {
    val text = raw.trimStart()
    val match = Regex("""^[+(]?\d[\d\s().+/\-]*""").find(text) ?: return null
    val number = normalizePhoneNumber(match.value) ?: return null
    return ParsedPhoneAndReason(
        number = number,
        reason = text.substring(match.range.last + 1).trim(),
    )
}

/**
 * A supplied target never silently falls back to the current chat. Raw WhatsApp
 * JIDs remain intact; a phone number becomes its canonical PN JID.
 */
internal fun normalizeExplicitChatTarget(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if ('@' in value) {
        return value.takeIf {
            Regex("""^[A-Za-z0-9_.:+-]+@(s\.whatsapp\.net|lid|g\.us)$""").matches(it)
        }
    }
    return normalizePhoneNumber(value)?.let { "$it@s.whatsapp.net" }
}

internal fun parseDurationMs(raw: String?): Long? {
    val match = raw?.trim()?.lowercase(Locale.ROOT)
        ?.let { Regex("""^(\d+(?:\.\d+)?)(s|sec|m|min|h|hr|d)?$""").matchEntire(it) }
        ?: return null
    val amount = match.groupValues[1].toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
    val multiplier = when (match.groupValues[2]) {
        "s", "sec" -> 1_000.0
        "h", "hr" -> 60.0 * 60.0 * 1_000.0
        "d" -> 24.0 * 60.0 * 60.0 * 1_000.0
        else -> 60.0 * 1_000.0
    }
    val millis = amount * multiplier
    return millis.takeIf { it.isFinite() && it <= Long.MAX_VALUE.toDouble() }?.toLong()
}

internal data class TraitCommandDef(
    val key: String,
    val displayName: String,
    val aliases: Set<String>,
)

internal val traitCommandDefs: List<TraitCommandDef> = listOf(
    TraitCommandDef(
        "trait_obedience",
        "Gehorsam",
        setOf("obedience", "gehorsam", "gefuegig", "fügig", "unterordnung", "obedient"),
    ),
    TraitCommandDef(
        "trait_flirt",
        "Flirt",
        setOf("flirt", "flirty", "flirten", "flirtend"),
    ),
    TraitCommandDef(
        "trait_lewd",
        "Anzüglichkeit",
        setOf("lewd", "anzueglich", "anzüglich", "anzueglichkeit", "anzüglichkeit", "nsfw", "sexuell"),
    ),
    TraitCommandDef(
        "trait_meanness",
        "Schärfe",
        setOf("meanness", "schaerfe", "schärfe", "fies", "gemein", "mean"),
    ),
    TraitCommandDef(
        "trait_initiative",
        "Eigeninitiative",
        setOf("initiative", "eigeninitiative", "antrieb", "drive"),
    ),
    TraitCommandDef(
        "trait_openness",
        "Offenheit",
        setOf("openness", "offenheit", "offen", "verletzlichkeit"),
    ),
    TraitCommandDef(
        "trait_suspicion",
        "Misstrauen",
        setOf("suspicion", "misstrauen", "skepsis", "skeptisch", "vorsicht"),
    ),
    TraitCommandDef(
        "trait_playfulness",
        "Spieltrieb",
        setOf("playfulness", "spieltrieb", "verspielt", "humor", "spielerisch"),
    ),
    TraitCommandDef(
        "trait_chaos",
        "Chaos",
        setOf("chaos", "chaotisch", "unordnung", "messy"),
    ),
)

internal fun findTraitCommand(value: String): TraitCommandDef? {
    val normalized = value.lowercase(Locale.ROOT)
    return traitCommandDefs.firstOrNull {
        normalized == it.key ||
            it.key == "trait_$normalized" ||
            normalized in it.aliases
    }
}

private val PHONE_INPUT = Regex("""^[+()0-9\s./-]+$""")
