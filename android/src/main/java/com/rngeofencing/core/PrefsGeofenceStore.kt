package com.rngeofencing.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * `SharedPreferences`-backed store (§9).
 *
 * The one rule that makes this safe: **one JSON blob per concern, under one key**
 * (§9.1, invariant 7). `SharedPreferences` rewrites and renames its whole file on
 * every commit, so a single key gives the same all-or-nothing guarantee a SQLite
 * transaction would. One key *per geofence* would throw that away — a process killed
 * mid-commit would leave a half-updated set, which is precisely the "geofences
 * silently stopped working" failure this design exists to avoid.
 *
 * Not multi-process safe, which is why nothing in this module may ever declare
 * `android:process` (§9.4, invariant 8).
 */
class PrefsGeofenceStore(context: Context) : GeofenceStore {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** The in-memory working copy. All reads come from here, at zero I/O cost (§9.3). */
  private val records = LinkedHashMap<String, GeofenceRecord>()
  private val queue = ArrayList<GeofenceEvent>()

  private var metaValue = RotationMeta()
  private var configValue = Config()
  private var loaded = false

  /**
   * Set when a blob carried a `"v"` this build does not understand. While it is set,
   * that blob is never rewritten from memory — the user's registered set is preserved
   * for a future migration instead of being silently overwritten (§9.2).
   */
  private var geofencesBlobQuarantined = false

  override var durableWrites: Boolean = false

  override var meta: RotationMeta
    get() = metaValue
    set(value) {
      metaValue = value
      writeMeta()
    }

  override var config: Config
    get() = configValue
    set(value) {
      configValue = value
      Logger.debug = value.debug
      writeConfig()
    }

  // -------------------------------------------------------------------------
  // Load
  // -------------------------------------------------------------------------

  override fun load() {
    if (loaded) return
    loaded = true

    readGeofences()
    readEvents()
    readMeta()
    readConfig()

    Logger.debug = configValue.debug
    Logger.d(
      "store loaded: ${records.size} geofences, ${queue.size} queued events, " +
        "enabled=${metaValue.enabled}"
    )
  }

  private fun readGeofences() {
    val raw = prefs.getString(KEY_GEOFENCES, null) ?: return
    try {
      val root = JSONObject(raw)
      if (!checkVersion(root, KEY_GEOFENCES, raw)) {
        geofencesBlobQuarantined = true
        return
      }
      val items = root.optJSONObject("items") ?: return
      for (id in items.keys()) {
        val item = items.optJSONObject(id) ?: continue
        records[id] = item.toRecord(id)
      }
    } catch (error: Throwable) {
      // Never crash the receiver on a corrupt blob (§9.2). Starting empty loses the
      // set; crashing loses the set *and* every future crossing.
      Logger.e("geofences blob unreadable, starting empty", error)
      quarantine(KEY_GEOFENCES, raw, "corrupt")
      geofencesBlobQuarantined = true
      records.clear()
    }
  }

