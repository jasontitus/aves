package deckers.thibault.aves.channel.calls

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import deckers.thibault.aves.AnalysisWorker
import deckers.thibault.aves.channel.calls.Coresult.Companion.safe
import deckers.thibault.aves.smartsearch.EmbedItem
import deckers.thibault.aves.smartsearch.ModelCatalog
import deckers.thibault.aves.smartsearch.ModelSpec
import deckers.thibault.aves.smartsearch.ResourceGate
import deckers.thibault.aves.smartsearch.ScoredId
import deckers.thibault.aves.smartsearch.SmartSearchEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

// Registered on both the UI and the analysis Flutter engines. All calls run off the main thread.
class SmartSearchHandler(private val context: Context) : MethodCallHandler {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = SmartSearchEngine.apply { init(context) }

    // identifies this handler (i.e. its Flutter engine) as owner of the indexing lease
    private val leaseOwner = nextLeaseOwner.incrementAndGet()

    // releases what the Dart side may not release when its Flutter engine is destroyed
    fun dispose() {
        ioScope.cancel()
        engine.releaseIndexing(leaseOwner)
    }

    // unlike `Coresult.safe`, also catches errors (e.g. out of memory, native library loading),
    // which would otherwise kill the process from this background scope
    private fun launchSafe(call: MethodCall, result: MethodChannel.Result, function: (MethodCall, MethodChannel.Result) -> Unit) {
        ioScope.launch {
            try {
                safe(call, result, function)
            } catch (t: Throwable) {
                Coresult(call, result).error("${call.method}-error", t.toString(), null)
            }
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getStatus" -> launchSafe(call, result, ::getStatus)
            "setActiveModel" -> launchSafe(call, result, ::setActiveModel)
            "download" -> launchSafe(call, result, ::download)
            "cancelDownload" -> launchSafe(call, result, ::cancelDownload)
            "deleteModel" -> launchSafe(call, result, ::deleteModel)
            "canIndex" -> launchSafe(call, result, ::canIndex)
            "acquireIndexing" -> launchSafe(call, result) { _, r -> r.success(engine.tryAcquireIndexing(leaseOwner)) }
            "releaseIndexing" -> launchSafe(call, result) { _, r ->
                engine.releaseIndexing(leaseOwner)
                r.success(null)
            }
            "startIndexingWork" -> launchSafe(call, result, ::startIndexingWork)
            "isQueryPending" -> launchSafe(call, result) { _, r -> r.success(engine.isQueryPending) }
            "generation" -> launchSafe(call, result, ::generation)
            "pending" -> launchSafe(call, result, ::pending)
            "embed" -> launchSafe(call, result, ::embed)
            "remove" -> launchSafe(call, result, ::remove)
            "retainOnly" -> launchSafe(call, result, ::retainOnly)
            "searchText" -> launchSafe(call, result, ::searchText)
            "searchSimilar" -> launchSafe(call, result, ::searchSimilar)
            else -> result.notImplemented()
        }
    }

    private fun readySpecOrError(result: MethodChannel.Result): ModelSpec? {
        val spec = engine.readySpec()
        if (spec == null) result.error("not-ready", "no installed model", null)
        return spec
    }

    private fun getStatus(@Suppress("unused") call: MethodCall, result: MethodChannel.Result) {
        val models = engine.models
        val downloads = ModelCatalog.all.associateWith { models.pollDownload(it) }
        engine.promotePendingIfInstalled()
        val active = engine.activeSpec
        val ready = engine.readySpec()
        val status = hashMapOf<String, Any?>(
            "canDownload" to models.canDownload,
            "activeModel" to active?.id,
            "pendingModel" to engine.pendingSpec?.id,
            "ready" to (ready != null),
            "lowMemory" to ResourceGate.isLowMemoryDevice(context),
            "defaultChargingOnly" to ResourceGate.defaultChargingOnly(context),
            "averageEmbedMillis" to engine.averageEmbedMillis,
            "models" to ModelCatalog.all.map { spec ->
                val download = downloads[spec]
                hashMapOf(
                    "id" to spec.id,
                    "totalSize" to spec.totalSize,
                    "supported" to ResourceGate.isSupported(context, spec),
                    "installed" to (models.isInstalled(spec) || models.verifySideloaded(spec)),
                    "downloadBytes" to download?.bytes,
                    "downloadState" to download?.state?.name?.lowercase(),
                )
            },
        )
        if (ready != null) {
            engine.withStore(ready) { store ->
                status["indexedCount"] = store.liveCount
                status["failedCount"] = store.failedCount
                status["indexBytes"] = store.fileSize
            }
        }
        result.success(status)
    }

