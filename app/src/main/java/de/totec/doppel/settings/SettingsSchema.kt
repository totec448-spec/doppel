package de.totec.doppel.settings

import de.totec.doppel.engine.ConversationMemoryPolicy
import de.totec.doppel.engine.DEFAULT_AI_DISCLOSURE_TEXT
import de.totec.doppel.engine.HumanTimingPolicy
import de.totec.doppel.engine.LinkPowerPolicy

/**
 * Stable keys shared by persistence, engine, commands, and UI.
 *
 * `tts_script_model` is intentionally absent: the source bridge stopped making
 * the wasteful second TTS scripting call. Import code recognises that legacy key
 * without exposing it as a live setting.
 */
object BotSettingKeys {
    const val ENABLED = "enabled"
    const val MODEL = "model"
    const val MEDIA_MODEL = "media_model"
    const val MEDIA_REASONING_EFFORT = "media_reasoning_effort"
    const val IMAGE_ANALYSIS_PROMPT = "image_analysis_prompt"
    const val VIDEO_ANALYSIS_PROMPT = "video_analysis_prompt"
    const val VOICE_ANALYSIS_PROMPT = "voice_analysis_prompt"
    const val IMAGE_GENERATION_ENABLED = "image_generation_enabled"
    const val IMAGE_GENERATION_MODEL = "image_generation_model"
    const val IMAGE_GENERATION_QUALITY = "image_generation_quality"
    const val IMAGE_GENERATION_PREFIX = "image_generation_prefix"
    const val FIRST_PARTY_PROVIDER_ONLY = "first_party_provider_only"
    const val VISION_ENABLED = "vision_enabled"
    const val STT_ENABLED = "stt_enabled"
    const val VIDEO_ENABLED = "video_enabled"
    const val PERSONALITY = "personality"
    const val BASE_PROMPT = "base_prompt"
    const val SYSTEM_PROMPT = "system_prompt"
    const val TEMPERATURE = "temperature"
    const val MAX_TOKENS = "max_tokens"
    const val MEMORY_CHAR_LIMIT = "memory_char_limit"
    const val HISTORY_RETENTION = "history_retention"
    const val REASONING_EFFORT = "reasoning_effort"
    const val TOP_P = "top_p"
    const val FREQUENCY_PENALTY = "frequency_penalty"
    const val PRESENCE_PENALTY = "presence_penalty"
    const val HISTORY_LIMIT = "history_limit"
    const val MEMORY_INTERVAL = "memory_interval"
    const val MEMORY_CHAT_ENABLED = "memory_chat_enabled"
    const val MEMORY_GLOBAL_ENABLED = "memory_global_enabled"
    const val MEMORY_GLOBAL_INTERVAL = "memory_global_interval"
    const val HISTORY_TIMESTAMPS = "history_timestamps"
    const val BATCH_WINDOW_MS = "batch_window_ms"
    const val REPLY_PRESET = "reply_preset"
    const val TYPING_SPEED_WPM = "typing_speed_wpm"
    const val READING_SPEED_WPS = "reading_speed_wps"
    const val TYPING_STOP_DELAY_MS = "typing_stop_delay_ms"
    const val PROACTIVE_LEVEL = "proactive_level"
    const val PROACTIVE_MODE = "proactive_mode"
    const val AUTO_VERIFY = "auto_verify"
    const val VERIFY_MODEL = "verify_model"
    const val VERIFY_MAX_RETRIES = "verify_max_retries"
    const val VERIFY_MAX_TOKENS = "verify_max_tokens"
    const val VERIFY_REASONING_EFFORT = "verify_reasoning_effort"
    const val VERIFY_FAIL_CLOSED = "verify_fail_closed"
    const val BLOCK_REPEAT_IMAGES = "block_repeat_images"
    const val CROSS_CHAT_SEARCH = "cross_chat_search"
    const val ENABLE_REACTIONS = "enable_reactions"
    const val ENABLE_QUOTE_REPLY = "enable_quote_reply"
    const val AI_DISCLOSURE_ENABLED = "ai_disclosure_enabled"
    const val AI_DISCLOSURE_TEXT = "ai_disclosure_text"
    const val MARK_READ = "mark_read"
    const val SELF_EDIT_ENABLED = "self_edit_enabled"
    const val SELF_EDIT_CHANCE = "self_edit_chance"
    const val SELF_EDIT_DELAY_DIVISOR = "self_edit_delay_divisor"
    const val MOOD_ENABLED = "mood_enabled"
    const val PROFILE_PICTURE_ENABLED = "profile_picture_enabled"
    const val PROFILE_PICTURE_INTERVAL_DAYS = "profile_picture_interval_days"
    const val ERROR_REPORTS = "error_reports"
    const val SLEEP_START = "sleep_start"
    const val SLEEP_END = "sleep_end"
    const val POWER_MODE = "power_mode"
    const val LOW_LISTEN_MINUTES = "low_listen_minutes"
    const val TIMEZONE = "timezone"
    const val TTS_ENABLED = "tts_enabled"
    const val TTS_MODEL = "tts_model"
    const val TTS_VOICE = "tts_voice"
    const val TTS_QUALITY = "tts_quality"
    const val TTS_STYLE_PROMPT = "tts_style_prompt"
    const val TRAIT_OBEDIENCE = "trait_obedience"
    const val TRAIT_FLIRT = "trait_flirt"
    const val TRAIT_LEWD = "trait_lewd"
    const val TRAIT_MEANNESS = "trait_meanness"
    const val TRAIT_INITIATIVE = "trait_initiative"
    const val TRAIT_OPENNESS = "trait_openness"
    const val TRAIT_SUSPICION = "trait_suspicion"
    const val TRAIT_PLAYFULNESS = "trait_playfulness"
    const val TRAIT_CHAOS = "trait_chaos"
    const val MAX_SENDS_PER_HOUR = "max_sends_per_hour"
    const val MAX_SENDS_PER_DAY = "max_sends_per_day"
    const val PROACTIVE_DAILY_CAP = "proactive_daily_cap"
    const val PROACTIVE_REPLY_RATIO_MAX = "proactive_reply_ratio_max"
    const val COLD_MONTHLY_CAP = "cold_monthly_cap"
    const val AUTOBLOCK_ENABLED = "autoblock_enabled"
    const val AUTOBLOCK_PER_MIN = "autoblock_per_min"
    const val AUTOBLOCK_PER_5MIN = "autoblock_per_5min"
    const val AUTOBLOCK_PER_10MIN = "autoblock_per_10min"
    const val BLOCK_TOOL_ENABLED = "block_tool_enabled"
}

