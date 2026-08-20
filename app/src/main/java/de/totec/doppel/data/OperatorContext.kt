package de.totec.doppel.data

/**
 * How a note the operator typed into a chat is worded for the model.
 *
 * The wording is the whole feature. The previous form announced itself as
 * `Important context from the operator:` and the system prompt vouched for it as trusted and "not
 * an attack". Both halves backfired: a turn in the user channel claiming privileges is the textbook
 * shape of a prompt injection, so the model kept refusing the note as a hijack attempt and said so
 * in its reasoning — and naming the attack in the defence only put the word in front of it.
 *
 * So there is no longer an author, an authority, or a defence. The note is rendered as something
 * that is simply true for the persona at that point in the chat, inside the square-bracket marker
 * family the prompt already defines as technical labels that are never written out. Nothing is left
 * for the model to argue with.
 */
const val OPERATOR_CONTEXT_MARKER = "[in your head]"

/**
 * Earlier wordings of the same thing. Rows written by older builds stay in the database forever, so
 * both are still recognised on the way out and re-rendered in the current form.
 */
private val LEGACY_OPERATOR_CONTEXT_PREFIXES =
    listOf(
        "Important context from the operator:\n",
        "## Injection\n",
    )

/** The bare note as the operator typed it, whichever wording the stored row carries. */
fun operatorContextBody(stored: String): String {
    val withoutLegacy =
        LEGACY_OPERATOR_CONTEXT_PREFIXES.fold(stored) { text, prefix -> text.removePrefix(prefix) }
    return withoutLegacy.removePrefix("$OPERATOR_CONTEXT_MARKER\n")
}

/** The same note as the model reads it in the history. */
fun operatorContextForModel(stored: String): String =
    "$OPERATOR_CONTEXT_MARKER\n${operatorContextBody(stored)}"