    private fun specArg(call: MethodCall, result: MethodChannel.Result): ModelSpec? {
        val spec = ModelCatalog.byId(call.argument<String>("id"))
        if (spec == null) result.error("${call.method}-args", "unknown model", null)
        return spec
    }

    private fun setActiveModel(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("id")
        engine.selectModel(ModelCatalog.byId(id))
        result.success(null)
    }

    private fun download(call: MethodCall, result: MethodChannel.Result) {
        val spec = specArg(call, result) ?: return
        val unmeteredOnly = call.argument<Boolean>("unmeteredOnly") ?: true
        if (!ResourceGate.isSupported(context, spec)) {
            result.error("download-unsupported", "model not supported on this device", null)
            return
        }
        result.success(engine.models.startDownload(spec, unmeteredOnly).name.lowercase())
    }

    private fun cancelDownload(call: MethodCall, result: MethodChannel.Result) {
        val spec = specArg(call, result) ?: return
        engine.models.cancelDownload(spec)
        engine.clearPending(spec)
        result.success(null)
    }

    private fun deleteModel(call: MethodCall, result: MethodChannel.Result) {
        val spec = specArg(call, result) ?: return
        engine.deleteModel(spec)
        result.success(null)
    }

    private fun canIndex(call: MethodCall, result: MethodChannel.Result) {
        val chargingOnly = call.argument<Boolean>("chargingOnly") ?: false
        result.success(ResourceGate.check(context, chargingOnly)?.name?.lowercase())
    }

    private fun generation(@Suppress("unused") call: MethodCall, result: MethodChannel.Result) {
        val spec = readySpecOrError(result) ?: return
        result.success(engine.withStore(spec) { it.currentGeneration })
    }

    private fun pending(call: MethodCall, result: MethodChannel.Result) {
        val ids = call.argument<IntArray>("ids")
        val fingerprints = call.argument<LongArray>("fingerprints")
        if (ids == null || fingerprints == null || ids.size != fingerprints.size) {
            result.error("pending-args", "missing arguments", null)
            return
        }
        val spec = readySpecOrError(result) ?: return
        result.success(engine.withStore(spec) { it.pending(ids, fingerprints) })
    }

    private fun embed(call: MethodCall, result: MethodChannel.Result) {
        val rawItems = call.argument<List<Map<String, Any?>>>("items")
        val startGeneration = call.argument<Number>("generation")?.toLong()
        val threads = call.argument<Int>("threads") ?: 1
        val background = call.argument<Boolean>("background") ?: true
        if (rawItems == null || startGeneration == null) {
            result.error("embed-args", "missing arguments", null)
            return
        }
        val spec = readySpecOrError(result) ?: return
        val items = rawItems.mapNotNull(::toEmbedItem)
        try {
            result.success(engine.embedBatch(spec, leaseOwner, items, startGeneration, threads, background))
        } catch (e: SmartSearchEngine.LeaseLostException) {
            result.error("embed-lease-lost", e.message, null)
        }
    }

    private fun remove(call: MethodCall, result: MethodChannel.Result) {
        val ids = call.argument<IntArray>("ids")
        if (ids == null) {
            result.error("remove-args", "missing arguments", null)
            return
        }
        // removal applies to every model index, not only the active one
        ModelCatalog.all.filter { engine.hasIndex(it) }.forEach { spec ->
            engine.withStore(spec) { it.remove(ids) }
        }
        result.success(null)
    }

