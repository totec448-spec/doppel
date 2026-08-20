package de.totec.doppel.commands

import java.util.Locale
import kotlinx.coroutines.CancellationException

class CommandExecutor(
    private val actions: AdminActions,
    private val prefixProvider: () -> String = { "/" },
    val registry: CommandRegistry = CommandRegistry(),
    private val wipeChallenges: WipeChallengeManager = WipeChallengeManager(),
) {
    private val parser = CommandParser(registry, prefixProvider)

    suspend fun execute(request: CommandRequest): CommandExecutionResult {
        val prefix = prefixProvider()
        val parsed = parser.parse(request.text, prefix)
        val invocation = when (parsed) {
            CommandParseResult.NotACommand -> return CommandExecutionResult.NotACommand
            is CommandParseResult.UnknownCommand -> {
                return CommandExecutionResult.UnknownCommandFallThrough(parsed.invokedName)
            }
            is CommandParseResult.Parsed -> parsed.invocation
        }

        if (!request.isAdmin) {
            return CommandExecutionResult.NotAuthorizedFallThrough(invocation.spec.name)
        }
        if (invocation.spec.id == CommandId.API_KEY && request.isGroup) {
            return reply("🔒 Der API-Key kann nur im Admin-Direktchat verwaltet werden.")
        }

        val context = AdminContext(
            origin = AdminOrigin.CHAT,
            actorId = request.senderId,
            chatId = request.chatId,
            isGroup = request.isGroup,
        )
        return try {
            dispatch(invocation, request, context, prefix)
        } catch (cancelled: CancellationException) {
            // The dispatch is a suspending call now, so this catch sits in the path of the
            // runtime's own shutdown. Reporting "the command failed" and returning normally would
            // leave the coroutine that was told to stop running on.
            throw cancelled
        } catch (_: Exception) {
            // Never echo exception text: adapters can touch credentials or
            // transport state, and normal users must not receive internals.
            reply("❌ Der Admin-Befehl konnte nicht ausgeführt werden.")
        }
    }

    private suspend fun dispatch(
        invocation: CommandInvocation,
        request: CommandRequest,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult = when (invocation.spec.id) {
        CommandId.HELP -> help(context, prefix)
        CommandId.STATUS -> actionReply(context, AdminAction.Status, "Status geladen.")
        CommandId.MOOD -> mood(invocation, context, prefix)
        CommandId.META -> actionReply(context, AdminAction.Meta, "Meta-Übersicht geladen.")
        CommandId.ALLOWLIST -> access(invocation, context, prefix, AccessList.ALLOW)
        CommandId.GROUP_ALLOWLIST -> access(invocation, context, prefix, AccessList.GROUP_ALLOW)
        CommandId.API_KEY -> apiKey(invocation, context, prefix)
        CommandId.RESET -> reset(invocation, context, request.chatId, prefix)
        CommandId.CLEAR_MEMORY -> {
            actionReply(
                context,
                AdminAction.ClearChatMemory(request.chatId),
                "Memory, Verlauf, proaktiver Zustand und Bild-Dedup dieses Persona-Threads wurden gelöscht.",
            )
        }
        CommandId.CLEAR_ALL -> clearAll(invocation, request, context, prefix)
        CommandId.SETTINGS -> actionReply(context, AdminAction.ListSettings, "Keine Settings vorhanden.")
        CommandId.SET -> set(invocation, context, prefix)
        CommandId.GET -> get(invocation, context, prefix)
        CommandId.MODEL -> settingShortcut(invocation, context, "model", "Modell", prefix)
        CommandId.MEDIA_MODEL -> {
            settingShortcut(invocation, context, "media_model", "Medienmodell", prefix)
        }
        CommandId.HISTORY -> history(invocation, context, prefix)
        CommandId.SYSTEM -> system(invocation, context, prefix)
        CommandId.PERSONALITY -> personality(invocation, context, prefix)
        CommandId.OFF -> {
            actionReply(
                context,
                AdminAction.PauseBot,
                "🔴 Bot ist funktional pausiert. Verbindung und Admin-Befehle bleiben aktiv. Fortsetzen mit ${prefix}on.",
            )
        }
        CommandId.ON -> {
            actionReply(context, AdminAction.ResumeBot, "🟢 Bot ist wieder aktiv.")
        }
        CommandId.CROSS_CHAT -> crossChat(invocation, context, prefix)
        CommandId.VOICE -> voice(invocation, context, prefix)
        CommandId.TRAITS -> traits(invocation, context, prefix)
        CommandId.PERSONA -> persona(invocation, request, context, prefix)
        CommandId.BLOCK -> block(invocation, context, prefix)
        CommandId.UNBLOCK -> unblock(invocation, context, prefix)
        CommandId.AUTOBLOCK -> autoblock(invocation, context, prefix)
        CommandId.BLOCK_TOOL -> blockTool(invocation, context, prefix)
        CommandId.PROACTIVE -> proactive(invocation, context, prefix)
        CommandId.REACHOUT -> actionReply(context, AdminAction.Reachout, "Reachout-Status geladen.")
        CommandId.SAFETY -> safety(invocation, context, prefix)
    }

    private suspend fun help(context: AdminContext, prefix: String): CommandExecutionResult {
        val result = call(context, AdminAction.Help)
        if (result !is AdminResult.Success) return renderResult(result, "Hilfe konnte nicht geladen werden.")
        val header = result.payload as? AdminPayload.HelpHeader
        val generated = registry.renderHelp(
            prefix = prefix,
            title = header?.title ?: "WhatsApp-Bot – Admin-Befehle",
        )
        val introduction = header?.introduction?.trim().orEmpty()
        return reply(if (introduction.isEmpty()) generated else "$introduction\n\n$generated")
    }

    private suspend fun mood(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val value = invocation.arguments.firstOrNull()
        if (value == null) return actionReply(context, AdminAction.MoodStatus, "Mood-Status geladen.")
        val enabled = parseOnOff(value)
            ?: return usage("Nutzung: ${prefix}mood on|off")
        return setSettings(
            context,
            mapOf("mood_enabled" to enabled.toString()),
            "✅ Mood ist ${if (enabled) "an" else "aus"}.",
        )
    }

    private suspend fun access(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
        list: AccessList,
    ): CommandExecutionResult {
        val action = invocation.arguments.firstOrNull()?.lowercase(Locale.ROOT)
        if (action == null || action == "list" || action == "status") {
            return actionReply(context, AdminAction.ListAccess(list), "Liste ist leer.")
        }

        val operation = when (action) {
            "add" -> AccessOperation.ADD
            "remove", "rm", "delete" -> AccessOperation.REMOVE
            "set", "replace" -> AccessOperation.REPLACE
            else -> return accessUsage(prefix, list)
        }
        val rawEntries = remainderAfterFirstToken(invocation.rawArguments)
        val entries = when (list) {
            AccessList.ALLOW, AccessList.ADMIN -> parsePhoneEntries(rawEntries)
            AccessList.GROUP_ALLOW -> parseGroupEntries(rawEntries)
        }
        if (operation != AccessOperation.REPLACE && entries.isEmpty()) {
            return reply(
                if (list == AccessList.GROUP_ALLOW) {
                    "❌ Gruppe explizit angeben. Nutzung: ${prefix}groupallowlist $action <Gruppenname|Gruppen-JID>"
                } else {
                    "❌ Keine gültige Telefonnummer. Nutzung: ${prefix}allowlist $action <Nummern>"
                },
            )
        }
        // REPLACE intentionally accepts an empty list so an admin can clear it.
        return actionReply(
            context,
            AdminAction.ChangeAccess(list, operation, entries),
            when (operation) {
                AccessOperation.ADD -> "✅ ${entries.size} Eintrag/Einträge hinzugefügt."
                AccessOperation.REMOVE -> "✅ ${entries.size} Eintrag/Einträge entfernt."
                AccessOperation.REPLACE -> "✅ Liste durch ${entries.size} Eintrag/Einträge ersetzt."
            },
        )
    }

    private fun accessUsage(prefix: String, list: AccessList): CommandExecutionResult {
        val command = if (list == AccessList.GROUP_ALLOW) "groupallowlist" else "allowlist"
        return usage("Nutzung: $prefix$command [list|add|remove|set] [Einträge]")
    }

    private suspend fun apiKey(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val action = invocation.arguments.firstOrNull()?.lowercase(Locale.ROOT)
        if (action == null || action == "status") {
            return actionReply(context, AdminAction.ApiKeyStatus, "API-Key-Status geladen.")
        }
        if (action in setOf("clear", "reset", "unset")) {
            return actionReply(context, AdminAction.ClearApiKey, "✅ Runtime-Key entfernt.")
        }
        if (action == "set") {
            val key = invocation.arguments.getOrNull(1)?.trim().orEmpty()
            if (invocation.arguments.size != 2 || key.length !in 16..512) {
                return usage("Nutzung: ${prefix}apikey set <OpenRouter-Key>")
            }
            // The command adapter is synchronous. Keep the mutable secret alive only for that one
            // call, then zero its backing buffer; neither the success text nor diagnostics contains
            // the key. Direct-chat-only enforcement happens before dispatch.
            val secret = SecretInput.from(key)
            return try {
                actionReply(
                    context,
                    AdminAction.SetApiKey(secret),
                    "✅ OpenRouter-Key sicher ersetzt.",
                )
            } finally {
                secret.close()
            }
        }
        return usage("Nutzung: ${prefix}apikey [status|set <Key>|clear]")
    }

    private suspend fun reset(
        invocation: CommandInvocation,
        context: AdminContext,
        chatId: String,
        prefix: String,
    ): CommandExecutionResult {
        if (invocation.arguments.size > 1 ||
            (invocation.arguments.size == 1 && !invocation.arguments[0].equals("all", ignoreCase = true))
        ) {
            return reply("❌ Erlaubt sind nur „reset“ oder „reset all“.")
        }
        val all = invocation.arguments.firstOrNull()?.equals("all", ignoreCase = true) == true
        return actionReply(
            context,
            AdminAction.ResetChat(chatId, resetGlobalSettings = all),
            if (all) {
                "✅ Persona-Thread und alle globalen Settings wurden zurückgesetzt."
            } else {
                "✅ Verlauf, proaktiver Zustand und Bild-Dedup dieses Persona-Threads wurden gelöscht. " +
                    "Memory bleibt; vollständig löschen mit ${prefix}clearmemory."
            },
        )
    }

    private suspend fun set(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val key = invocation.arguments.firstOrNull()
            ?: return usage("Nutzung: ${prefix}set <Key> <Wert>")
        val value = remainderAfterFirstToken(invocation.rawArguments)
        if (value.isBlank()) return reply("❌ Kein Wert für „$key“ angegeben.")
        return setSettings(context, mapOf(key to value), "✅ *$key* wurde aktualisiert.")
    }

    private suspend fun get(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val key = invocation.arguments.firstOrNull()
            ?: return usage("Nutzung: ${prefix}get <Key>")
        return actionReply(context, AdminAction.GetSetting(key), "Setting „$key“ geladen.")
    }

    private suspend fun settingShortcut(
        invocation: CommandInvocation,
        context: AdminContext,
        key: String,
        label: String,
        prefix: String,
    ): CommandExecutionResult {
        val value = invocation.rawArguments.trim()
        if (value.isEmpty()) return actionReply(context, AdminAction.GetSetting(key), "$label geladen.")
        return setSettings(context, mapOf(key to value), "✅ $label: $value")
    }

    private suspend fun history(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val raw = invocation.rawArguments.trim().lowercase(Locale.ROOT)
        if (raw.isEmpty()) {
            return actionReply(context, AdminAction.GetSetting("history_limit"), "History-Limit geladen.")
        }
        val value = if (raw in setOf("all", "alle", "unlimited", "off", "0")) 0 else raw.toIntOrNull()
        if (value == null || value < 0) {
            return reply("❌ History muss eine ganze Zahl ab 0 oder „all“ sein. Nutzung: ${prefix}history 30")
        }
        val changes = linkedMapOf("history_limit" to value.toString())
        val retention = getSettingSnapshot(context, "history_retention")?.value?.toIntOrNull()
        if (value > 0 && retention != null && retention > 0 && retention < value) {
            changes["history_retention"] = value.toString()
        }
        return setSettings(
            context,
            changes,
            if (value == 0) "✅ History-Kontext: alle gespeicherten Nachrichten." else "✅ History-Kontext: letzte $value Nachrichten.",
        )
    }

    private suspend fun system(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val value = invocation.rawArguments.trim()
        if (value.isEmpty()) {
            return actionReply(context, AdminAction.GetSetting("system_prompt"), "Custom-Systemtext geladen.")
        }
        return setSettings(
            context,
            linkedMapOf("system_prompt" to value, "personality" to "custom"),
            "✅ Eigener Systemtext ist aktiv (personality = custom).",
        )
    }

    private suspend fun personality(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val key = invocation.arguments.firstOrNull()?.lowercase(Locale.ROOT)
            ?: return actionReply(context, AdminAction.ListPersonas, "Personas geladen.")
        if (key == "custom") {
            val prompt = remainderAfterFirstToken(invocation.rawArguments)
            if (prompt.isBlank()) return usage("Nutzung: ${prefix}personality custom <Text>")
            return setSettings(
                context,
                linkedMapOf("system_prompt" to prompt, "personality" to "custom"),
                "✅ Eigene Persönlichkeit ist aktiv.",
            )
        }
        return setSettings(context, mapOf("personality" to key), "✅ Persönlichkeit: $key")
    }

    private suspend fun crossChat(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val raw = invocation.arguments.firstOrNull()
            ?: return actionReply(context, AdminAction.GetSetting("cross_chat_search"), "Cross-Chat-Status geladen.")
        val enabled = parseOnOff(raw)
            ?: return usage("Nutzung: ${prefix}crosschat on|off")
        return setSettings(
            context,
            mapOf("cross_chat_search" to enabled.toString()),
            "✅ Cross-Chat-Suche ist ${if (enabled) "an" else "aus"}.",
        )
    }

    private suspend fun voice(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val sub = invocation.arguments.firstOrNull()?.lowercase(Locale.ROOT)
            ?: return actionReply(context, AdminAction.VoiceStatus, "Voice-Status geladen.")
        if (sub == "on" || sub == "off") {
            return setSettings(
                context,
                mapOf("tts_enabled" to (sub == "on").toString()),
                "✅ Sprachnachrichten sind ${if (sub == "on") "an" else "aus"}.",
            )
        }
        if (sub in setOf("quality", "qualitaet", "q")) {
            val value = invocation.arguments.getOrNull(1)?.toIntOrNull()
            if (value == null) return actionReply(context, AdminAction.GetSetting("tts_quality"), "TTS-Qualität geladen.")
            if (value !in 1..10) return reply("❌ TTS-Qualität muss eine ganze Zahl von 1 bis 10 sein.")
            return setSettings(context, mapOf("tts_quality" to value.toString()), "✅ TTS-Qualität: $value")
        }
        if (sub == "voices" || sub == "list") {
            return actionReply(context, AdminAction.ListVoices, "Keine Stimmen verfügbar.")
        }
        val name =
            if (sub in setOf("voice", "allgemein", "fallback")) {
                invocation.arguments.drop(1).joinToString(" ").trim()
            } else {
                invocation.arguments.joinToString(" ").trim()
            }
        if (name.isEmpty()) {
            return actionReply(context, AdminAction.GetSetting("tts_voice"), "Standardstimme geladen.")
        }
        return actionReply(context, AdminAction.SetDefaultVoice(name), "✅ Standardstimme: $name")
    }

    private suspend fun traits(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val name = invocation.arguments.firstOrNull()?.lowercase(Locale.ROOT)
        if (name == null) {
            val result = call(context, AdminAction.ListSettings)
            if (result is AdminResult.Success && result.payload is AdminPayload.Settings) {
                val traits = result.payload.settings.filter { it.key.startsWith("trait_") }
                return reply(formatSettings("*Charakter-Stats* (-3 bis +3)", traits))
            }
            return renderResult(result, "Charakter-Stats geladen.")
        }
        if (name == "reset") {
            val changes = traitCommandDefs.associate { it.key to "0" }
            return setSettings(context, changes, "✅ Alle Charakter-Stats stehen auf 0.")
        }
        val trait = findTraitCommand(name)
            ?: return reply("❌ Unbekannter Stat. Nutzung: ${prefix}traits <Name> <-3..3>")
        val rawValue = invocation.arguments.getOrNull(1)
            ?: return actionReply(context, AdminAction.GetSetting(trait.key), "${trait.displayName} geladen.")
        val value = rawValue.toIntOrNull()
        if (value == null || value !in -3..3) {
            return reply("❌ ${trait.displayName} muss eine ganze Zahl von -3 bis 3 sein.")
        }
        return setSettings(context, mapOf(trait.key to value.toString()), "✅ ${trait.displayName}: ${signed(value)}")
    }

    private suspend fun persona(
        invocation: CommandInvocation,
        request: CommandRequest,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val sub = invocation.arguments.firstOrNull()?.lowercase(Locale.ROOT)
        if (sub == null || sub == "list") {
            return actionReply(context, AdminAction.ListPersonas, "Keine Personas vorhanden.")
        }
        if (sub == "create" || sub == "new") {
            val key = invocation.arguments.getOrNull(1)?.lowercase(Locale.ROOT)
            val prompt = invocation.rawArguments
                .let(::remainderAfterFirstToken)
                .let(::remainderAfterFirstToken)
            if (key == null || prompt.isBlank()) {
                return usage("Nutzung: ${prefix}persona create <Key> <Prompt>")
            }
            if (!PERSONA_KEY.matches(key)) {
                return reply("❌ Persona-Key: 2–40 Zeichen, nur a-z, 0-9, _ und -.")
            }
            if (key in RESERVED_PERSONA_KEYS) return reply("❌ „$key“ ist als Built-in reserviert.")
            return actionReply(context, AdminAction.UpsertPersona(key, prompt), "✅ Persona „$key“ gespeichert.")
        }
        if (sub == "assign") {
            val key = invocation.arguments.getOrNull(1)?.lowercase(Locale.ROOT)
                ?: return usage("Nutzung: ${prefix}persona assign <Key> [Ziel]")
            val target = resolvePersonaTarget(invocation.arguments.getOrNull(2), request.chatId)
                ?: return reply("❌ Ungültiges explizites Ziel; keine Zuweisung wurde geändert.")
            return actionReply(context, AdminAction.AssignPersona(key, target), "✅ $target nutzt Persona „$key“.")
        }
        if (sub == "unassign" || sub == "clear") {
            val target = resolvePersonaTarget(invocation.arguments.getOrNull(1), request.chatId)
                ?: return reply("❌ Ungültiges explizites Ziel; keine Zuweisung wurde geändert.")
            return actionReply(context, AdminAction.UnassignPersona(target), "✅ Persona-Zuweisung für $target entfernt.")
        }
        if (sub == "images" || sub == "bilder") {
            val key = invocation.arguments.getOrNull(1)?.lowercase(Locale.ROOT)
                ?: return usage("Nutzung: ${prefix}persona images <Key>")
            return actionReply(context, AdminAction.PersonaImages(key), "Bilderbereich für $key ist bereit.")
        }
        if (sub in setOf("delete", "remove", "del")) {
            val key = invocation.arguments.getOrNull(1)?.lowercase(Locale.ROOT)
                ?: return usage("Nutzung: ${prefix}persona delete <Key>")
            return actionReply(context, AdminAction.DeletePersona(key), "✅ Persona „$key“ gelöscht.")
        }
        return usage("Nutzung: ${prefix}persona [list|create|assign|unassign|images|delete] …")
    }

    private suspend fun block(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        if (invocation.rawArguments.isBlank() || invocation.arguments.firstOrNull()?.equals("list", true) == true) {
            return actionReply(context, AdminAction.ListBlocks, "Blockliste ist leer.")
        }
        val parsed = parseLeadingPhoneAndReason(invocation.rawArguments)
            ?: return reply("❌ Keine gültige Nummer. Nutzung: ${prefix}block <Nummer> [Grund]")
        val admins = call(context, AdminAction.ListAccess(AccessList.ADMIN))
        val adminPayload = (admins as? AdminResult.Success)
            ?.payload
            ?.let { it as? AdminPayload.AccessEntries }
        if (adminPayload == null || adminPayload.list != AccessList.ADMIN) {
            return reply("❌ Adminliste konnte nicht sicher geprüft werden; Blockierung abgebrochen.")
        }
        val adminNumbers = adminPayload.entries.mapNotNull(::normalizePhoneNumber).toSet()
        if (parsed.number in adminNumbers) return reply("❌ Eine Admin-Nummer kann nicht blockiert werden.")
        return actionReply(
            context,
            AdminAction.BlockContact(parsed.number, parsed.reason.ifBlank { "manuell vom Admin" }),
            "🚫 ${parsed.number} wurde blockiert.",
        )
    }

    private suspend fun unblock(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val number = parseLeadingPhoneAndReason(invocation.rawArguments)?.number
            ?: return usage("Nutzung: ${prefix}unblock <Nummer>")
        return actionReply(context, AdminAction.UnblockContact(number), "✅ $number wurde entblockt.")
    }

    private suspend fun autoblock(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val sub = invocation.arguments.firstOrNull()?.lowercase(Locale.ROOT)
        if (sub == null || sub == "status") {
            return filteredSettings(
                context,
                "*Auto-Block*",
                setOf(
                    "autoblock_enabled",
                    "autoblock_per_min",
                    "autoblock_per_5min",
                    "autoblock_per_10min",
                    "block_tool_enabled",
                ),
            )
        }
        if (sub == "on" || sub == "off") {
            return setSettings(
                context,
                mapOf("autoblock_enabled" to (sub == "on").toString()),
                "✅ Auto-Block ist ${if (sub == "on") "an" else "aus"}.",
            )
        }
        if (sub == "tool") {
            val value = invocation.arguments.getOrNull(1)?.lowercase(Locale.ROOT)
            if (value != "on" && value != "off") {
                return usage("Nutzung: ${prefix}autoblock tool on|off")
            }
            return setSettings(
                context,
                mapOf("block_tool_enabled" to (value == "on").toString()),
                "✅ Modell-Blocktool ist ${if (value == "on") "an" else "aus"}.",
            )
        }
        if (sub == "limits" || sub == "schwellen") {
            val values = invocation.arguments.drop(1).map(String::toIntOrNull)
            if (values.size != 3 || values.any { it == null || it < 0 }) {
                return reply("❌ Alle drei Schwellen müssen nichtnegative ganze Zahlen sein.")
            }
            val changes = linkedMapOf(
                "autoblock_per_min" to values[0].toString(),
                "autoblock_per_5min" to values[1].toString(),
                "autoblock_per_10min" to values[2].toString(),
            )
            return setSettings(context, changes, "✅ Alle drei Auto-Block-Schwellen wurden atomar gesetzt.")
        }
        return usage("Nutzung: ${prefix}autoblock [on|off|tool on|off|limits <1m> <5m> <10m>]")
    }

    private suspend fun blockTool(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val raw = invocation.arguments.firstOrNull()
            ?: return actionReply(context, AdminAction.GetSetting("block_tool_enabled"), "Blocktool-Status geladen.")
        val enabled = parseOnOff(raw)
            ?: return usage("Nutzung: ${prefix}blocktool on|off")
        return setSettings(
            context,
            mapOf("block_tool_enabled" to enabled.toString()),
            "✅ Modell-Blocktool ist ${if (enabled) "an" else "aus"}.",
        )
    }

    private suspend fun proactive(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val first = invocation.arguments.firstOrNull()?.lowercase(Locale.ROOT)
        if (first == null || first == "status" || first == "list") {
            return actionReply(context, AdminAction.ProactiveStatus, "Proaktiv-Status geladen.")
        }
        if (first == "global") {
            val level = invocation.arguments.getOrNull(1)?.toIntOrNull()
            if (level == null || level !in 0..10) {
                return reply("❌ Globaler Proaktiv-Level muss eine ganze Zahl von 0 bis 10 sein.")
            }
            return actionReply(
                context,
                AdminAction.SetGlobalProactiveLevel(level),
                "✅ Globaler Proaktiv-Level: $level",
            )
        }

        val target = normalizePhoneNumber(first)
            ?: return reply("❌ Keine gültige Nummer. Nutzung: ${prefix}proactive <Nummer> <0-10|off|clear>")
        val value = invocation.arguments.getOrNull(1)?.lowercase(Locale.ROOT)
        if (value == null) {
            return actionReply(context, AdminAction.GetProactiveOverride(target), "Proaktiv-Override für $target geladen.")
        }
        if (value in setOf("clear", "reset", "default")) {
            return actionReply(context, AdminAction.ClearProactiveOverride(target), "✅ Override für $target entfernt.")
        }
        val level = if (value in setOf("off", "aus")) 0 else value.toIntOrNull()
        if (level == null || level !in 0..10) {
            return reply("❌ Kontakt-Level muss eine ganze Zahl von 0 bis 10 sein; Dezimalwerte sind nicht erlaubt.")
        }
        return actionReply(
            context,
            AdminAction.SetProactiveOverride(target, level),
            "✅ Proaktiv-Level für $target: $level",
        )
    }

    private suspend fun safety(
        invocation: CommandInvocation,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val sub = invocation.arguments.firstOrNull()?.lowercase(Locale.ROOT) ?: "status"
        return when (sub) {
            "status", "state" -> actionReply(context, AdminAction.SafetyStatus, "Safety-Status geladen.")
            "events" -> actionReply(context, AdminAction.SafetyEvents(), "Keine Safety-Events.")
            "refresh", "check" -> actionReply(context, AdminAction.RefreshSafety, "✅ Safety-Refresh abgeschlossen.")
            "ack" -> {
                val id = invocation.arguments.getOrNull(1)?.toLongOrNull()
                if (id == null || id <= 0) usage("Nutzung: ${prefix}safety ack <Lock-ID>")
                else actionReply(context, AdminAction.AcknowledgeSafetyLock(id), "✅ Safety-Lock #$id bestätigt.")
            }
            "clear" -> {
                val id = invocation.arguments.getOrNull(1)?.toLongOrNull()
                if (id == null || id <= 0) usage("Nutzung: ${prefix}safety clear <Lock-ID>")
                else actionReply(context, AdminAction.ClearSafetyLock(id), "✅ Safety-Lock #$id gelöscht.")
            }
            "hold" -> {
                val duration = parseDurationMs(invocation.arguments.getOrNull(1))
                val reason = invocation.arguments.drop(2).joinToString(" ").trim()
                if (duration == null || reason.isEmpty()) {
                    usage("Nutzung: ${prefix}safety hold <30m|2h|1d> <Grund>")
                } else {
                    actionReply(
                        context,
                        AdminAction.HoldSafety(duration, reason),
                        "✅ Safety-Hold für ${formatDuration(duration)} gesetzt: $reason",
                    )
                }
            }
            else -> usage("Nutzung: ${prefix}safety [status|events|refresh|ack|clear|hold]")
        }
    }

    private suspend fun clearAll(
        invocation: CommandInvocation,
        request: CommandRequest,
        context: AdminContext,
        prefix: String,
    ): CommandExecutionResult {
        val raw = invocation.arguments.firstOrNull()?.trim()
        if (raw != null && invocation.arguments.size == 1 && SIX_DIGITS.matches(raw)) {
            return when (val confirmation = wipeChallenges.confirm(request.senderId, request.chatId, raw)) {
                WipeConfirmation.MissingOrExpired -> {
                    reply("❌ Kein passender Löschvorgang offen, falscher Code oder Code abgelaufen.")
                }
                is WipeConfirmation.Confirmed -> {
                    actionReply(
                        context,
                        AdminAction.Wipe(confirmation.target),
                        "🧨 ${confirmation.target.label()} wurden unwiderruflich gelöscht.",
                    )
                }
            }
        }

        val target = when {
            raw == null -> currentPersonaTarget(context)
                ?: return reply("❌ Aktive Persona konnte nicht bestimmt werden.")
            raw.equals("all", true) || raw.equals("alle", true) || raw == "*" -> WipeTarget.All
            else -> {
                val key = raw.lowercase(Locale.ROOT)
                if (!PERSONA_KEY.matches(key)) return reply("❌ Ungültiger Persona-Key.")
                if (!personaExists(context, key)) return reply("❌ Persona „$key“ wurde nicht gefunden.")
                WipeTarget.Persona(key)
            }
        }
        val challenge = wipeChallenges.issue(request.senderId, request.chatId, target)
        return reply(
            buildString {
                appendLine("⚠️ *Unwiderruflicher Löschvorgang*")
                appendLine("Ziel: ${target.label()}")
                appendLine("Gelöscht werden Verlauf, Chat-/Global-Memory, Proaktiv-State und Bild-Dedup.")
                appendLine("Settings, Persona-Definitionen und Allow-/Adminlisten bleiben erhalten.")
                appendLine()
                append("Binnen 2 Minuten bestätigen mit: *${prefix}clearall ${challenge.code}*")
            },
        )
    }

    private suspend fun currentPersonaTarget(context: AdminContext): WipeTarget.Persona? {
        val personas = call(context, AdminAction.ListPersonas)
        val key = ((personas as? AdminResult.Success)?.payload as? AdminPayload.Personas)?.activeKey
            ?: getSettingSnapshot(context, "personality")?.value
            ?: return null
        val normalized = key.lowercase(Locale.ROOT)
        return normalized.takeIf { PERSONA_KEY.matches(it) }?.let { WipeTarget.Persona(it) }
    }

    private suspend fun personaExists(context: AdminContext, key: String): Boolean {
        val result = call(context, AdminAction.ListPersonas)
        val payload = (result as? AdminResult.Success)?.payload as? AdminPayload.Personas
        return payload?.entries?.any { it.key.equals(key, ignoreCase = true) } ?: false
    }

    private fun resolvePersonaTarget(raw: String?, currentChatId: String): String? {
        if (raw == null || raw.equals("me", true) || raw.equals("current", true) || raw.equals("hier", true)) {
            return currentChatId
        }
        return normalizeExplicitChatTarget(raw)
    }

    private suspend fun filteredSettings(
        context: AdminContext,
        title: String,
        keys: Set<String>,
    ): CommandExecutionResult {
        val result = call(context, AdminAction.ListSettings)
        if (result is AdminResult.Success && result.payload is AdminPayload.Settings) {
            return reply(formatSettings(title, result.payload.settings.filter { it.key in keys }))
        }
        return renderResult(result, "$title geladen.")
    }

    private suspend fun setSettings(
        context: AdminContext,
        changes: Map<String, String>,
        success: String,
    ): CommandExecutionResult {
        return actionReply(context, AdminAction.SetSettings(changes), success)
    }

    private suspend fun getSettingSnapshot(context: AdminContext, key: String): SettingSnapshot? {
        val result = call(context, AdminAction.GetSetting(key))
        return ((result as? AdminResult.Success)?.payload as? AdminPayload.Setting)?.setting
    }

    private suspend fun actionReply(
        context: AdminContext,
        action: AdminAction,
        successFallback: String,
    ): CommandExecutionResult = renderResult(call(context, action), successFallback)

    private suspend fun call(context: AdminContext, action: AdminAction): AdminResult {
        return actions.execute(AdminRequest(context, action))
    }

    private fun renderResult(result: AdminResult, successFallback: String): CommandExecutionResult = when (result) {
        is AdminResult.Success -> reply(renderPayload(result.payload, successFallback))
        is AdminResult.Invalid -> {
            val field = result.field?.takeIf(String::isNotBlank)?.let { " für „$it“" }.orEmpty()
            reply("❌ Ungültiger Wert$field: ${result.reason}")
        }
        is AdminResult.NotFound -> reply("❌ Nicht gefunden: ${result.subject}")
        is AdminResult.Denied -> reply("⛔ Aktion abgelehnt: ${result.reason}")
        is AdminResult.Failure -> reply("❌ Aktion fehlgeschlagen: ${result.reason}")
    }

    private fun renderPayload(payload: AdminPayload, fallback: String): String = when (payload) {
        AdminPayload.Empty -> fallback
        is AdminPayload.Text -> payload.value.ifBlank { fallback }
        is AdminPayload.Mood -> {
            if (!payload.enabled) {
                "🎭 Mood ist aus (immer neutral)."
            } else {
                "🎭 Mood ist an. Aktuelle Grundstimmung: ${payload.name ?: "neutral"}."
            }
        }
        is AdminPayload.HelpHeader -> payload.introduction.ifBlank { fallback }
        is AdminPayload.Setting -> {
            val setting = payload.setting
            "*${setting.key}* = ${setting.value}\nDefault: ${setting.defaultValue}\n${setting.description}"
        }
        is AdminPayload.Settings -> formatSettings("⚙️ *Globale Settings*", payload.settings)
        is AdminPayload.SettingsChanged -> {
            if (payload.normalizedValues.isEmpty()) {
                fallback
            } else {
                payload.normalizedValues.entries.joinToString(
                    prefix = "✅ ",
                    separator = "\n",
                ) { (key, value) -> "*$key* = $value" }
            }
        }
        is AdminPayload.AccessEntries -> {
            val label = when (payload.list) {
                AccessList.ALLOW -> "Allowlist"
                AccessList.GROUP_ALLOW -> "Gruppen-Allowlist"
                AccessList.ADMIN -> "Adminliste"
            }
            if (payload.entries.isEmpty()) "$label ist leer." else "$label (${payload.entries.size}):\n${payload.entries.joinToString("\n") { "• $it" }}"
        }
        is AdminPayload.AccessChanged -> "$fallback\nGesamt: ${payload.total}; geändert: ${payload.changed}."
        is AdminPayload.SecretStatus -> {
            "🔑 API-Key: ${payload.maskedValue()} (Quelle: ${payload.source})"
        }
        is AdminPayload.Personas -> {
            val entries = payload.entries.joinToString("\n") {
                val active = if (it.key == payload.activeKey) " ⭐" else ""
                "• *${it.key}* — ${it.displayName}$active"
            }.ifBlank { "_keine Personas_" }
            val assignments = payload.assignments.joinToString("\n") { "• ${it.chatId} → ${it.personaKey}" }
            buildString {
                append("*Personas*\n")
                append(entries)
                if (assignments.isNotBlank()) {
                    append("\n\n*Zuweisungen*\n")
                    append(assignments)
                }
            }
        }
        is AdminPayload.PersonaData ->
            payload.entries.joinToString("\n") { "• ${it.displayName} (${it.key})" }
                .ifBlank { "No persona has chat or memory data." }
        is AdminPayload.Voices -> {
            if (payload.entries.isEmpty()) "Keine Stimmen verfügbar."
            else payload.entries.joinToString(prefix = "*Stimmen*\n", separator = "\n") { "• *${it.name}* — ${it.description}" }
        }
        is AdminPayload.ImageLocation -> "Bilderbereich für ${payload.personaKey}:\n${payload.location}"
        is AdminPayload.ImageAssets -> {
            if (payload.entries.isEmpty()) {
                "Keine Bilder für ${payload.personaKey} freigegeben. Import erfolgt ausschließlich in der App."
            } else {
                payload.entries.joinToString(
                    prefix = "*Freigegebene Bilder für ${payload.personaKey}*\n",
                    separator = "\n",
                ) { "• ${it.displayName} (${it.assetId})" }
            }
        }
        is AdminPayload.ImageReferences -> {
            if (payload.entries.isEmpty()) {
                "Keine Character-Referenzen für ${payload.personaKey}."
            } else {
                payload.entries.joinToString(
                    prefix = "Character-Referenzen für ${payload.personaKey}:\n",
                    separator = "\n",
                ) { "• ${it.displayName}" }
            }
        }
        is AdminPayload.ProfilePictures -> {
            if (payload.entries.isEmpty()) {
                "Keine Profilbilder für ${payload.personaKey}."
            } else {
                payload.entries.joinToString(
                    prefix = "Profilbilder für ${payload.personaKey}:\n",
                    separator = "\n",
                ) { "${if (it.assetId == payload.live) "●" else "•"} ${it.displayName}" }
            }
        }
        is AdminPayload.ImageSent ->
            "✅ Bild wurde an ${payload.targetChatId} gesendet."
        is AdminPayload.Blocks -> {
            if (payload.entries.isEmpty()) "Blockliste ist leer."
            else payload.entries.joinToString(prefix = "*Blockliste*\n", separator = "\n") {
                val source = if (it.source == BlockSource.REMOTE) "WhatsApp bestätigt" else "lokale Sperre"
                "• ${it.number} ($source)${it.reason.takeIf(String::isNotBlank)?.let { reason -> " — $reason" }.orEmpty()}"
            }
        }
        is AdminPayload.BlockChanged -> {
            val bridge = if (payload.whatsappConfirmed) "" else "\n⚠️ WhatsApp hat nur den lokalen Soft-Block bestätigt."
            "$fallback$bridge"
        }
        is AdminPayload.SafetyState ->
            buildString {
                append(payload.summary)
                if (payload.activeLocks.isNotEmpty()) {
                    append("\n")
                    append(payload.activeLocks.joinToString("\n") { "• #${it.id} ${it.label}" })
                }
            }
        is AdminPayload.ProactiveOverride -> {
            payload.level?.let { "${payload.target}: Level $it" }
                ?: "${payload.target}: kein Override, global ${payload.globalLevel}"
        }
        is AdminPayload.ProactiveContacts -> {
            if (payload.contacts.isEmpty()) {
                "Noch keine Kontakte bekannt."
            } else {
                payload.contacts.joinToString(
                    prefix = "*Proaktivität pro Kontakt* (global ${payload.globalLevel})\n",
                    separator = "\n",
                ) { contact ->
                    val name = contact.displayName?.takeIf(String::isNotBlank) ?: contact.chatId
                    val level =
                        if (contact.overridden) "${contact.level}" else "global (${contact.level})"
                    "• $name — $level${if (contact.blocked) " ⛔" else ""}"
                }
            }
        }
        is AdminPayload.WriteContactOutcome -> {
            val name = payload.displayName?.takeIf(String::isNotBlank) ?: payload.target
            when {
                payload.sent -> "✅ Nachricht an $name ist raus."
                // Step one of the confirmation: the code has to come back with the next call.
                payload.confirmation != null ->
                    "⚠️ Wirklich an $name schreiben? Bestätige mit `${payload.confirmation}`."
                else -> "❌ Nicht gesendet an $name: ${payload.detail ?: "unbekannter Grund"}"
            }
        }
        is AdminPayload.WipeSummary -> {
            "🧨 ${payload.target.label()} gelöscht: ${payload.affectedThreads} Thread(s)."
        }
        // Memory documents are only browsable in the local app; chat replies stay metadata-only.
        is AdminPayload.Memories -> {
            if (payload.entries.isEmpty()) {
                "🧠 Noch keine Memory-Dateien."
            } else {
                payload.entries.joinToString(prefix = "🧠 *Memory*\n", separator = "\n") {
                    "${it.title} · rev ${it.revision} · ${it.characters} Zeichen"
                }
            }
        }
        is AdminPayload.MemoryDocument -> {
            "🧠 ${payload.summary.title} · rev ${payload.summary.revision} · " +
                "${payload.summary.characters} Zeichen"
        }
        is AdminPayload.MemoryWritten -> "🧠 ${payload.detail}"
    }

    private fun formatSettings(title: String, settings: List<SettingSnapshot>): String {
        if (settings.isEmpty()) return "$title\n_keine Werte_"
        return settings.sortedBy(SettingSnapshot::key).joinToString(
            prefix = "$title\n",
            separator = "\n",
        ) {
            val marker = if (it.overridden) " (überschrieben)" else ""
            "• *${it.key}* = ${it.value}$marker\n  Default: ${it.defaultValue} — ${it.description}"
        }
    }

    private fun reply(message: String): CommandExecutionResult.Replied {
        val normalized = message.trim()
        if (normalized.length <= MAX_REPLY_CHARS) {
            return CommandExecutionResult.Replied(normalized)
        }

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            val hardEnd = (start + MAX_REPLY_CHARS).coerceAtMost(normalized.length)
            var end = if (hardEnd == normalized.length) {
                hardEnd
            } else {
                normalized.lastIndexOf('\n', hardEnd).takeIf { it > start } ?: hardEnd
            }
            if (end < normalized.length &&
                end > start &&
                Character.isHighSurrogate(normalized[end - 1]) &&
                Character.isLowSurrogate(normalized[end])
            ) {
                end--
            }
            normalized.substring(start, end).trim().takeIf(String::isNotBlank)?.let(chunks::add)
            start = end
            while (start < normalized.length && normalized[start].isWhitespace()) start++
        }
        return CommandExecutionResult.Replied(chunks)
    }

    private fun usage(message: String): CommandExecutionResult = reply(message)

    private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1_000
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}min"
        val hours = minutes / 60
        return if (hours < 24) "${hours}h ${minutes % 60}min" else "${hours / 24}d ${hours % 24}h"
    }

    companion object {
        private const val MAX_REPLY_CHARS = 3_500
        private val SIX_DIGITS = Regex("^\\d{6}$")
        private val PERSONA_KEY = Regex("^[a-z0-9_-]{2,40}$")
        private val RESERVED_PERSONA_KEYS = setOf(
            "human",
            "female",
            "male",
            "goth",
            "sam",
            "default",
            "assistant",
            "homie",
            "sarkastisch",
            "flirty",
            "coach",
            "nerd",
            "philosoph",
            "formell",
            "custom",
        )
    }
}