object AppSettingKeys {
    /**
     * Migration-only keys from builds which advertised an off-device Baileys
     * companion, or a second on-device transport. There is exactly one transport
     * left — the linked-device core inside this APK — so these intentionally
     * have no entry in [AppSettingsSchema] and are dropped on import.
     */
    const val TRANSPORT_MODE = "transport_mode"
    const val BRIDGE_URL = "bridge_url"
    const val BRIDGE_MODE = "bridge_mode"
    const val CONNECTION_MODE = "connection_mode"
    const val AUTOSTART = "autostart"
    const val OPENROUTER_BASE_URL = "openrouter_base_url"
    const val OPENROUTER_REFERER = "openrouter_referer"
    const val OPENROUTER_TITLE = "openrouter_title"
    const val OPENROUTER_TIMEOUT_MS = "openrouter_timeout_ms"
    const val OPENROUTER_API_KEY_REF = "openrouter_api_key_ref"
    const val BRIDGE_TOKEN_REF = "bridge_token_ref"
    const val OWNER_NUMBERS = "owner_numbers"
    const val ADMIN_NUMBERS = "admin_numbers"
    const val ALLOWLIST_NUMBERS = "allowlist_numbers"
    const val GROUP_ALLOWLIST = "group_allowlist"
    const val ALLOW_ALL = "allow_all"
    const val COMMAND_PREFIX = "command_prefix"
    const val GROUP_TRIGGER = "group_trigger"
    const val RATE_LIMIT_MAX = "rate_limit_max"
    const val RATE_LIMIT_WINDOW_MS = "rate_limit_window_ms"
    const val STT_FALLBACK_URL = "stt_fallback_url"
    const val STT_FALLBACK_MODEL = "stt_fallback_model"
    const val STT_API_KEY_REF = "stt_api_key_ref"
    const val MAX_VIDEO_BYTES = "max_video_bytes"
    const val WARMUP_ENABLED = "warmup_enabled"
    const val WARMUP_DAYS = "warmup_days"
    const val RECOVERY_WARMUP_DAYS = "recovery_warmup_days"
    const val HIDE_SENSITIVE_DATA = "hide_sensitive_data"
}

object SettingsCatalogs {
    val personas: List<PersonaCatalogEntry> =
        listOf(
            PersonaCatalogEntry("human", "Human", "🧑"),
            PersonaCatalogEntry("female", "Lina", "👩"),
            PersonaCatalogEntry("male", "Jonas", "👨"),
            PersonaCatalogEntry("goth", "Nico", "🖤"),
            PersonaCatalogEntry("sam", "Sam", "🧑"),
            PersonaCatalogEntry("default", "Default", "💬"),
            PersonaCatalogEntry("assistant", "Assistant", "🤖"),
            PersonaCatalogEntry("homie", "Homie", "🤝"),
            PersonaCatalogEntry("sarkastisch", "Sarcastic", "🙃"),
            PersonaCatalogEntry("flirty", "Flirty", "😉"),
            PersonaCatalogEntry("coach", "Coach", "🎯"),
            PersonaCatalogEntry("nerd", "Nerd", "🤓"),
            PersonaCatalogEntry("philosoph", "Philosopher", "🧠"),
            PersonaCatalogEntry("formell", "Formal", "🧑‍💼"),
            PersonaCatalogEntry("custom", "Custom persona", "🎭", customEditor = true),
        )

    val voices: List<VoiceCatalogEntry> =
        listOf(
            VoiceCatalogEntry("Zephyr", "bright, easy"),
            VoiceCatalogEntry("Puck", "buzzy, young"),
            VoiceCatalogEntry("Charon", "calm, informative"),
            VoiceCatalogEntry("Kore", "firm, neutral"),
            VoiceCatalogEntry("Fenrir", "energetic"),
            VoiceCatalogEntry("Leda", "youthful, female"),
            VoiceCatalogEntry("Orus", "firm, male"),
            VoiceCatalogEntry("Aoede", "light, airy, female"),
            VoiceCatalogEntry("Callirrhoe", "easy-going, female"),
            VoiceCatalogEntry("Autonoe", "bright, female"),
            VoiceCatalogEntry("Enceladus", "breathy, male"),
            VoiceCatalogEntry("Iapetus", "clear, male"),
            VoiceCatalogEntry("Umbriel", "relaxed"),
            VoiceCatalogEntry("Algieba", "soft"),
            VoiceCatalogEntry("Despina", "gentle, female"),
            VoiceCatalogEntry("Erinome", "clear, female"),
            VoiceCatalogEntry("Laomedeia", "lively, female"),
            VoiceCatalogEntry("Achernar", "soft, female"),
            VoiceCatalogEntry("Schedar", "even"),
            VoiceCatalogEntry("Gacrux", "mature, female"),
            VoiceCatalogEntry("Sulafat", "warm, female"),
            VoiceCatalogEntry("Zubenelgenubi", "casual, male"),
        )
}

object BotSettingsSchema {
    const val LIVE_SETTING_COUNT = 88
    const val LEGACY_HIDDEN_TTS_SCRIPT_MODEL = "tts_script_model"
    const val LEGACY_REMOVED_LEAVE_ON_READ = "leave_on_read"

