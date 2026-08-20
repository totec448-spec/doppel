package de.totec.doppel.integration

import de.totec.doppel.ai.MissingApiKeyException
import de.totec.doppel.ai.OpenRouterHttpException
import de.totec.doppel.ai.OpenRouterProtocolException
import de.totec.doppel.engine.PlannedSideEffect
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Explains why an approved image could not be sent, and what to send instead.
 *
 * An unsendable image used to propagate out of the side-effect phase and abort the whole turn
 * before any bubble was dispatched, so one stale asset reference cost the user the entire reply and
 * the log said nothing beyond a stack trace. Both halves of the fix live here: a specific,
 * operator-actionable reason code, and the degraded action that keeps the turn visible.
 *
 * Kept out of the turn runner so the mapping can be exercised without an Android context.
 */
internal object ImageSendDiagnostics {
    /**
     * The asset store reports every validation failure as an [IllegalArgumentException], so the
     * message is the only discriminator available. Matching on it is unlovely, but "file missing"
     * and "wrong persona" need different fixes and must not collapse into one reason.
     */
    fun failureCode(failure: Throwable): String {
        val message = failure.message.orEmpty()
        return when {
            failure is FileNotFoundException -> "asset_file_missing"
            message.contains("already sent") -> "already_sent_here"
            message.contains("missing or damaged") -> "asset_file_missing"
            message.contains("was modified") -> "asset_hash_mismatch"
            message.contains("not approved") -> "asset_not_approved"
            message.contains("Invalid image ID") -> "invalid_asset_id"
            message.contains("different image size") -> "upload_size_mismatch"
            message.contains("different image hash") -> "upload_hash_mismatch"
            message.contains("Unknown image approval") ||
                message.contains("Invalid image approval") -> "asset_metadata_invalid"
            failure is IOException -> "upload_failed"
            else -> failure.javaClass.simpleName.lowercase().take(80)
        }
    }

    /**
     * Why a *generated* picture never came back, in a slug the model and the operator can both read.
     *
     * Distinct from [failureCode], which explains an already-approved asset that could not be sent.
     * A generation fails at the provider, so the interesting evidence is the HTTP status plus the
     * provider's own error code — `moderation_blocked` and friends. Losing that was what made every
     * failure look identical and undiagnosable.
     */
    fun generationFailureCode(failure: Throwable): String =
        when (failure) {
            is OpenRouterHttpException ->
                listOfNotNull(
                    failure.reasonCode.trim().takeIf {
                        it.isNotEmpty() && it != "http_error"
                    },
                    failure.providerErrorCode?.trim()?.takeIf(String::isNotEmpty)
                        ?: failure.providerErrorType?.trim()?.takeIf(String::isNotEmpty),
                    "http_${failure.statusCode}",
                ).first()
            is OpenRouterProtocolException -> failure.reasonCode
            is MissingApiKeyException -> "missing_api_key"
            is FileNotFoundException -> "generated_file_missing"
            is IOException -> "generation_request_failed"
            else -> failure.javaClass.simpleName.lowercase().take(80)
        }.lowercase()
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ') it else '_' }
            .joinToString("")
            .take(48)

    fun failureLabel(code: String): String =
        when (code) {
            "asset_file_missing" -> "image file is missing or damaged"
            "asset_hash_mismatch" -> "image file was modified after approval"
            "asset_not_approved" -> "image is not approved for this persona"
            "invalid_asset_id" -> "invalid image reference"
            "already_sent_here" -> "image was already sent to this chat"
            "asset_metadata_invalid" -> "approval data unreadable or outdated"
            "upload_size_mismatch" -> "bridge reported a different size"
            "upload_hash_mismatch" -> "bridge reported a different hash"
            "upload_failed" -> "upload to the bridge failed"
            "moderation_blocked" -> "the provider's safety filter refused this scene"
            "missing_api_key" -> "no OpenRouter API key is configured"
            "generation_request_failed" -> "the generation request never completed"
            "no_compatible_endpoint" -> "OpenRouter found no compatible image provider endpoint"
            "model_not_found" -> "OpenRouter does not recognize the selected image model"
            "unsupported_parameter" -> "the image model rejected a requested parameter"
            "invalid_request", "invalid_request_error", "bad_request" ->
                "OpenRouter rejected the image request as invalid"
            "image_generation_user_error" -> "the image request needs different input or settings"
            "http_400" -> "OpenRouter rejected the image request (HTTP 400)"
            "http_404" -> "OpenRouter could not route the selected image model (HTTP 404)"
            else -> "unknown error ($code)"
        }

    fun failureSummary(
        caption: String?,
        code: String,
    ): String =
        "Image could not be sent · ${failureLabel(code)}" +
            if (caption.isNullOrBlank()) "" else " · sending the caption as text"

    /**
     * What survives a failed image: the caption as plain text if there was one, otherwise nothing.
     * Returning `null` drops only this action — the rest of the turn still goes out.
     */
    fun degrade(
        action: PlannedSideEffect.UploadApprovedImage,
        code: String,
    ): PlannedSideEffect.VoiceTextFallback? =
        action.caption
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { caption ->
                PlannedSideEffect.VoiceTextFallback(
                    idempotencyKey = "${action.idempotencyKey}:caption-fallback",
                    text = caption,
                    reasonCode = code,
                )
            }
}
