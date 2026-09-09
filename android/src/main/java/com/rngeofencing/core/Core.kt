package com.rngeofencing.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * The owner of everything (§15.6).
 *
 * `Core` owns the geofences, the state machine, the store and the queue. The React
 * module — TurboModule or legacy — is a **read-and-forward adapter** over it,
 * constructed and destroyed freely, and never the owner of anything. That is not an
 * architectural preference, it is a correctness requirement: on Android the broadcast
 * receiver runs with no React instance at all when the app is killed, and if the
 * logic lived in `ReactContextBaseJavaModule` because "that is where `@ReactMethod`
 * lives", killed-app detection would stop working and the failure would look like
 * flaky hardware.
 *
 * Every public entry point here must be called **on [executor]**. It is a single
 * thread, and it is what serialises rotations (§4.5, invariant 2) and every
 * read-modify-write on the store (§9.4). Two overlapping rotations diffing against
 * the same OS registry produce a partial set — the single most likely source of a
 * "geofences silently stopped working" bug.
 */
object Core {

  /**
   * One serialised executor for the whole module. Not a pool — the serialisation
   * *is* the point.
   */
  val executor =
    Executors.newSingleThreadExecutor(
      ThreadFactory { runnable ->
        Thread(runnable, "rn-geofencing").apply { isDaemon = true }
      }
    )

  private var store: PrefsGeofenceStore? = null
  private var registry: PlatformRegistry? = null
  private var resolver: LocationResolver? = null
  private var gate: TransitionGate? = null
  private var engine: RotationEngine? = null

  @Volatile private var delivery: JsDelivery? = null

  /**
   * Whether the unconditional process-start re-arm has happened yet (§4.6,
   * invariant 9).
   */
  private var reArmedThisProcess = false

  // -------------------------------------------------------------------------
  // Lifecycle
  // -------------------------------------------------------------------------

  /**
   * Builds the core and loads the store. Idempotent, cheap, and safe from any entry
   * point — the receiver, the boot receiver, the headless service or the module.
   *
   * Takes an application [Context], never a `ReactApplicationContext`: §11's
   * structural rule is enforced by the signature.
   */
  fun initialize(context: Context, durableWrites: Boolean = false) {
    val appContext = context.applicationContext
    if (store == null) {
      val created = PrefsGeofenceStore(appContext)
      created.load()
      val createdGate = TransitionGate(created)
      val createdRegistry = PlatformRegistry(appContext)
      store = created
      gate = createdGate
      registry = createdRegistry
      resolver = LocationResolver(appContext)
      engine = RotationEngine(created, createdRegistry, createdGate)
    }
    // A receiver or headless entry raises durability for the rest of the process; it
    // never lowers it back (§9.3).
    if (durableWrites) {
      store?.durableWrites = true
    }
  }

  fun attachDelivery(value: JsDelivery) {
    delivery = value
  }

  /**
   * Called from the module's `invalidate()`.
   *
   * Detaches the adapter — it never shuts the core down. The core outlives the module
   * by design (§15.6).
   */
  fun detachDelivery() {
    delivery = null
  }

  /** Flushes the queue into JS the moment a listener appears (§15.4). */
  fun onJsStartedObserving(context: Context) {
    executor.execute {
      initialize(context)
      drainQueueToJs()
    }
  }

  // -------------------------------------------------------------------------
  // Public API surface (§8.1) — all of it runs on `executor`
  // -------------------------------------------------------------------------

  fun ready(context: Context, config: Config): State {
    initialize(context)
    val store = requireStore()
    store.config = config
    Logger.d("ready: $config")

    // Every process start re-arms the whole set unconditionally, whether or not
    // `start()` is called again: a reboot, a force-stop or a Play Services reset
    // clears the OS side while our flags still say `active = true` (§4.6,
    // invariant 9).
    if (store.meta.enabled) {
      rotate(context, RotationTrigger.READY, centerHint = null)
    }
    return getState(context)
  }

  fun start(context: Context) {
    initialize(context)
    val store = requireStore()
    requireAvailable()
    store.meta = store.meta.copy(enabled = true)
    rotate(context, RotationTrigger.START, centerHint = null)
  }

  fun stop(context: Context) {
    initialize(context)
    val store = requireStore()
    store.meta = store.meta.copy(enabled = false, boundaryRadius = null)
    // Tears down every region this app registered, boundary included. The store is
    // kept, so a later `start()` re-arms without the host re-adding anything.
    requireRegistry().removeAll()
    store.setActive(emptySet())
    Logger.d("stop: all regions disarmed, ${store.count()} geofence(s) kept in the store")
  }