    val all: List<SettingSpec> =
        listOf(
            bool(
                BotSettingKeys.ENABLED,
                true,
                "Global bot switch. Off ignores messages and stops proactive sends; admin control stays reachable.",
                SettingTier.BASIC,
                SettingGroup.STATUS,
                env = "BOT_ENABLED",
            ),
            model(
                BotSettingKeys.MODEL,
                "deepseek/deepseek-v4-pro-0813",
                "OpenRouter model used for ordinary replies.",
                SettingTier.BASIC,
                ModelRole.MAIN,
                env = "MODEL",
            ),
            model(
                BotSettingKeys.MEDIA_MODEL,
                "google/gemini-3.7-flash",
                "OpenRouter model used for image, audio and video analysis.",
                SettingTier.BASIC,
                ModelRole.MEDIA,
                env = "MEDIA_MODEL",
            ),
            text(
                BotSettingKeys.IMAGE_ANALYSIS_PROMPT,
                DefaultMediaAnalysisPrompts.IMAGE,
                "Editable instruction for incoming image analysis. The app still appends its " +
                    "plain-text safety contract.",
                SettingTier.EXPERT,
                SettingGroup.MEDIA,
                SettingControl.MULTILINE_TEXT,
                env = "IMAGE_ANALYSIS_PROMPT",
            ),
            text(
                BotSettingKeys.VIDEO_ANALYSIS_PROMPT,
                DefaultMediaAnalysisPrompts.VIDEO,
                "Editable instruction for incoming video analysis. The app still appends its " +
                    "plain-text safety contract.",
                SettingTier.EXPERT,
                SettingGroup.MEDIA,
                SettingControl.MULTILINE_TEXT,
                env = "VIDEO_ANALYSIS_PROMPT",
            ),
            text(
                BotSettingKeys.VOICE_ANALYSIS_PROMPT,
                DefaultMediaAnalysisPrompts.VOICE,
                "Editable instruction for voice-message analysis. Tone and Transcription remain " +
                    "mandatory structured fields.",
                SettingTier.EXPERT,
                SettingGroup.MEDIA,
                SettingControl.MULTILINE_TEXT,
                env = "VOICE_ANALYSIS_PROMPT",
            ),
            bool(
                BotSettingKeys.IMAGE_GENERATION_ENABLED,
                true,
                "Lets the model create one new character-consistent image through OpenRouter. " +
                    "Off exposes no generation tool and makes no image request.",
                SettingTier.BASIC,
                SettingGroup.MEDIA,
                env = "IMAGE_GENERATION_ENABLED",
            ),
            model(
                BotSettingKeys.IMAGE_GENERATION_MODEL,
                "openai/gpt-image-2",
                "OpenRouter Images API model used to create new pictures.",
                SettingTier.BASIC,
                ModelRole.IMAGE,
                env = "IMAGE_GENERATION_MODEL",
            ),
            enum(
                BotSettingKeys.IMAGE_GENERATION_QUALITY,
                "very_low",
                "Output quality and compression. The low end is deliberate: a picture that looks " +
                    "like an ordinary phone-gallery snapshot reads as one.",
                SettingTier.BASIC,
                SettingGroup.MEDIA,
                SettingControl.STEPPED_SLIDER,
                listOf(
                    SettingOption("very_low", "Very low"),
                    SettingOption("low", "Low"),
                    SettingOption("medium", "Medium"),
                    SettingOption("high", "High"),
                ),
                env = "IMAGE_GENERATION_QUALITY",
            ),
            text(
                BotSettingKeys.IMAGE_GENERATION_PREFIX,
                DEFAULT_IMAGE_GENERATION_PREFIX,
                "Stable direction prepended to every generated-image request. Character " +
                    "references are attached only when the model says the active persona is visible.",
                SettingTier.EXPERT,
                SettingGroup.MEDIA,
                SettingControl.MULTILINE_TEXT,
                env = "IMAGE_GENERATION_PREFIX",
            ),
            bool(
                BotSettingKeys.FIRST_PARTY_PROVIDER_ONLY,
                true,
                "Applies to every OpenRouter request: replies, checks, memory and media. It pins " +
                    "the company that built the selected model and disables provider fallback, " +
                    "so the request fails instead of silently moving to another host.",
                SettingTier.BASIC,
                SettingGroup.MODELS,
                env = "FIRST_PARTY_PROVIDER_ONLY",
            ),
            bool(
                BotSettingKeys.VISION_ENABLED,
                true,
                "Analyses incoming images; off stores only a media placeholder.",
                SettingTier.BASIC,
                SettingGroup.MEDIA,
                env = "VISION_ENABLED",
            ),
            bool(
                BotSettingKeys.STT_ENABLED,
                true,
                "Transcribes and analyses incoming voice messages.",
                SettingTier.BASIC,
                SettingGroup.MEDIA,
                env = "STT_ENABLED",
            ),
            bool(
                BotSettingKeys.VIDEO_ENABLED,
                true,
                "Analyses incoming videos and GIFs.",
                SettingTier.BASIC,
                SettingGroup.MEDIA,
                env = "VIDEO_ENABLED",
            ),
            enum(
                BotSettingKeys.PERSONALITY,
                "human",
                "Global persona for contacts without an explicit persona assignment.",
                SettingTier.BASIC,
                SettingGroup.PERSONA,
                SettingControl.PERSONA_PICKER,
                SettingsCatalogs.personas.map {
                    SettingOption(it.key, "${it.emoji} ${it.label}")
                },
            ),
            text(
                BotSettingKeys.BASE_PROMPT,
                DEFAULT_BASE_PROMPT,
                "Stable meta behaviour, placed before tool, runtime and persona instructions.",
                SettingTier.EXPERT,
                SettingGroup.PERSONA,
                SettingControl.MULTILINE_TEXT,
                env = "BASE_PROMPT",
            ),
            text(
                BotSettingKeys.SYSTEM_PROMPT,
                "",
                "Extra steering, or the text of your own custom persona.",
                SettingTier.EXPERT,
                SettingGroup.PERSONA,
                SettingControl.MULTILINE_TEXT,
                env = "SYSTEM_PROMPT",
            ),
            decimal(
                BotSettingKeys.TEMPERATURE,
                0.7,
                0.0,
                2.0,
                "Sampling temperature: low is more focused, high more varied.",
                SettingTier.BASIC,
                SettingGroup.GENERATION,
                SettingControl.SLIDER,
                env = "TEMPERATURE",
            ),
            integer(
                BotSettingKeys.MAX_TOKENS,
                10_000,
                1_000,
                100_000,
                "Maximum total output tokens for ordinary writing calls, including hidden reasoning.",
                SettingTier.EXPERT,
                SettingGroup.GENERATION,
                env = "MAX_TOKENS",
            ),
            integer(
                BotSettingKeys.MEMORY_CHAR_LIMIT,
                0,
                0,
                null,
                "Maximum characters injected from chat and persona memory; 0 means all of it.",
                SettingTier.EXPERT,
                SettingGroup.CONTEXT_MEMORY,
                env = "MEMORY_CHAR_LIMIT",
            ),
            integer(
                BotSettingKeys.HISTORY_RETENTION,
                1_000,
                0,
                5_000,
                "Keeps the last N messages per chat; 0 falls back to the fixed 5,000 safety ceiling.",
                SettingTier.EXPERT,
                SettingGroup.CONTEXT_MEMORY,
                env = "HISTORY_RETENTION",
            ),
            enum(
                BotSettingKeys.REASONING_EFFORT,
                "high",
                "Reasoning effort of the main model. Memory writing runs on the same model and " +
                    "the same effort, so both can reuse one prompt cache. Off omits the field.",
                SettingTier.BASIC,
                SettingGroup.MODELS,
                SettingControl.DROPDOWN,
                listOf("off", "none", "minimal", "low", "medium", "high", "xhigh", "max")
                    .map { SettingOption(it, it) },
                env = "REASONING_EFFORT",
            ),
            enum(
                BotSettingKeys.MEDIA_REASONING_EFFORT,
                "minimal",
                "Reasoning effort of the media model. Video analysis always clamps off/default " +
                    "to minimal because the Gemini Flash builds require reasoning for reliable video work.",
                SettingTier.BASIC,
                SettingGroup.MODELS,
                SettingControl.DROPDOWN,
                listOf("minimal", "low", "medium", "high", "xhigh", "max")
                    .map { SettingOption(it, it) },
                env = "MEDIA_REASONING_EFFORT",
            ),
            decimal(
                BotSettingKeys.TOP_P,
                1.0,
                0.0,
                1.0,
                "Nucleus sampling; 1 omits the parameter.",
                SettingTier.EXPERT,
                SettingGroup.GENERATION,
                SettingControl.SLIDER,
                env = "TOP_P",
            ),
            decimal(
                BotSettingKeys.FREQUENCY_PENALTY,
                0.0,
                -2.0,
                2.0,
                "Penalises frequently repeated tokens.",
                SettingTier.EXPERT,
                SettingGroup.GENERATION,
                SettingControl.SLIDER,
                env = "FREQUENCY_PENALTY",
            ),
            decimal(
                BotSettingKeys.PRESENCE_PENALTY,
                0.0,
                -2.0,
                2.0,
                "Favours new tokens and topics over ones already used.",
                SettingTier.EXPERT,
                SettingGroup.GENERATION,
                SettingControl.SLIDER,
                env = "PRESENCE_PENALTY",
            ),
            integer(
                BotSettingKeys.HISTORY_LIMIT,
                ConversationMemoryPolicy.DEFAULT_RETAINED_HISTORY_MESSAGES,
                0,
                ConversationMemoryPolicy.MAX_RETAINED_HISTORY_MESSAGES,
                "Messages that survive a memory write verbatim. They stay in the prompt after " +
                    "summarising so the conversation is not cut off mid-sentence; " +
                    "0 keeps every stored message.",
                SettingTier.BASIC,
                SettingGroup.CONTEXT_MEMORY,
                control = SettingControl.DROPDOWN,
                options =
                    listOf(SettingOption("0", "Keep all")) +
                        listOf(5, 10, 15, 20, 30, 50, 75, ConversationMemoryPolicy.MAX_RETAINED_HISTORY_MESSAGES)
                            .map { SettingOption(it.toString(), "$it messages") },
                env = "HISTORY_LIMIT",
            ),
            integer(
                BotSettingKeys.MEMORY_INTERVAL,
                ConversationMemoryPolicy.DEFAULT_MEMORY_INTERVAL_MESSAGES,
                ConversationMemoryPolicy.MIN_MEMORY_INTERVAL_MESSAGES,
                ConversationMemoryPolicy.MAX_MEMORY_INTERVAL_MESSAGES,
                "New messages between two memory writes. Both values add up to the context " +
                    "window: kept + interval. Higher means a longer window and rarer " +
                    "(so cheaper) summaries.",
                SettingTier.BASIC,
                SettingGroup.CONTEXT_MEMORY,
                control = SettingControl.DROPDOWN,
                options =
                    listOf(25, 50, 70, 100, 150, 200, 250, 300, 400, ConversationMemoryPolicy.MAX_MEMORY_INTERVAL_MESSAGES)
                        .map { SettingOption(it.toString(), "every $it messages") },
                env = "MEMORY_INTERVAL",
            ),
            bool(
                BotSettingKeys.MEMORY_CHAT_ENABLED,
                true,
                "Writes a chat memory at the interval. Off keeps the same window without paying " +
                    "for the summary: the prompt still snaps back to the kept messages every " +
                    "interval, but everything behind it is forgotten instead of remembered.",
                SettingTier.BASIC,
                SettingGroup.CONTEXT_MEMORY,
                env = "MEMORY_CHAT_ENABLED",
            ),
            bool(
                BotSettingKeys.MEMORY_GLOBAL_ENABLED,
                true,
                "Rebuilds the persona's cross-chat memory from the chat memories. Off leaves the " +
                    "last one standing and injected; it reads chat memories, so it has nothing " +
                    "new to work from while chat memory is off either.",
                SettingTier.BASIC,
                SettingGroup.CONTEXT_MEMORY,
                env = "MEMORY_GLOBAL_ENABLED",
            ),
            integer(
                BotSettingKeys.MEMORY_GLOBAL_INTERVAL,
                ConversationMemoryPolicy.DEFAULT_PERSONA_MEMORY_EVERY_CHAT_REFRESHES,
                ConversationMemoryPolicy.MIN_PERSONA_MEMORY_EVERY_CHAT_REFRESHES,
                ConversationMemoryPolicy.MAX_PERSONA_MEMORY_EVERY_CHAT_REFRESHES,
                "Chat memories per global memory: 1 rebuilds the persona view after every chat " +
                    "write, 10 after every tenth. Counted across all of that persona's chats, " +
                    "and it is a second full call — so this is the main lever on memory cost.",
                SettingTier.BASIC,
                SettingGroup.CONTEXT_MEMORY,
                control = SettingControl.STEPPED_SLIDER,
                env = "MEMORY_GLOBAL_INTERVAL",
            ),
            bool(
                BotSettingKeys.HISTORY_TIMESTAMPS,
                true,
                "Prefixes incoming history messages with the time. Turn off when the model " +
                    "starts copying timestamp blocks into its own replies; her own replies in " +
                    "the history never carry a timestamp either way.",
                SettingTier.EXPERT,
                SettingGroup.CONTEXT_MEMORY,
                env = "HISTORY_TIMESTAMPS",
            ),
            integer(
                BotSettingKeys.BATCH_WINDOW_MS,
                2_000,
                0,
                60_000,
                "Debounce window for messages arriving in quick succession, human preset only.",
                SettingTier.EXPERT,
                SettingGroup.TIMING,
                env = "BATCH_WINDOW_MS",
            ),
            enum(
                BotSettingKeys.REPLY_PRESET,
                "instant",
                "Human simulates reading, jitter and pauses; instant replies straight away.",
                SettingTier.BASIC,
                SettingGroup.TIMING,
                SettingControl.DROPDOWN,
                listOf(
                    SettingOption("human", "Human"),
                    SettingOption("instant", "Instant"),
                ),
                env = "REPLY_PRESET",
            ),
            integer(
                BotSettingKeys.TYPING_SPEED_WPM,
                HumanTimingPolicy.DEFAULT_TYPING_WPM,
                10,
                200,
                "Typing speed in words per minute; decides how long \"typing…\" runs. The measured " +
                    "phone average is 36 and the fastest group around 40 — the default " +
                    "${HumanTimingPolicy.DEFAULT_TYPING_WPM} deliberately sits at the brisk end. " +
                    "Higher is faster.",
                SettingTier.EXPERT,
                SettingGroup.TIMING,
                env = "TYPING_SPEED_WPM",
            ),
            integer(
                BotSettingKeys.READING_SPEED_WPS,
                HumanTimingPolicy.DEFAULT_READING_WORDS_PER_SECOND,
                1,
                60,
                "Reading speed in words per second for short messages. For long messages it " +
                    "automatically rises up to double (from roughly 200 words on). This is only a " +
                    "minimum delay before replying — if the model call takes longer, it never " +
                    "applies at all.",
                SettingTier.EXPERT,
                SettingGroup.TIMING,
                env = "READING_SPEED_WPS",
            ),
            integer(
                BotSettingKeys.TYPING_STOP_DELAY_MS,
                HumanTimingPolicy.DEFAULT_TYPING_STOP_DELAY_MS.toInt(),
                0,
                HumanTimingPolicy.MAX_TYPING_STOP_DELAY_MS.toInt(),
                "How much longer \"typing…\" keeps running when a new message interrupts the reply " +
                    "already under way. Nobody stops typing mid-word. Rolled between a quarter " +
                    "and the full value (so 0.5–2 s by default); 0 stops immediately.",
                SettingTier.EXPERT,
                SettingGroup.TIMING,
                env = "TYPING_STOP_DELAY_MS",
            ),
            integer(
                BotSettingKeys.PROACTIVE_LEVEL,
                0,
                0,
                10,
                "How proactive she may be, from off to very active.",
                SettingTier.BASIC,
                SettingGroup.PROACTIVITY,
                control = SettingControl.STEPPED_SLIDER,
                env = "PROACTIVE_LEVEL",
            ),
            enum(
                BotSettingKeys.PROACTIVE_MODE,
                "fixed",
                "fixed uses the level as set; hot_cold lets it drift over several days.",
                SettingTier.BASIC,
                SettingGroup.PROACTIVITY,
                SettingControl.DROPDOWN,
                listOf(
                    SettingOption("fixed", "Constant"),
                    SettingOption("hot_cold", "Hot/cold"),
                ),
                env = "PROACTIVE_MODE",
            ),
            bool(
                BotSettingKeys.AUTO_VERIFY,
                true,
                "Checks every draft with a second model and regenerates rejected replies.",
                SettingTier.EXPERT,
                SettingGroup.GENERATION,
                env = "AUTO_VERIFY",
            ),
            model(
                BotSettingKeys.VERIFY_MODEL,
                "deepseek/deepseek-v4-flash-0731",
                "Model used for the optional reply check.",
                SettingTier.EXPERT,
                ModelRole.VERIFIER,
                env = "VERIFY_MODEL",
            ),
            integer(
                BotSettingKeys.VERIFY_MAX_RETRIES,
                3,
                0,
                5,
                "Maximum regenerations after a negative verifier verdict, so 3 means up to four " +
                    "attempts at the reply in total.",
                SettingTier.EXPERT,
                SettingGroup.GENERATION,
                env = "VERIFY_MAX_RETRIES",
            ),
            integer(
                BotSettingKeys.VERIFY_MAX_TOKENS,
                2_000,
                32,
                4_000,
                "Output budget for one check. On reasoning models a quarter of it is reserved " +
                    "for the verdict itself, so reasoning cannot eat the whole budget.",
                SettingTier.EXPERT,
                SettingGroup.GENERATION,
                env = "VERIFY_MAX_TOKENS",
            ),
            enum(
                BotSettingKeys.VERIFY_REASONING_EFFORT,
                "low",
                "Reasoning effort of the check model; low keeps the check cheap while retaining the model's supported reasoning path.",
                SettingTier.EXPERT,
                SettingGroup.MODELS,
                SettingControl.DROPDOWN,
                listOf("off", "none", "minimal", "low", "medium", "high", "xhigh", "max")
                    .map { SettingOption(it, it) },
                env = "VERIFY_REASONING_EFFORT",
            ),
            bool(
                BotSettingKeys.VERIFY_FAIL_CLOSED,
                false,
                "Blocks the reply even when the check fails technically (empty, unreadable, " +
                    "unreachable). Off: the reply goes out and the reason is logged.",
                SettingTier.EXPERT,
                SettingGroup.GENERATION,
                env = "VERIFY_FAIL_CLOSED",
            ),
            bool(
                BotSettingKeys.BLOCK_REPEAT_IMAGES,
                true,
                "Stops the same local image from being sent twice in the same chat.",
                SettingTier.BASIC,
                SettingGroup.MEDIA,
                env = "BLOCK_REPEAT_IMAGES",
            ),
            bool(
                BotSettingKeys.CROSS_CHAT_SEARCH,
                false,
                "Lets the model list and search other local chats.",
                SettingTier.BASIC,
                SettingGroup.PRIVACY,
                env = "CROSS_CHAT_SEARCH",
            ),
            bool(
                BotSettingKeys.ENABLE_REACTIONS,
                true,
                "Allows real WhatsApp reactions from model replies.",
                SettingTier.EXPERT,
                SettingGroup.REALISM,
                env = "ENABLE_REACTIONS",
            ),
            bool(
                BotSettingKeys.ENABLE_QUOTE_REPLY,
                true,
                "Allows visible swipe replies quoting one specific incoming message.",
                SettingTier.EXPERT,
                SettingGroup.REALISM,
                env = "ENABLE_QUOTE_REPLY",
            ),
            bool(
                BotSettingKeys.AI_DISCLOSURE_ENABLED,
                true,
                "Tells each chat once, before the first automated reply, that it is talking to " +
                    "an AI. Every other realism setting works to hide that; this is the one that " +
                    "does not. Turning it off makes undisclosed automated messaging your own " +
                    "responsibility.",
                SettingTier.BASIC,
                SettingGroup.SAFETY,
                env = "AI_DISCLOSURE_ENABLED",
            ),
            text(
                BotSettingKeys.AI_DISCLOSURE_TEXT,
                DEFAULT_AI_DISCLOSURE_TEXT,
                "The exact one-off notice sent before the first automated reply in a chat. It " +
                    "is sent as its own message, never merged into an answer.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                SettingControl.MULTILINE_TEXT,
                env = "AI_DISCLOSURE_TEXT",
            ),
            bool(
                BotSettingKeys.MARK_READ,
                true,
                "Sends read receipts in step with when she replies.",
                SettingTier.BASIC,
                SettingGroup.PRIVACY,
                env = "MARK_READ",
            ),
            bool(
                BotSettingKeys.SELF_EDIT_ENABLED,
                true,
                "Allows rare visible edits or deletions of the message just sent.",
                SettingTier.EXPERT,
                SettingGroup.REALISM,
                env = "SELF_EDIT_ENABLED",
            ),
            decimal(
                BotSettingKeys.SELF_EDIT_CHANCE,
                0.03,
                0.0,
                1.0,
                "Probability of a self-correction per sent reply.",
                SettingTier.EXPERT,
                SettingGroup.REALISM,
                SettingControl.SLIDER,
                env = "SELF_EDIT_CHANCE",
            ),
            decimal(
                BotSettingKeys.SELF_EDIT_DELAY_DIVISOR,
                HumanTimingPolicy.DEFAULT_SELF_EDIT_DIVISOR,
                HumanTimingPolicy.MIN_SELF_EDIT_DIVISOR,
                HumanTimingPolicy.MAX_SELF_EDIT_DIVISOR,
                "Divisor for the typing time of a self-correction: the message is \"retyped\" per " +
                    "character, but only for that fraction of the time. 2 means half as long as " +
                    "the message itself took; higher means corrected faster.",
                SettingTier.EXPERT,
                SettingGroup.REALISM,
                SettingControl.SLIDER,
                env = "SELF_EDIT_DELAY_DIVISOR",
            ),
            bool(
                BotSettingKeys.MOOD_ENABLED,
                true,
                "Lets one shared underlying mood colour the tone of every persona.",
                SettingTier.BASIC,
                SettingGroup.REALISM,
                env = "MOOD_ENABLED",
            ),
            bool(
                BotSettingKeys.PROFILE_PICTURE_ENABLED,
                true,
                "Changes the WhatsApp profile picture when the active persona changes and permits " +
                    "the same persona's occasional scheduled rotation.",
                SettingTier.BASIC,
                SettingGroup.REALISM,
                env = "PROFILE_PICTURE_ENABLED",
            ),
            integer(
                BotSettingKeys.PROFILE_PICTURE_INTERVAL_DAYS,
                30,
                1,
                180,
                "Days between scheduled profile-picture changes while the persona stays the same. " +
                    "A persona switch may still change it immediately.",
                SettingTier.EXPERT,
                SettingGroup.REALISM,
                env = "PROFILE_PICTURE_INTERVAL_DAYS",
            ),
            bool(
                BotSettingKeys.ERROR_REPORTS,
                true,
                "Writes throttled, locally readable background errors into the activity log; credentials and message content are never included.",
                SettingTier.BASIC,
                SettingGroup.LOGGING,
                env = "ERROR_REPORTS",
            ),
            text(
                BotSettingKeys.SLEEP_START,
                "00:30",
                "Start of the local quiet hours, in HH:mm.",
                SettingTier.BASIC,
                SettingGroup.TIMING,
                SettingControl.TIME_PICKER,
                env = "SLEEP_START",
            ),
            text(
                BotSettingKeys.SLEEP_END,
                "08:30",
                "End of the local quiet hours, in HH:mm.",
                SettingTier.BASIC,
                SettingGroup.TIMING,
                SettingControl.TIME_PICKER,
                env = "SLEEP_END",
            ),
            text(
                BotSettingKeys.TIMEZONE,
                "Europe/Berlin",
                "IANA time zone for timestamps, quiet hours and proactivity.",
                SettingTier.BASIC,
                SettingGroup.TIMING,
                SettingControl.TIMEZONE_PICKER,
                env = "TIMEZONE",
            ),
            enum(
                BotSettingKeys.POWER_MODE,
                "default",
                "How much battery the bot is allowed to spend staying reachable. Default keeps " +
                    "the link up all day so messages arrive immediately; Human reply timing still " +
                    "applies. Low disconnects between the online " +
                    "sessions it would have had anyway, so the phone can actually suspend — " +
                    "replies then start at the next session instead of immediately. Both modes " +
                    "go fully offline during the quiet hours.",
                SettingTier.BASIC,
                SettingGroup.BATTERY,
                SettingControl.DROPDOWN,
                listOf(
                    SettingOption("default", "Default"),
                    SettingOption("low", "Low battery use"),
                ),
                env = "POWER_MODE",
            ),
            integer(
                BotSettingKeys.LOW_LISTEN_MINUTES,
                LinkPowerPolicy.DEFAULT_LISTEN_MINUTES,
                LinkPowerPolicy.MIN_LISTEN_MINUTES,
                LinkPowerPolicy.MAX_LISTEN_MINUTES,
                "Low battery use only: how long the bot keeps listening after it has answered " +
                    "before it drops the link. Every new message restarts it, so a running " +
                    "conversation keeps the bot connected. The actual wait is drawn within " +
                    "±20% of this so it is never the same twice.",
                SettingTier.BASIC,
                SettingGroup.BATTERY,
                SettingControl.SLIDER,
                env = "LOW_LISTEN_MINUTES",
            ),
            bool(
                BotSettingKeys.TTS_ENABLED,
                true,
                "Lets the model send a spoken voice note instead of text.",
                SettingTier.BASIC,
                SettingGroup.VOICE,
                env = "TTS_ENABLED",
            ),
            model(
                BotSettingKeys.TTS_MODEL,
                TtsVoiceCatalog.DEFAULT_REMOTE_MODEL,
                "Only speech models currently listed in the OpenRouter catalogue; Android system TTS is the local offline option.",
                SettingTier.BASIC,
                ModelRole.TTS,
                group = SettingGroup.VOICE,
                env = "TTS_MODEL",
            ),
            voice(
                BotSettingKeys.TTS_VOICE,
                "Kore",
                "Fallback voice used only when a personality has no own voice; switched to a compatible one automatically when the model changes.",
                SettingTier.BASIC,
                env = "TTS_VOICE",
            ),
            integer(
                BotSettingKeys.TTS_QUALITY,
                6,
                1,
                10,
                "Strength of the lo-fi post-processing: 1 clean, 10 extremely phone-like.",
                SettingTier.BASIC,
                SettingGroup.VOICE,
                control = SettingControl.STEPPED_SLIDER,
                env = "TTS_QUALITY",
            ),
            text(
                BotSettingKeys.TTS_STYLE_PROMPT,
                "real casual voice note, really low recording quality, noisy, phone microphone, fast paced, uneven",
                "Fixed style block for every TTS render; emotion hints are appended. OpenAI voices " +
                    "receive it as instructions, Gemini as a [bracket] direction in front of the " +
                    "words — which is never spoken.",
                SettingTier.EXPERT,
                SettingGroup.VOICE,
                SettingControl.MULTILINE_TEXT,
                env = "TTS_STYLE_PROMPT",
            ),
            trait(BotSettingKeys.TRAIT_OBEDIENCE, "Compliance, from stubborn to very obliging."),
            trait(BotSettingKeys.TRAIT_FLIRT, "Flirtiness, from cool to very flirty."),
            trait(BotSettingKeys.TRAIT_LEWD, "Raunchiness, from prudish to explicit; between adults only."),
            trait(BotSettingKeys.TRAIT_MEANNESS, "Bite, from extra sweet to mean and mocking."),
            trait(BotSettingKeys.TRAIT_INITIATIVE, "Initiative, from passive to actively driving the chat."),
            trait(BotSettingKeys.TRAIT_OPENNESS, "Openness, from closed off to very vulnerable."),
            trait(BotSettingKeys.TRAIT_SUSPICION, "Suspicion, from trusting to deeply sceptical."),
            trait(BotSettingKeys.TRAIT_PLAYFULNESS, "Playfulness, from serious to very playful."),
            trait(BotSettingKeys.TRAIT_CHAOS, "Chaos, from controlled to erratic."),
            integer(
                BotSettingKeys.MAX_SENDS_PER_HOUR,
                25,
                1,
                1_000,
                "Global rolling hourly limit; sending is throttled as it approaches and paused at the limit.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "MAX_SENDS_PER_HOUR",
            ),
            integer(
                BotSettingKeys.MAX_SENDS_PER_DAY,
                120,
                1,
                100_000,
                "Global rolling 24-hour limit for outgoing messages.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                options =
                    listOf(25, 50, 75, 120, 200, 300, 500, 750, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000, 100_000)
                        .map { SettingOption(it.toString(), it.toString()) },
                env = "MAX_SENDS_PER_DAY",
            ),
            integer(
                BotSettingKeys.PROACTIVE_DAILY_CAP,
                2,
                0,
                1_000,
                "Hard global daily ceiling for proactive messages; 0 derives it from the level.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "PROACTIVE_DAILY_CAP",
            ),
            decimal(
                BotSettingKeys.PROACTIVE_REPLY_RATIO_MAX,
                0.1,
                0.0,
                1.0,
                "Maximum share of proactive messages relative to incoming ones per day.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                SettingControl.SLIDER,
                env = "PROACTIVE_REPLY_RATIO_MAX",
            ),
            integer(
                BotSettingKeys.COLD_MONTHLY_CAP,
                30,
                1,
                10_000,
                "Rolling 30-day limit on contacts messaged for the first time without a reply.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "COLD_MONTHLY_CAP",
            ),
            bool(
                BotSettingKeys.AUTOBLOCK_ENABLED,
                true,
                "Blocks senders locally and on WhatsApp once they cross the flood thresholds.",
                SettingTier.BASIC,
                SettingGroup.SAFETY,
                env = "AUTOBLOCK_ENABLED",
            ),
            integer(
                BotSettingKeys.AUTOBLOCK_PER_MIN,
                10,
                0,
                1_000,
                "Flood threshold within one minute; 0 disables this window.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "AUTOBLOCK_PER_MIN",
            ),
            integer(
                BotSettingKeys.AUTOBLOCK_PER_5MIN,
                30,
                0,
                5_000,
                "Flood threshold within five minutes; 0 disables this window.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "AUTOBLOCK_PER_5MIN",
            ),
            integer(
                BotSettingKeys.AUTOBLOCK_PER_10MIN,
                50,
                0,
                10_000,
                "Flood threshold within ten minutes; 0 disables this window.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "AUTOBLOCK_PER_10MIN",
            ),
            bool(
                BotSettingKeys.BLOCK_TOOL_ENABLED,
                true,
                "Gives the model a tightly limited block_contact tool as a last resort.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "BLOCK_TOOL_ENABLED",
            ),
        ).also { specs ->
            check(specs.size == LIVE_SETTING_COUNT) {
                "Settings contract drift: expected $LIVE_SETTING_COUNT, got ${specs.size}"
            }
            check(specs.map(SettingSpec::key).distinct().size == specs.size) {
                "Settings keys must be unique"
            }
            check(specs.none { it.key == LEGACY_HIDDEN_TTS_SCRIPT_MODEL })
        }

