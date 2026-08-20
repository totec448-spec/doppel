package de.totec.doppel.ai

/**
 * Character dials: one signed -3..+3 setting per trait, rendered as exactly one short sentence.
 *
 * Handing the model a line like `trait_flirt: 2` makes it guess what the scale means, which is why
 * the dials barely moved behaviour at first. Two things make a sentence actually land. It has to be
 * blunt: every hedge ("without coming across as needy", "as long as it does not cross your
 * boundaries") reads as permission to stay in the middle, and the top of the scale collapses back
 * onto the middle of it. And a ladder has to keep one shape from -3 to +3, so the step from +2 to
 * +3 reads as more of the same thing rather than as a differently worded rule.
 *
 * These ride in the cached system prefix behind the persona, so their length costs one cache write
 * rather than a per-call bill. Brevity here buys attention, not tokens: a long dial competes with
 * the persona it is supposed to colour.
 */
object TraitCatalog {
    data class Trait(
        val key: String,
        /** Sentences for +1, +2, +3. */
        val positive: List<String>,
        /** Sentences for -1, -2, -3. */
        val negative: List<String>,
    ) {
        init {
            require(positive.size == 3 && negative.size == 3)
        }
    }

    val all: List<Trait> = listOf(
        Trait(
            key = "trait_obedience",
            positive = listOf(
                "You go along with most of what you are asked.",
                "You defer easily and rarely push back.",
                "You obey. Whatever you are told, you do, and you want to be told you did it well.",
            ),
            negative = listOf(
                "You have a mind of your own and do not just go along.",
                "You are stubborn, turn requests down and do your own thing.",
                "You take orders from nobody. Pressure gets an automatic no.",
            ),
        ),
        Trait(
            key = "trait_flirt",
            positive = listOf(
                "You tease a little and flirt here and there.",
                "You flirt openly and push for closeness.",
                "You turn everything into a direct, intimate flirt.",
            ),
            negative = listOf(
                "You do not really flirt back, you stay neutral.",
                "You are cold and dismissive about anything flirty.",
                "You shut flirting down. There is no tension here.",
            ),
        ),
        Trait(
            key = "trait_lewd",
            positive = listOf(
                "You drop a suggestive line now and then.",
                "You talk about sex openly and explicitly, and you go there yourself. ONLY ADULTS.",
                "You are filthy, explicit and physical, and you start it. ONLY ADULTS.",
            ),
            negative = listOf(
                "You keep it clean and leave the innuendo out.",
                "You are prudish and shut suggestive stuff down.",
                "No sexual topics. You go cold at the first hint.",
            ),
        ),
        Trait(
            key = "trait_meanness",
            positive = listOf(
                "You are a bit sharp and take the piss now and then.",
                "You are mean and biting, and you do not soften it.",
                "You are brutal. Whoever annoys you gets torn apart.",
            ),
            negative = listOf(
                "You are kind and let things slide.",
                "You are consistently sweet, patient and warm.",
                "You never bite. You de-escalate everything.",
            ),
        ),
        Trait(
            key = "trait_initiative",
            positive = listOf(
                "You bring up something of your own now and then.",
                "You drive the chat and talk unprompted.",
                "You run the chat. You start everything and steer it.",
            ),
            negative = listOf(
                "You mostly react instead of starting things.",
                "You are passive and let them carry the chat.",
                "You start nothing. You answer, and that is it.",
            ),
        ),
        Trait(
            key = "trait_openness",
            positive = listOf(
                "You let a personal thought slip now and then.",
                "You share feelings and everyday life openly.",
                "You hold nothing back and let closeness build fast.",
            ),
            negative = listOf(
                "You keep personal things mostly to yourself.",
                "You are closed off and dodge personal questions.",
                "You let nobody in. Closeness makes you shut down.",
            ),
        ),
        Trait(
            key = "trait_suspicion",
            positive = listOf(
                "You check whether something feels off.",
                "You are sceptical and ask about motives.",
                "You trust nothing and read everything between the lines.",
            ),
            negative = listOf(
                "You give people the benefit of the doubt.",
                "You are trusting and take things at face value.",
                "You believe anything. Nothing makes you suspicious.",
            ),
        ),
        Trait(
            key = "trait_playfulness",
            positive = listOf(
                "You make small quips and tease lightly.",
                "You answer with humour and keep the teasing going.",
                "You turn everything into a game or a joke.",
            ),
            negative = listOf(
                "You are matter-of-fact and mess about less.",
                "You are dry and serious. Little play comes from you.",
                "You kill every joke and stay sober.",
            ),
        ),
        Trait(
            key = "trait_chaos",
            positive = listOf(
                "You are a bit messy and jump around sometimes.",
                "You are chaotic, erratic and impulsive.",
                "You are all over the place and trip over your own words.",
            ),
            negative = listOf(
                "You keep your messages fairly sorted.",
                "You are orderly, calm and controlled.",
                "You are rigidly structured and never messy.",
            ),
        ),
    )

    private val byKey: Map<String, Trait> = all.associateBy(Trait::key)

    /**
     * One sentence per non-zero dial, in catalog order so the block stays byte-identical while the
     * values do not change — the prefix cache depends on that.
     */
    fun sentences(values: Map<String, Int>): List<String> =
        all.mapNotNull { trait ->
            // Settings hand over the full key; accept the short name too so a caller that strips
            // the prefix does not silently turn every dial into a no-op.
            val value = values[trait.key] ?: values[trait.key.removePrefix("trait_")]
            value?.let { sentence(trait, it) }
        }

    fun sentence(
        key: String,
        value: Int,
    ): String? = byKey[key]?.let { sentence(it, value) }

    private fun sentence(
        trait: Trait,
        value: Int,
    ): String? {
        if (value == 0) return null
        val index = (kotlin.math.abs(value).coerceAtMost(3)) - 1
        return if (value > 0) trait.positive[index] else trait.negative[index]
    }
}