  fun addGeofences(context: Context, records: List<GeofenceRecord>) {
    initialize(context)
    val store = requireStore()
    if (records.isEmpty()) return // must not reject (§8.4)

    val config = store.config
    val normalised = records.map { validate(it, config) }

    val newIds = normalised.map { it.id }.toSet()
    val existing = store.all().map { it.id }.toSet()
    val resultingCount = (existing + newIds).size
    if (resultingCount > MAX_GEOFENCES) {
      throw GeofencingException(
        "E_LIMIT_EXCEEDED",
        "adding ${normalised.size} geofence(s) would bring the store to " +
          "$resultingCount, above the cap of $MAX_GEOFENCES (§9.5)",
      )
    }

    store.upsert(normalised)
    if (store.meta.enabled) {
      rotate(context, RotationTrigger.GEOFENCES_CHANGED, centerHint = null)
    }
  }

  fun removeGeofences(context: Context, ids: List<String>?) {
    initialize(context)
    val store = requireStore()
    val targets = ids ?: store.all().map { it.id }
    if (targets.isEmpty()) return // must not reject (§8.4)

    // Disarm before forgetting: a region removed from the store but left armed at OS
    // level would keep firing for an id we can no longer resolve.
    val armed = targets.filter { store.get(it)?.active == true }
    if (armed.isNotEmpty()) {
      requireRegistry().removeRegions(armed)
    }
    store.delete(targets)

    if (store.meta.enabled) {
      rotate(context, RotationTrigger.GEOFENCES_CHANGED, centerHint = null)
    }
  }

  fun getGeofences(context: Context): List<GeofenceRecord> {
    initialize(context)
    // BOUNDARY_ID is an implementation detail and never escapes a public result
    // (§4.2, invariant 10).
    return requireStore().all().filter { it.id != BOUNDARY_ID }
  }

  fun getActiveIds(context: Context): List<String> {
    initialize(context)
    return requireStore().activeIds().filter { it != BOUNDARY_ID }.sorted()
  }

  fun flushQueue(context: Context): List<GeofenceEvent> {
    initialize(context)
    return requireStore().drainQueue()
  }

  fun getState(context: Context): State {
    initialize(context)
    val store = requireStore()
    val resolver = requireResolver()
    val registry = requireRegistry()
    val meta = store.meta

    return State(
      enabled = meta.enabled,
      available = registry.isAvailable(),
      authorization = Permissions.authorization(context),
      accuracyAuthorization = Permissions.accuracyAuthorization(context),
      geofenceCount = store.all().count { it.id != BOUNDARY_ID },
      activeCount = store.activeIds().count { it != BOUNDARY_ID },
      batteryOptimized = isBatteryOptimized(context),
      locationServicesEnabled = resolver.locationServicesEnabled(),
      rotationCenterLatitude = meta.centerLatitude,
      rotationCenterLongitude = meta.centerLongitude,
      rotationCenterAt = meta.centerAt,
      boundaryRadius = meta.boundaryRadius,
      droppedEventCount = meta.droppedCount,
    )
  }

  // -------------------------------------------------------------------------
  // OS callbacks
  // -------------------------------------------------------------------------

  /**
   * Entry point for every geofence broadcast (§6.4).
   *
   * Order is fixed:
   * 1. A boundary EXIT triggers a rotation and **stops** — it is never emitted
   *    (invariant 10).
   * 2. Every other id passes through the transition gate (§5, invariant 6).
   * 3. Surviving events are persisted into the queue.
   * 4. They are delivered to JS, or the headless task is started, or they wait.
   */
  fun handleTransition(
    context: Context,
    ids: List<String>,
    action: GeofenceAction,
    location: LatLng?,
    accuracy: Double? = null,
  ) {
    initialize(context, durableWrites = true)

    val boundaryCrossed = ids.contains(BOUNDARY_ID) && action == GeofenceAction.EXIT
    val realIds = ids.filter { it != BOUNDARY_ID }

    if (boundaryCrossed) {
      Logger.d("boundary EXIT — rotating")
      rotate(context, RotationTrigger.BOUNDARY_EXIT, centerHint = location)
    }

    if (realIds.isEmpty()) return

    val events = requireGate().onPlatformTransition(realIds, action, location, accuracy)
    if (events.isEmpty()) return

    deliver(context, events)
  }