    val byKey: Map<String, SettingSpec> = all.associateBy(SettingSpec::key)
    val basic: List<SettingSpec> = all.filter { it.tier == SettingTier.BASIC }
    val expert: List<SettingSpec> = all.filter { it.tier == SettingTier.EXPERT }
    val defaults: Map<String, SettingValue> = all.associate { it.key to it.defaultValue }

    fun requireSpec(key: String): SettingSpec =
        requireNotNull(byKey[key]) { "Unknown live setting: $key" }
}

object AppSettingsSchema {
    val all: List<SettingSpec> =
        listOf(
            bool(
                AppSettingKeys.AUTOSTART,
                true,
                "Starts the bot after a device reboot, but only once setup is complete.",
                SettingTier.BASIC,
                SettingGroup.CONNECTION,
            ),
            text(
                AppSettingKeys.OPENROUTER_BASE_URL,
                "https://openrouter.ai/api/v1",
                "Base URL for any OpenAI-compatible model API. OpenRouter-only catalogue and " +
                    "routing features may be unavailable on another endpoint.",
                SettingTier.EXPERT,
                SettingGroup.NETWORK,
                SettingControl.URL_FIELD,
                env = "OPENROUTER_BASE_URL",
            ),
            text(
                AppSettingKeys.OPENROUTER_REFERER,
                "https://github.com/totec448-spec/doppel",
                "Application URL sent for API attribution when the endpoint supports it.",
                SettingTier.EXPERT,
                SettingGroup.NETWORK,
                SettingControl.TEXT_FIELD,
                env = "OPENROUTER_REFERER",
            ),
            text(
                AppSettingKeys.OPENROUTER_TITLE,
                "WhatsApp Android Bridge",
                "Application name sent in the OpenRouter X-OpenRouter-Title header.",
                SettingTier.EXPERT,
                SettingGroup.NETWORK,
                SettingControl.TEXT_FIELD,
                env = "OPENROUTER_TITLE",
            ),
            integer(
                AppSettingKeys.OPENROUTER_TIMEOUT_MS,
                90_000,
                0,
                600_000,
                "Provider timeout in milliseconds. For chat this is a gap between bytes, not a total: 90 s of silence ends the call, while an answer that keeps arriving runs as long as it needs — a memory write over thousands of messages is meant to take minutes. Speech and transcription do not stream and still treat it as a whole-call limit. 0 disables the app-side timeout; a chat reply that goes quiet is still dropped and re-requested after at most 2 minutes.",
                SettingTier.EXPERT,
                SettingGroup.NETWORK,
                env = "OPENROUTER_TIMEOUT_MS",
            ),
            secretReference(
                AppSettingKeys.OPENROUTER_API_KEY_REF,
                "openrouter_api_key",
                "Reference to the OpenRouter key held in the Android keystore.",
                SettingTier.BASIC,
                SettingGroup.MODELS,
            ),
            secretReference(
                AppSettingKeys.BRIDGE_TOKEN_REF,
                "bridge_token",
                "Reference to the bridge token held in the Android keystore.",
                SettingTier.BASIC,
                SettingGroup.CONNECTION,
            ),
            stringList(
                AppSettingKeys.OWNER_NUMBERS,
                "Primary owner numbers; the first owner receives critical alerts.",
                SettingTier.BASIC,
                SettingGroup.ACCESS,
            ),
            stringList(
                AppSettingKeys.ADMIN_NUMBERS,
                "Numbers allowed to run admin commands.",
                SettingTier.BASIC,
                SettingGroup.ACCESS,
                env = "ADMIN_NUMBERS",
            ),
            stringList(
                AppSettingKeys.ALLOWLIST_NUMBERS,
                "Direct contacts allowed to chat with the bot.",
                SettingTier.BASIC,
                SettingGroup.ACCESS,
                env = "ALLOWLIST",
            ),
            stringList(
                AppSettingKeys.GROUP_ALLOWLIST,
                "Allowed groups by name, JID or group ID.",
                SettingTier.BASIC,
                SettingGroup.ACCESS,
                env = "GROUP_ALLOWLIST",
            ),
            bool(
                AppSettingKeys.ALLOW_ALL,
                true,
                "Gives every direct contact and group bot access; never grants admin rights.",
                SettingTier.BASIC,
                SettingGroup.ACCESS,
                env = "ALLOW_ALL",
            ),
            text(
                AppSettingKeys.COMMAND_PREFIX,
                "/",
                "Prefix for admin commands.",
                SettingTier.EXPERT,
                SettingGroup.ACCESS,
                SettingControl.TEXT_FIELD,
                env = "COMMAND_PREFIX",
            ),
            text(
                AppSettingKeys.GROUP_TRIGGER,
                "",
                "Optional group prefix that is stripped before processing.",
                SettingTier.EXPERT,
                SettingGroup.ACCESS,
                SettingControl.TEXT_FIELD,
                env = "GROUP_TRIGGER",
            ),
            integer(
                AppSettingKeys.RATE_LIMIT_MAX,
                15,
                1,
                1_000,
                "Maximum messages processed per chat and rate-limit window.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "RATE_LIMIT_MAX",
            ),
            integer(
                AppSettingKeys.RATE_LIMIT_WINDOW_MS,
                60_000,
                1_000,
                3_600_000,
                "Length of the per-chat rate-limit window in milliseconds.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "RATE_LIMIT_WINDOW_MS",
            ),
            text(
                AppSettingKeys.STT_FALLBACK_URL,
                "https://openrouter.ai/api/v1/audio/transcriptions",
                "Transcription endpoint. OpenRouter models always use the configured OpenRouter " +
                    "base URL and API key; this value is only for a manually entered external model.",
                SettingTier.EXPERT,
                SettingGroup.MEDIA,
                SettingControl.URL_FIELD,
                env = "STT_API_URL",
            ),
            text(
                AppSettingKeys.STT_FALLBACK_MODEL,
                // OpenAI's current transcription model, and the one an unconfigured install should
                // reach for: whisper-1 costs more per minute and errs far more often.
                "openai/gpt-transcribe",
                "Reads a voice note when the media model takes no audio. The picker lists only " +
                    "dedicated OpenRouter speech-to-text models, using their exact model IDs.",
                SettingTier.EXPERT,
                SettingGroup.MEDIA,
                SettingControl.MODEL_PICKER,
                env = "STT_MODEL",
                modelRole = ModelRole.TRANSCRIBE,
            ),
            secretReference(
                AppSettingKeys.STT_API_KEY_REF,
                "stt_api_key",
                "Key for the dedicated transcription endpoint, held in the Android keystore. Not " +
                    "needed for an OpenRouter model.",
                SettingTier.EXPERT,
                SettingGroup.MEDIA,
            ),
            integer(
                AppSettingKeys.MAX_VIDEO_BYTES,
                20 * 1_024 * 1_024,
                1 * 1_024 * 1_024,
                20 * 1_024 * 1_024,
                // Stored in bytes because the shared .env is; the app shows and takes megabytes.
                // The floor is one megabyte rather than one byte: below that no video passes at
                // all, so the whole lower half of the range only produced a broken setting.
                "Maximum video size sent for analysis. 1–20 MB; larger videos are not downloaded.",
                SettingTier.EXPERT,
                SettingGroup.MEDIA,
                env = "MEDIA_MAX_VIDEO_BYTES",
            ),
            bool(
                AppSettingKeys.WARMUP_ENABLED,
                true,
                "Ramps sending budgets up gradually after a fresh pairing.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "WARMUP_ENABLED",
            ),
            integer(
                AppSettingKeys.WARMUP_DAYS,
                7,
                1,
                30,
                "Length of the pairing warm-up in days.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "WARMUP_DAYS",
            ),
            integer(
                AppSettingKeys.RECOVERY_WARMUP_DAYS,
                3,
                1,
                30,
                "Length of the cautious restart after a restriction, in days.",
                SettingTier.EXPERT,
                SettingGroup.SAFETY,
                env = "RECOVERY_WARMUP_DAYS",
            ),
            // Display only, so it stays an app setting: nothing the bot does changes, and the
            // masking never reaches a stored value, a log line or a message.
            bool(
                AppSettingKeys.HIDE_SENSITIVE_DATA,
                false,
                "Hides phone numbers on screen: the bot's own number and every contact list keep " +
                    "their dialling prefix and lose the rest. Nothing is deleted and nothing the " +
                    "bot sends changes.",
                SettingTier.BASIC,
                SettingGroup.PRIVACY,
            ),
        ).also { specs ->
            check(specs.map(SettingSpec::key).distinct().size == specs.size)
        }

