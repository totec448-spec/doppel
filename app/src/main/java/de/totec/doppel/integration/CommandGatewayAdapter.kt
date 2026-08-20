package de.totec.doppel.integration

import de.totec.doppel.commands.CommandExecutionResult
import de.totec.doppel.commands.CommandExecutor
import de.totec.doppel.commands.CommandRequest
import de.totec.doppel.commands.AdminActions
import de.totec.doppel.domain.IncomingEvent
import de.totec.doppel.engine.AdminCommandGateway
import de.totec.doppel.engine.CommandHandling
import de.totec.doppel.settings.AppSettingKeys
import de.totec.doppel.settings.SettingsRepository

class CommandGatewayAdapter(
    actions: AdminActions,
    private val settings: SettingsRepository,
) : AdminCommandGateway {
    private val executor =
        CommandExecutor(
            actions = actions,
            prefixProvider = {
                settings.snapshot().appText(AppSettingKeys.COMMAND_PREFIX)
            },
        )

    override suspend fun handle(
        event: IncomingEvent,
        isAdmin: Boolean,
    ): CommandHandling =
        when (
            val result =
                executor.execute(
                    CommandRequest(
                        text = event.text,
                        senderId = event.senderJid,
                        chatId = event.chatJid,
                        isGroup = event.isGroup,
                        isAdmin = isAdmin,
                    ),
                )
        ) {
            CommandExecutionResult.NotACommand -> CommandHandling.NotACommand
            is CommandExecutionResult.NotAuthorizedFallThrough,
            is CommandExecutionResult.UnknownCommandFallThrough,
            -> CommandHandling.FallThrough

            is CommandExecutionResult.Replied -> CommandHandling.Handled(result.messages)
        }
}