  /** `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` — the OS dropped everything (§6.6). */
  fun onBootCompleted(context: Context) {
    initialize(context, durableWrites = true)
    val store = requireStore()
    if (!store.meta.enabled) {
      Logger.d("boot: module is stopped, nothing to re-arm")
      return
    }
    // The flags still claim `active = true` but the OS registry is empty, so this
    // must be a forced full re-add, not a diff (§4.6, invariant 9).
    reArmedThisProcess = false
    rotate(context, RotationTrigger.BOOT, centerHint = null)
  }

  /** Provider turned back on — re-arm (§6.6). */
  fun onProvidersChanged(context: Context) {
    initialize(context, durableWrites = true)
    val store = requireStore()
    if (!store.meta.enabled) return
    if (!requireResolver().locationServicesEnabled()) {
      Logger.d("providers changed: location services off, waiting")
      return
    }
    reArmedThisProcess = false
    rotate(context, RotationTrigger.PROVIDERS_CHANGED, centerHint = null)
  }

  /** Cheap correction on foreground: the last known location is fresh (§4.5). */
  fun onAppForeground(context: Context) {
    initialize(context)
    if (!requireStore().meta.enabled) return
    rotate(context, RotationTrigger.FOREGROUND, centerHint = null)
  }

  // -------------------------------------------------------------------------
  // Rotation
  // -------------------------------------------------------------------------

  /**
   * Computes and applies the active set.
   *
   * Always called on [executor], so it is already serialised (invariant 2). Never
   * rotates on an unresolved centre (invariant 4).
   */
  fun rotate(context: Context, trigger: RotationTrigger, centerHint: LatLng?) {
    val store = requireStore()
    val engine = requireEngine()

    if (!requireRegistry().isAvailable()) {
      Logger.w("rotate($trigger): Play Services unavailable, module is inert")
      return
    }

    val center = requireResolver().resolve(centerHint)
    if (center == null) {
      // Keeping the stale set is deliberate: a rotation around a bogus centre arms
      // the wrong regions everywhere (invariant 4). This resolves, it does not throw
      // (§8.4).
      Logger.w("rotate($trigger): no centre, keeping the current set")
      return
    }

    val forceReAddAll = !reArmedThisProcess
    reArmedThisProcess = true

    val computed = engine.computeActiveSet(center)
    Logger.d(
      "rotate($trigger): ${computed.active.size} of ${store.count()} geofence(s) selected"
    )

    val result =
      engine.applyActiveSet(
        computed.active,
        computed.boundaryRadius,
        center,
        forceReAddAll = forceReAddAll,
      )

    if (result.syntheticEvents.isNotEmpty()) {
      deliver(context, result.syntheticEvents)
    }

    if (result.on.isNotEmpty() || result.off.isNotEmpty()) {
      delivery?.takeIf { it.isObserving() }?.emitGeofencesChange(result.on, result.off)
    }
  }

  // -------------------------------------------------------------------------
  // Delivery (§6.5)
  // -------------------------------------------------------------------------

  private fun deliver(context: Context, events: List<GeofenceEvent>) {
    val current = delivery

    if (current != null && current.isObserving()) {
      events.forEach { current.emitEvent(it) }
      Logger.d("delivered ${events.size} event(s) to JS")
      return
    }

    // Persist first, always. Whatever happens next — a headless task that times out,
    // a process frozen mid-flight — the crossing is already on disk (§9.3).
    requireStore().enqueue(events)

    if (current != null && current.hasReactInstance()) {
      // Alive but nobody is listening yet: the queue is flushed the moment a
      // listener appears, via onJsStartedObserving (§6.5).
      Logger.d("queued ${events.size} event(s): React instance alive, no JS listener")
      return
    }

    if (requireStore().config.enableHeadless) {
      startHeadless(context, events)
    } else {
      // The single biggest simplification available: no service, no notification, no
      // FOREGROUND_SERVICE_LOCATION permission. Events flush on next launch (§6.5).
      Logger.d("queued ${events.size} event(s): enableHeadless=false")
    }
  }

  private fun drainQueueToJs() {
    val current = delivery ?: return
    if (!current.isObserving()) return
    val drained = requireStore().drainQueue()
    if (drained.isEmpty()) return
    Logger.d("flushing ${drained.size} queued event(s) to JS")
    drained.forEach { current.emitEvent(it) }
  }