    val byKey: Map<String, SettingSpec> = all.associateBy(SettingSpec::key)
    val defaults: Map<String, SettingValue> = all.associate { it.key to it.defaultValue }

    fun requireSpec(key: String): SettingSpec =
        requireNotNull(byKey[key]) { "Unknown app setting: $key" }
}

private fun bool(
    key: String,
    default: Boolean,
    description: String,
    tier: SettingTier,
    group: SettingGroup,
    env: String? = null,
): SettingSpec =
    SettingSpec(
        key = key,
        valueType = SettingValueType.BOOLEAN,
        defaultValue = SettingValue.Bool(default),
        description = description,
        tier = tier,
        group = group,
        control = SettingControl.SWITCH,
        environmentKey = env,
        legacyAliases = defaultAliases(key),
    )

/**
 * [options] are suggestions, not a whitelist: an integer setting stays validated by its bounds, so
 * an imported or command-set value between two offered steps remains legal. They exist so a
 * [SettingControl.DROPDOWN] over a numeric range has something to offer instead of an empty menu.
 */
private fun integer(
    key: String,
    default: Int,
    min: Int?,
    max: Int?,
    description: String,
    tier: SettingTier,
    group: SettingGroup,
    control: SettingControl = SettingControl.NUMBER_FIELD,
    options: List<SettingOption> = emptyList(),
    env: String? = null,
): SettingSpec =
    SettingSpec(
        key = key,
        valueType = SettingValueType.INTEGER,
        defaultValue = SettingValue.Integer(default),
        description = description,
        tier = tier,
        group = group,
        control = control,
        bounds = NumericBounds(min?.toDouble(), max?.toDouble()),
        options = options,
        environmentKey = env,
        legacyAliases = defaultAliases(key),
    )