  private fun readEvents() {
    val raw = prefs.getString(KEY_EVENTS, null) ?: return
    try {
      val root = JSONObject(raw)
      if (!checkVersion(root, KEY_EVENTS, raw)) return
      val items = root.optJSONArray("items") ?: return
      for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        item.toEvent()?.let { queue.add(it) }
      }
    } catch (error: Throwable) {
      Logger.e("events blob unreadable, starting empty", error)
      queue.clear()
    }
  }

  private fun readMeta() {
    val raw = prefs.getString(KEY_META, null) ?: return
    try {
      val root = JSONObject(raw)
      if (!checkVersion(root, KEY_META, raw)) return
      metaValue =
        RotationMeta(
          centerLatitude = root.optDoubleOrNull("centerLat"),
          centerLongitude = root.optDoubleOrNull("centerLng"),
          centerAt = root.optLongOrNull("centerAt"),
          boundaryRadius = root.optDoubleOrNull("boundaryRadius"),
          enabled = root.optBoolean("enabled", false),
          droppedCount = root.optInt("droppedCount", 0),
        )
    } catch (error: Throwable) {
      Logger.e("meta blob unreadable, starting empty", error)
    }
  }

  private fun readConfig() {
    val raw = prefs.getString(KEY_CONFIG, null) ?: return
    try {
      val root = JSONObject(raw)
      if (!checkVersion(root, KEY_CONFIG, raw)) return
      val defaults = Config()
      configValue =
        Config(
          proximityRadius = root.optDouble("proximityRadius", defaults.proximityRadius),
          initialTriggerEntry = root.optBoolean("initialTriggerEntry", defaults.initialTriggerEntry),
          notificationResponsiveness =
            root.optInt("notificationResponsiveness", defaults.notificationResponsiveness),
          useSignificantLocationChanges =
            root.optBoolean(
              "useSignificantLocationChanges",
              defaults.useSignificantLocationChanges,
            ),
          enableHeadless = root.optBoolean("enableHeadless", defaults.enableHeadless),
          minRadius = root.optDouble("minRadius", defaults.minRadius),
          debug = root.optBoolean("debug", defaults.debug),
        )
    } catch (error: Throwable) {
      Logger.e("config blob unreadable, using defaults", error)
    }
  }

  /**
   * An unknown `"v"` must neither crash nor be overwritten (§9.2). The raw string is
   * copied aside so a later migration still has it, and this build carries on empty.
   */
  private fun checkVersion(root: JSONObject, key: String, raw: String): Boolean {
    val version = root.optInt("v", -1)
    if (version == BLOB_VERSION) return true
    Logger.e(
      "blob '$key' has version $version, this build understands $BLOB_VERSION — " +
        "keeping the raw value aside and starting empty"
    )
    quarantine(key, raw, "v$version")
    return false
  }

  private fun quarantine(key: String, raw: String, reason: String) {
    val backupKey = "$key.$reason.backup"
    if (prefs.contains(backupKey)) return // already preserved; do not overwrite it
    prefs.edit().putString(backupKey, raw).apply()
  }

  // -------------------------------------------------------------------------
  // Reads (memory only)
  // -------------------------------------------------------------------------

  override fun all(): List<GeofenceRecord> = records.values.toList()

  override fun get(id: String): GeofenceRecord? = records[id]

  override fun count(): Int = records.size

  override fun activeIds(): Set<String> =
    records.values.filter { it.active }.map { it.id }.toSet()

  override fun queueSize(): Int = queue.size

  // -------------------------------------------------------------------------
  // Mutations (memory, then write-through — never write-behind, §9.3)
  // -------------------------------------------------------------------------

  override fun upsert(records: List<GeofenceRecord>) {
    if (records.isEmpty()) return
    records.forEach { record ->
      val existing = this.records[record.id]
      // An upsert of an existing geofence keeps its gate state and armed flag:
      // re-registering the same circle is not a reason to forget that the device is
      // already inside it (§5.1).
      this.records[record.id] =
        if (existing == null) {
          record
        } else {
          record.copy(
            state = existing.state,
            enteredAt = existing.enteredAt,
            dwellEmitted = existing.dwellEmitted,
            // If the OS-visible definition changed, the armed region is the *old*
            // one, so it must be re-added before this flag can be trusted (§4.4).
            active = existing.active && !record.definitionChanged(existing),
          )
        }
    }
    writeGeofences()
  }

  override fun delete(ids: List<String>) {
    if (ids.isEmpty()) return
    var changed = false
    ids.forEach { if (records.remove(it) != null) changed = true }
    if (changed) writeGeofences()
  }

  override fun updateState(
    id: String,
    state: GeofenceState,
    enteredAt: Long?,
    dwellEmitted: Boolean,
  ) {
    val existing = records[id] ?: return
    records[id] = existing.copy(state = state, enteredAt = enteredAt, dwellEmitted = dwellEmitted)
    writeGeofences()
  }

  override fun setActive(ids: Set<String>) {
    var changed = false
    records.keys.toList().forEach { id ->
      val existing = records[id] ?: return@forEach
      val next = ids.contains(id)
      if (existing.active != next) {
        records[id] = existing.copy(active = next)
        changed = true
      }
    }
    if (changed) writeGeofences()
  }

  override fun setActive(ids: Collection<String>, active: Boolean) {
    var changed = false
    ids.forEach { id ->
      val existing = records[id] ?: return@forEach
      if (existing.active != active) {
        records[id] = existing.copy(active = active)
        changed = true
      }
    }
    if (changed) writeGeofences()
  }

  override fun enqueue(event: GeofenceEvent) = enqueue(listOf(event))

  override fun enqueue(events: List<GeofenceEvent>) {
    if (events.isEmpty()) return
    queue.addAll(events)
    var dropped = 0
    while (queue.size > MAX_QUEUED_EVENTS) {
      queue.removeAt(0) // FIFO: oldest first (§9.5)
      dropped++
    }
    if (dropped > 0) {
      Logger.w("event queue full, dropped $dropped oldest event(s)")
      // Through the property, not the backing field: the setter is what calls
      // writeMeta(). Overflow happens overwhelmingly on the receiver / killed-app
      // path, so a counter that only lives in memory is a counter nobody ever reads.
      meta = meta.copy(droppedCount = meta.droppedCount + dropped)
    }
    writeEvents()
  }

  override fun removeQueued(keys: Set<String>) {
    if (keys.isEmpty() || queue.isEmpty()) return
    if (!queue.removeAll { it.queueKey in keys }) return
    writeEvents()
  }

  override fun drainQueue(): List<GeofenceEvent> {
    if (queue.isEmpty()) return emptyList()
    val drained = queue.toList()
    queue.clear()
    writeEvents()
    return drained
  }

  // -------------------------------------------------------------------------
  // Serialisation
  // -------------------------------------------------------------------------

  private fun writeGeofences() {
    if (geofencesBlobQuarantined) {
      Logger.w("refusing to overwrite a quarantined geofences blob; changes are in memory only")
      return
    }
    val items = JSONObject()
    records.values.forEach { items.put(it.id, it.toJson()) }
    write(KEY_GEOFENCES, JSONObject().put("v", BLOB_VERSION).put("items", items))
  }

  private fun writeEvents() {
    val items = JSONArray()
    queue.forEach { items.put(it.toJson()) }
    write(KEY_EVENTS, JSONObject().put("v", BLOB_VERSION).put("items", items))
  }

  private fun writeMeta() {
    val root =
      JSONObject()
        .put("v", BLOB_VERSION)
        .put("enabled", metaValue.enabled)
        .put("droppedCount", metaValue.droppedCount)
    metaValue.centerLatitude?.let { root.put("centerLat", it) }
    metaValue.centerLongitude?.let { root.put("centerLng", it) }
    metaValue.centerAt?.let { root.put("centerAt", it) }
    metaValue.boundaryRadius?.let { root.put("boundaryRadius", it) }
    write(KEY_META, root)
  }

  private fun writeConfig() {
    val root =
      JSONObject()
        .put("v", BLOB_VERSION)
        .put("proximityRadius", configValue.proximityRadius)
        .put("initialTriggerEntry", configValue.initialTriggerEntry)
        .put("notificationResponsiveness", configValue.notificationResponsiveness)
        .put("useSignificantLocationChanges", configValue.useSignificantLocationChanges)
        .put("enableHeadless", configValue.enableHeadless)
        .put("minRadius", configValue.minRadius)
        .put("debug", configValue.debug)
    write(KEY_CONFIG, root)
  }

  private fun write(key: String, value: JSONObject) {
    try {
      val editor = prefs.edit().putString(key, value.toString())
      // `commit()` on the receiver/headless paths, where the process may be frozen
      // within milliseconds; `apply()` otherwise (§9.3).
      if (durableWrites) editor.commit() else editor.apply()
    } catch (error: Throwable) {
      throw GeofencingException("E_STORE", "failed to write '$key': ${error.message}", error)
    }
  }

  private companion object {
    const val PREFS_NAME = "rn_geofencing"
    const val BLOB_VERSION = 1

    const val KEY_GEOFENCES = "geofences"
    const val KEY_EVENTS = "events"
    const val KEY_META = "meta"
    const val KEY_CONFIG = "config"
  }
}