  /**
   * Starts the short-lived headless service (§6.5).
   *
   * Addressed by component name so `core/` keeps no compile-time edge into the React
   * Native-aware layer (invariant 1). A geofence broadcast puts the app on a
   * temporary power allowlist for ~10 seconds, which is what makes starting a
   * foreground service legal here even in Doze.
   */
  private fun startHeadless(context: Context, events: List<GeofenceEvent>) {
    try {
      val intent =
        Intent()
          .setComponent(ComponentName(context.packageName, HEADLESS_SERVICE_CLASS_NAME))
          .putExtras(headlessExtras(events))

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
      Logger.d("headless service started with ${events.size} event(s)")
    } catch (error: Throwable) {
      // A background-start restriction or a missing service declaration must not
      // lose the crossing — it is already queued above.
      Logger.w("headless service could not be started; events stay queued", error)
    }
  }

  /**
   * One bundle carrying every event of this broadcast as a JSON array.
   *
   * A single Play Services broadcast can name several triggering geofences, and
   * starting one service per event would be both slower and easier to lose.
   */
  private fun headlessExtras(events: List<GeofenceEvent>): Bundle {
    val array = JSONArray()
    events.forEach { array.put(it.toWirePayload()) }
    return Bundle().apply { putString("events", array.toString()) }
  }

  // -------------------------------------------------------------------------
  // Validation (§8.4)
  // -------------------------------------------------------------------------

  private fun validate(record: GeofenceRecord, config: Config): GeofenceRecord {
    if (record.id.isBlank()) {
      throw GeofencingException("E_INVALID_GEOFENCE", "identifier must not be empty")
    }
    if (record.id == BOUNDARY_ID) {
      throw GeofencingException(
        "E_INVALID_GEOFENCE",
        "identifier '$BOUNDARY_ID' is reserved by this module",
      )
    }
    if (!record.center.isValid) {
      throw GeofencingException(
        "E_INVALID_GEOFENCE",
        "'${record.id}' has a non-finite or out-of-range coordinate " +
          "(${record.latitude}, ${record.longitude})",
      )
    }
    if (!record.radius.isFinite() || record.radius <= 0.0) {
      throw GeofencingException(
        "E_INVALID_GEOFENCE",
        "'${record.id}' has radius ${record.radius}; it must be greater than zero",
      )
    }
    if (!record.notifyOnEntry && !record.notifyOnExit && !record.notifyOnDwell) {
      throw GeofencingException(
        "E_INVALID_GEOFENCE",
        "'${record.id}' has no transition enabled, so it could never fire",
      )
    }

    // Radii below the platform floor frequently never trigger, so clamp up and say
    // so rather than registering something that silently does nothing (§10).
    if (record.radius < config.minRadius) {
      Logger.w(
        "geofence '${record.id}' radius ${record.radius.toInt()}m is below the " +
          "reliable minimum; clamping up to ${config.minRadius.toInt()}m"
      )
      return record.copy(radius = config.minRadius)
    }
    return record
  }

  private fun requireAvailable() {
    if (!requireRegistry().isAvailable()) {
      throw GeofencingException(
        "E_UNAVAILABLE",
        "Google Play services geofencing is not available on this device",
      )
    }
  }

  /** App "Restricted" in battery settings means delivery may be dropped entirely (§10). */
  private fun isBatteryOptimized(context: Context): Boolean {
    val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return !manager.isIgnoringBatteryOptimizations(context.packageName)
  }

  // -------------------------------------------------------------------------

  private fun requireStore(): PrefsGeofenceStore =
    store ?: throw GeofencingException("E_NOT_READY", "the geofencing core is not initialized")

  private fun requireRegistry(): PlatformRegistry =
    registry ?: throw GeofencingException("E_NOT_READY", "the geofencing core is not initialized")

  private fun requireResolver(): LocationResolver =
    resolver ?: throw GeofencingException("E_NOT_READY", "the geofencing core is not initialized")

  private fun requireGate(): TransitionGate =
    gate ?: throw GeofencingException("E_NOT_READY", "the geofencing core is not initialized")

  private fun requireEngine(): RotationEngine =
    engine ?: throw GeofencingException("E_NOT_READY", "the geofencing core is not initialized")

  const val HEADLESS_TASK_NAME = "RNGeofenceHeadlessTask"
  private const val HEADLESS_SERVICE_CLASS_NAME = "com.rngeofencing.GeofenceHeadlessService"
}

/** The wire shape of [GeofenceEvent] — shared by the queue, the emitter and headless. */
fun GeofenceEvent.toWirePayload(): JSONObject {
  val json =
    JSONObject()
      .put("identifier", id)
      .put("action", action.wire)
      .put("timestamp", timestamp)
      .put("approximate", approximate)
      .put("synthetic", synthetic)
  latitude?.let { json.put("latitude", it) }
  longitude?.let { json.put("longitude", it) }
  accuracy?.let { json.put("accuracy", it) }
  extras?.let { json.put("extras", it) }
  return json
}