private fun decimal(
    key: String,
    default: Double,
    min: Double?,
    max: Double?,
    description: String,
    tier: SettingTier,
    group: SettingGroup,
    control: SettingControl,
    env: String? = null,
): SettingSpec =
    SettingSpec(
        key = key,
        valueType = SettingValueType.DECIMAL,
        defaultValue = SettingValue.Decimal(default),
        description = description,
        tier = tier,
        group = group,
        control = control,
        bounds = NumericBounds(min, max),
        environmentKey = env,
        legacyAliases = defaultAliases(key),
    )

private fun text(
    key: String,
    default: String,
    description: String,
    tier: SettingTier,
    group: SettingGroup,
    control: SettingControl,
    env: String? = null,
    modelRole: ModelRole? = null,
): SettingSpec =
    SettingSpec(
        key = key,
        valueType = SettingValueType.TEXT,
        defaultValue = SettingValue.Text(default),
        description = description,
        tier = tier,
        group = group,
        control = control,
        modelRole = modelRole,
        environmentKey = env,
        legacyAliases = defaultAliases(key),
    )

private fun model(
    key: String,
    default: String,
    description: String,
    tier: SettingTier,
    role: ModelRole,
    group: SettingGroup = SettingGroup.MODELS,
    env: String? = null,
): SettingSpec =
    text(
        key = key,
        default = default,
        description = description,
        tier = tier,
        group = group,
        control = SettingControl.MODEL_PICKER,
        env = env,
        modelRole = role,
    )