// ---------------------------------------------------------------------------
// JSON mapping
// ---------------------------------------------------------------------------

private fun GeofenceRecord.toJson(): JSONObject {
  val json =
    JSONObject()
      .put("lat", latitude)
      .put("lng", longitude)
      .put("radius", radius)
      .put("onEntry", notifyOnEntry)
      .put("onExit", notifyOnExit)
      .put("onDwell", notifyOnDwell)
      .put("loiteringDelay", loiteringDelay)
      .put("state", state.name)
      .put("dwellEmitted", dwellEmitted)
      .put("active", active)
  extras?.let { json.put("extras", it) }
  enteredAt?.let { json.put("enteredAt", it) }
  return json
}

private fun JSONObject.toRecord(id: String): GeofenceRecord {
  val defaults = GeofenceRecord(id = id, latitude = 0.0, longitude = 0.0, radius = 0.0)
  return GeofenceRecord(
    id = id,
    latitude = optDouble("lat", 0.0),
    longitude = optDouble("lng", 0.0),
    radius = optDouble("radius", 0.0),
    notifyOnEntry = optBoolean("onEntry", true),
    notifyOnExit = optBoolean("onExit", true),
    notifyOnDwell = optBoolean("onDwell", false),
    loiteringDelay = optInt("loiteringDelay", defaults.loiteringDelay),
    extras = optStringOrNull("extras"),
    state = GeofenceState.parse(optStringOrNull("state")),
    enteredAt = optLongOrNull("enteredAt"),
    dwellEmitted = optBoolean("dwellEmitted", false),
    active = optBoolean("active", false),
  )
}

