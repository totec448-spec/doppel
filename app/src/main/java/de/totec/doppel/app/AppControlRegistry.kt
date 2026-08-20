package de.totec.doppel.app

import de.totec.doppel.commands.AdminActions
import de.totec.doppel.settings.ModelRole
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A model returned by the live OpenRouter catalogue.
 *
 * [supportedRoles] is deliberately mandatory. The UI must not offer a model
 * for a role merely because its identifier exists in the provider catalogue.
 */
data class UiModelOption(
    val id: String,
    val label: String,
    val supportedRoles: Set<ModelRole>,
    val createdAtEpochSeconds: Long? = null,
    val reasoningEfforts: List<String>? = null,
    val reasoningDefaultEffort: String? = null,
    val reasoningMandatory: Boolean = false,
    val supportedVoices: List<String> = emptyList(),
    /** Token prices from OpenRouter; role-specific unit prices below take precedence in the UI. */
    val promptPricePerToken: Double? = null,
    val completionPricePerToken: Double? = null,
    val imagePricePerUnit: Double? = null,
    val imageOutputPricePerToken: Double? = null,
    val requestPrice: Double? = null,
    val transcriptionPricePerHour: Double? = null,
) {
    init {
        require(id.isNotBlank())
        require(label.isNotBlank())
        require(supportedRoles.isNotEmpty())
    }
}

fun interface ModelCatalogControl {
    /**
     * Forces a provider refresh. Implementations may return a previously
     * validated stale cache when offline, but must never invent model support.
     */
    suspend fun refreshModels(): List<UiModelOption>
}

data class AppRuntimeMetrics(
    val pendingChats: Int = 0,
    val processedToday: Int = 0,
    val sentToday: Int = 0,
    val accountName: String? = null,
    val accountJid: String? = null,
) {
    fun bounded(): AppRuntimeMetrics =
        copy(
            pendingChats = pendingChats.coerceAtLeast(0),
            processedToday = processedToday.coerceAtLeast(0),
            sentToday = sentToday.coerceAtLeast(0),
            accountName = accountName?.trim()?.take(300)?.takeIf(String::isNotEmpty),
            accountJid = accountJid?.trim()?.take(200)?.takeIf(String::isNotEmpty),
        )
}

/** The most recent outbound refusal, already resolved to the exact operator setting when possible. */
data class RuntimeLimitNotice(
    val reason: String,
    val detail: String,
    val settingKey: String? = null,
    val settingLabel: String? = null,
    val untilMs: Long? = null,
)

/**
 * What kind of problem an alert reports, and therefore how it behaves.
 *
 * One alert per kind is live at a time: a provider that fails four times in a row is one problem,
 * not four, and a stack of identical red rows buries the one that is different.
 */
enum class AlertKind {
    /** No API key, or one the provider rejected. Nothing works until it is fixed. */
    API_KEY,

    /** The provider answered with an error — the HTTP status, in as few words as it takes. */
    PROVIDER,

    /**
     * WhatsApp is not connected and is not going to reconnect on its own.
     *
     * Only the states that need a person: a scheduled sleep and an ordinary reconnect are the
     * link working as designed, and a red row for either would train the owner to ignore them.
     */
    LINK,
}

/**
 * Destinations on the settings page that are not settings.
 *
 * A key handed to the settings page is normally a setting's own key, but the two conditions the
 * owner is most likely to be sent here for have no setting row at all: the API key is a secret and
 * the WhatsApp link is a pairing panel, and both are reached through a shortcut instead. Naming a
 * schema key for them resolved to nothing, so the row looked tappable and did nothing. These are the
 * shortcut titles the settings page matches on, kept next to the alerts that point at them so the
 * two cannot drift apart silently.
 */
object SettingsTargets {
    const val LINK_WHATSAPP = "Link WhatsApp"
    const val OPENROUTER_API_KEY = "OpenRouter API key"
    const val ACTIVITY_LOG = "Activity log"
}

/**
 * A problem the owner should see once and can then wave away.
 *
 * Deliberately not the same thing as [RuntimeLimitNotice]. A limit is a rule that is still in
 * force, so dismissing it would be a lie — it is cleared by changing the setting behind it and
 * nowhere else. An alert is an event that already happened, so an X is the honest control: the
 * owner has read it, and it comes back by itself the moment the problem recurs differently.
 */