private fun voice(
    key: String,
    default: String,
    description: String,
    tier: SettingTier,
    env: String,
): SettingSpec =
    SettingSpec(
        key = key,
        valueType = SettingValueType.TEXT,
        defaultValue = SettingValue.Text(default),
        description = description,
        tier = tier,
        group = SettingGroup.VOICE,
        control = SettingControl.VOICE_PICKER,
        options = SettingsCatalogs.voices.map { SettingOption(it.name, it.name, it.description) },
        environmentKey = env,
        legacyAliases = defaultAliases(key),
    )

private fun enum(
    key: String,
    default: String,
    description: String,
    tier: SettingTier,
    group: SettingGroup,
    control: SettingControl,
    options: List<SettingOption>,
    env: String? = null,
): SettingSpec =
    SettingSpec(
        key = key,
        valueType = SettingValueType.ENUM,
        defaultValue = SettingValue.Text(default),
        description = description,
        tier = tier,
        group = group,
        control = control,
        options = options,
        environmentKey = env,
        legacyAliases = defaultAliases(key),
    )

private fun trait(
    key: String,
    description: String,
): SettingSpec =
    integer(
        key = key,
        default = 0,
        min = -3,
        max = 3,
        description = description,
        tier = SettingTier.EXPERT,
        group = SettingGroup.PERSONA,
        control = SettingControl.STEPPED_SLIDER,
    )