private fun GeofenceEvent.toJson(): JSONObject {
  val json =
    JSONObject()
      .put("id", id)
      .put("action", action.wire)
      .put("ts", timestamp)
      .put("approximate", approximate)
      .put("synthetic", synthetic)
  latitude?.let { json.put("lat", it) }
  longitude?.let { json.put("lng", it) }
  accuracy?.let { json.put("accuracy", it) }
  extras?.let { json.put("extras", it) }
  return json
}

private fun JSONObject.toEvent(): GeofenceEvent? {
  val id = optStringOrNull("id") ?: return null
  val action = GeofenceAction.parse(optStringOrNull("action")) ?: return null
  return GeofenceEvent(
    id = id,
    action = action,
    timestamp = optLong("ts", 0L),
    latitude = optDoubleOrNull("lat"),
    longitude = optDoubleOrNull("lng"),
    accuracy = optDoubleOrNull("accuracy"),
    approximate = optBoolean("approximate", false),
    synthetic = optBoolean("synthetic", false),
    extras = optStringOrNull("extras"),
  )
}

/**
 * `optString` returns `""` for a missing key, and `optDouble` returns `NaN` — both of
 * which would be indistinguishable from a real value. These read `null` properly.
 */
private fun JSONObject.optStringOrNull(key: String): String? =
  if (isNull(key)) null else optString(key, "").ifEmpty { null }

private fun JSONObject.optDoubleOrNull(key: String): Double? =
  if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

private fun JSONObject.optLongOrNull(key: String): Long? =
  if (!has(key) || isNull(key)) null else optLong(key)