data class RuntimeAlert(
    val kind: AlertKind,
    val title: String,
    val detail: String,
    /**
     * Where the settings page should land: a setting key, or one of [SettingsTargets]. Null still
     * opens settings — the alert is worth acting on either way, and the overview it lands on is
     * where the runtime is started and stopped.
     */
    val settingKey: String? = null,
    /** Standing conditions disappear only when fixed and therefore cannot be hidden. */
    val dismissible: Boolean = true,
    val atMs: Long = 0L,
)

/**
 * Process-local integration seam shared by the Application, foreground host
 * and Compose controller.
 *
 * It owns no coroutine, timer or transport. A host may be replaced during
 * reconnect; compare-and-set unbinding prevents an old host from clearing a
 * newer binding.
 */
class AppControlRegistry {
    private val modelCatalog = AtomicReference<ModelCatalogControl?>(null)
    private val adminActions = AtomicReference<AdminActions?>(null)
    private val metricsRefresh = AtomicReference<(() -> Unit)?>(null)
    private val activityRevisionCounter = AtomicLong(0)
    private val mutableActivityRevision = MutableStateFlow(0L)
    private val mutableMetrics = MutableStateFlow(AppRuntimeMetrics())
    private val mutableLimitNotice = MutableStateFlow<RuntimeLimitNotice?>(null)
    private val mutableAlerts = MutableStateFlow<List<RuntimeAlert>>(emptyList())
    private val dismissedAlerts = ConcurrentHashMap<AlertKind, String>()

    val activityRevision: StateFlow<Long> = mutableActivityRevision.asStateFlow()
    val metrics: StateFlow<AppRuntimeMetrics> = mutableMetrics.asStateFlow()
    val limitNotice: StateFlow<RuntimeLimitNotice?> = mutableLimitNotice.asStateFlow()

    /** Every live alert, at most one per [AlertKind], in a stable order. */
    val alerts: StateFlow<List<RuntimeAlert>> = mutableAlerts.asStateFlow()

    fun bindModelCatalog(control: ModelCatalogControl) {
        modelCatalog.set(control)
    }

    fun currentModelCatalog(): ModelCatalogControl? = modelCatalog.get()

    fun bindAdminActions(actions: AdminActions) {
        adminActions.set(actions)
    }

    fun currentAdminActions(): AdminActions? = adminActions.get()

    /**
     * True while a UI actually collects [metrics]. Recomputing them is a database aggregate over
     * the day's traffic, so the runtime skips it whenever nobody can see the result.
     */
    val metricsObserved: Boolean
        get() = mutableMetrics.subscriptionCount.value > 0

    fun bindMetricsRefresh(refresh: () -> Unit) {
        metricsRefresh.set(refresh)
    }

    fun unbindMetricsRefresh(refresh: () -> Unit) {
        metricsRefresh.compareAndSet(refresh, null)
    }

    /**
     * Forces one recomputation even when [metricsObserved] is still false, so a UI that has just
     * attached does not display counters frozen from the last time it was open.
     */
    fun requestMetricsRefresh() {
        metricsRefresh.get()?.invoke()
    }

    /**
     * Called after a committed activity row. StateFlow conflation means bursts
     * cause at most one pending UI reload and cost nothing while no UI exists.
     */
    fun notifyActivityChanged() {
        mutableActivityRevision.value = activityRevisionCounter.incrementAndGet()
    }

    fun publishMetrics(metrics: AppRuntimeMetrics) {
        mutableMetrics.value = metrics.bounded()
    }

    fun publishLimit(notice: RuntimeLimitNotice?) {
        mutableLimitNotice.value = notice
    }

    /**
     * Shows [alert], replacing any older one of the same kind.
     *
     * A dismissed alert stays dismissed for as long as it says the same thing. The provider fails
     * on every retry of the same turn, so re-showing "OpenRouter 429" the second the owner closed
     * it would make the X useless; a different status is a different problem and shows again.
     */
    fun publishAlert(alert: RuntimeAlert) {
        if (dismissedAlerts[alert.kind] == alert.title) return
        mutableAlerts.update { current ->
            (current.filterNot { it.kind == alert.kind } + alert).sortedBy { it.kind.ordinal }
        }
    }

    /** Closes the alert of [kind] and remembers what it said, so the same one stays closed. */
    fun dismissAlert(kind: AlertKind) {
        mutableAlerts.update { current ->
            current.firstOrNull { it.kind == kind }?.let { dismissedAlerts[kind] = it.title }
            current.filterNot { it.kind == kind }
        }
    }

    /** Withdraws the alert of [kind] because the problem is gone, and re-arms it for next time. */
    fun clearAlert(kind: AlertKind) {
        dismissedAlerts.remove(kind)
        mutableAlerts.update { current -> current.filterNot { it.kind == kind } }
    }
}