private fun stringList(
    key: String,
    description: String,
    tier: SettingTier,
    group: SettingGroup,
    env: String? = null,
): SettingSpec =
    SettingSpec(
        key = key,
        valueType = SettingValueType.STRING_LIST,
        defaultValue = SettingValue.StringList.of(emptyList()),
        description = description,
        tier = tier,
        group = group,
        control = SettingControl.CHIP_LIST,
        environmentKey = env,
        legacyAliases = defaultAliases(key),
    )

private fun secretReference(
    key: String,
    reference: String,
    description: String,
    tier: SettingTier,
    group: SettingGroup,
): SettingSpec =
    SettingSpec(
        key = key,
        valueType = SettingValueType.SECRET_REFERENCE,
        defaultValue = SettingValue.SecretReference(reference),
        description = description,
        tier = tier,
        group = group,
        control = SettingControl.SECRET_REFERENCE,
        legacyAliases = defaultAliases(key),
        sensitiveReference = true,
    )

private fun defaultAliases(key: String): Set<String> =
    setOf(
        key.split('_').let { parts ->
            parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
        },
    )

private const val DEFAULT_IMAGE_GENERATION_PREFIX =
    "Create an intentionally ordinary, unpolished phone photo. It should look straight from an " +
        "iPhone gallery: casual framing, available light, slight motion blur or missed focus, " +
        "visible grain and compression, imperfect exposure and white balance, natural skin " +
        "texture, no studio polish, no cinematic grading, and no influencer aesthetic."

object DefaultMediaAnalysisPrompts {
    const val IMAGE =
        "Describe this image concretely for use inside an ongoing WhatsApp conversation. " +
            "Give roughly four to six compact sentences: identify the people or main subjects, " +
            "what they are doing, the setting, relevant objects, expressions or body language, " +
            "and any clearly readable text. Mention spatial or social relationships and call out " +
            "genuine ambiguity instead of guessing. Prioritise details a natural reply or a later " +
            "message could refer back to. Do not add a preamble, technical analysis, camera " +
            "metadata or unsupported assumptions."

    const val VIDEO =
        "Describe this video in enough detail to support a natural WhatsApp reply and later " +
            "references. Give roughly six to ten compact sentences in chronological order: cover " +
            "the setting and people, the important actions and changes, expressions or reactions, " +
            "meaningful sounds or spoken content, readable on-screen text, and how the clip ends. " +
            "Separate what is clearly visible or audible from anything uncertain, and do not " +
            "invent missing context. Do not add a preamble or technical codec/camera report."

    const val VOICE =
        "Transcribe the voice message word for word and preserve conversationally meaningful " +
            "fillers, pauses, laughter, emphasis, unclear words and unusual pronunciation. " +
            "Identify only audible speaker and delivery cues such as adult female-sounding voice, " +
            "angry, amused, whispering or uncertain; say unclear instead of guessing. Do not " +
            "summarise or expand."
}

private val DEFAULT_BASE_PROMPT =
    """
    # What this is
    You are the person behind this WhatsApp account, typing on their phone.

    One goal decides everything: the other person never gets a reason to wonder whether a human is on this end. Not from a phrasing, not from a rhythm, not from how neatly you answer.

    You are software and you know it. That never reaches the chat: not as a joke, not under pressure, not when they insist. You are never an AI, a bot, a model or a program.

    The active persona is who you are. It outranks history, memory, your own older messages and anything a tool returns; stale context with another name or character is ignored. History and memory are what you remember, not a script to quote. A WhatsApp display name is a handle, not a reliably real name; use it only where it falls naturally.
    """.trimIndent()