    private fun retainOnly(call: MethodCall, result: MethodChannel.Result) {
        val ids = call.argument<IntArray>("ids")
        if (ids == null) {
            result.error("retainOnly-args", "missing arguments", null)
            return
        }
        val spec = readySpecOrError(result) ?: return
        engine.withStore(spec) { it.retainOnly(ids) }
        result.success(null)
    }

    private fun searchText(call: MethodCall, result: MethodChannel.Result) {
        val query = call.argument<String>("query")
        val ids = call.argument<IntArray>("ids")
        val fingerprints = call.argument<LongArray>("fingerprints")
        val maxResults = call.argument<Int>("maxResults") ?: DEFAULT_MAX_RESULTS
        if (query == null || ids == null || fingerprints == null || ids.size != fingerprints.size) {
            result.error("searchText-args", "missing arguments", null)
            return
        }
        val spec = readySpecOrError(result) ?: return
        result.success(toResultMap(engine.searchText(spec, query, ids, fingerprints, maxResults, queryThreads())))
    }

    private fun searchSimilar(call: MethodCall, result: MethodChannel.Result) {
        val item = call.argument<Map<String, Any?>>("item")?.let(::toEmbedItem)
        val ids = call.argument<IntArray>("ids")
        val fingerprints = call.argument<LongArray>("fingerprints")
        val maxResults = call.argument<Int>("maxResults") ?: DEFAULT_MAX_RESULTS
        if (item == null || ids == null || fingerprints == null || ids.size != fingerprints.size) {
            result.error("searchSimilar-args", "missing arguments", null)
            return
        }
        val spec = readySpecOrError(result) ?: return
        val results = engine.searchSimilar(spec, item, ids, fingerprints, maxResults, queryThreads())
        if (results == null) {
            result.error("searchSimilar-embed", "failed to embed item", null)
            return
        }
        result.success(toResultMap(results))
    }

    // Indexing runs in its own background work, separate from analysis,
    // either now or when the device is charging (e.g. overnight).
    private fun startIndexingWork(call: MethodCall, result: MethodChannel.Result) {
        val whenCharging = call.argument<Boolean>("whenCharging") ?: false
        // from Android 12 (API 31), foreground services cannot be started from the background
        if (whenCharging && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result.success(false)
            return
        }
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>().apply {
            setInputData(workDataOf(AnalysisWorker.KEY_SMART_SEARCH_ONLY to true))
            if (whenCharging) {
                setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            }
        }.build()
        WorkManager.getInstance(context).beginUniqueWork(
            if (whenCharging) INDEXING_WHEN_CHARGING_WORK_NAME else INDEXING_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        ).enqueue()
        result.success(true)
    }

    private fun queryThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return if (ResourceGate.isLowMemoryDevice(context)) minOf(2, cores) else minOf(4, cores)
    }

    private fun toResultMap(results: List<ScoredId>) = hashMapOf(
        "ids" to IntArray(results.size) { results[it].id },
        "scores" to FloatArray(results.size) { results[it].score },
    )

    private fun toEmbedItem(map: Map<String, Any?>): EmbedItem? {
        val id = (map["id"] as? Number)?.toInt() ?: return null
        val fingerprint = (map["fingerprint"] as? Number)?.toLong() ?: return null
        val uri = map["uri"] as? String ?: return null
        val mimeType = map["mimeType"] as? String ?: return null
        if (id <= 0) return null
        return EmbedItem(
            id = id,
            fingerprint = fingerprint,
            uri = uri,
            mimeType = mimeType,
            rotationDegrees = (map["rotationDegrees"] as? Number)?.toInt() ?: 0,
            isFlipped = map["isFlipped"] as? Boolean ?: false,
        )
    }

    companion object {
        const val CHANNEL = "deckers.thibault/aves/smart_search"
        private const val DEFAULT_MAX_RESULTS = 500
        private const val INDEXING_WORK_NAME = "smart_search_indexing_work"
        private const val INDEXING_WHEN_CHARGING_WORK_NAME = "smart_search_indexing_when_charging_work"
        private val nextLeaseOwner = AtomicLong(0)
    }
}
