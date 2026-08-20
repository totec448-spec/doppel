package de.totec.doppel.ai

internal fun testSettings(
    tools: ToolAccessSettings = ToolAccessSettings(),
    output: OutputSettings = OutputSettings(),
    verification: VerificationSettings = VerificationSettings(),
    preferStreaming: Boolean = true,
    toolLoopLimit: Int = 8,
): ResolvedTurnSettings = ResolvedTurnSettings(
    models = mapOf(
        ModelRole.MAIN to "test/main",
        ModelRole.MEDIA to "test/media",
        ModelRole.VERIFY to "test/verifier",
        ModelRole.TTS to "test/tts",
    ),
    baseInstructions = "Be helpful, concise, and honest.",
    output = output,
    tools = tools,
    verification = verification,
    preferStreaming = preferStreaming,
    toolLoopLimit = toolLoopLimit,
)

internal fun testTurn(message: String = "Hallo") = TurnContext(
    latestMessage = message,
    persona = PersonaContext(
        id = "human",
        displayName = "Alex",
        instructions = "Write like a trusted human contact.",
        traits = mapOf("playfulness" to 1, "suspicion" to -1),
    ),
)
