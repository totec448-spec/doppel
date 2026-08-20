package de.totec.doppel.ai

import kotlinx.coroutines.CancellationException

/**
 * What one model declares it can read.
 *
 * `null` means "cannot tell right now" — the catalogue was unreachable, or the model is not listed
 * on it — and is deliberately not the same as "reads nothing". A caller that cannot tell must try
 * the request it would have made anyway; only a *declared* absence is allowed to skip a call.
 */
fun interface MediaModelCapabilitySource {
    suspend fun capabilitiesOf(model: String): Set<ModelCapability>?

    companion object {
        /** For call sites that deliberately send whatever they were given. */
        val UNKNOWN = MediaModelCapabilitySource { null }
    }
}

/**
 * Answers from the ordinary model catalogue.
 *
 * No request of its own: `GET /models` already carries `architecture.input_modalities` for every
 * model, so the media pipeline reads the same cached list the settings screen fills its model
 * pickers from. Sharing one [ModelCatalogCache] across the app is what keeps this free — the
 * catalogue is fetched once per TTL for both.
 */
class CatalogMediaModelCapabilities(
    private val catalog: ModelCatalogClient,
) : MediaModelCapabilitySource {
    override suspend fun capabilitiesOf(model: String): Set<ModelCapability>? {
        val id = model.trim()
        if (id.isEmpty()) return null
        return try {
            catalog
                .getCatalog(forceRefresh = false)
                .models
                .firstOrNull { it.id == id }
                ?.capabilities
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
