package com.rngeofencing.core

/**
 * In-memory test doubles.
 *
 * [FakeGeofenceStore] and [FakeRegionRegistry] are why §9.6 and [RegionRegistry] are
 * interfaces at all: the rotation engine and the transition gate — the only two parts
 * of this module that are genuinely hard (§3) — are driven here with no device, no
 * Play Services and no `SharedPreferences`.
 */
class FakeGeofenceStore(
  override var meta: RotationMeta = RotationMeta(enabled = true),
  override var config: Config = Config(),
) : GeofenceStore {

  private val records = LinkedHashMap<String, GeofenceRecord>()
  private val queue = mutableListOf<GeofenceEvent>()

  override var durableWrites: Boolean = false

  /** Number of write-through calls, so a test can assert a mutation actually persisted. */
  var writes = 0
    private set

  override fun load() = Unit

  override fun all(): List<GeofenceRecord> = records.values.toList()

  override fun get(id: String): GeofenceRecord? = records[id]

  override fun count(): Int = records.size

  override fun upsert(records: List<GeofenceRecord>) {
    records.forEach { record ->
      val existing = this.records[record.id]
      this.records[record.id] =
        if (existing == null) {
          record
        } else {
          record.copy(
            state = existing.state,
            enteredAt = existing.enteredAt,
            dwellEmitted = existing.dwellEmitted,
            active = existing.active && !record.definitionChanged(existing),
          )
        }
    }
    writes++
  }

  override fun delete(ids: List<String>) {
    ids.forEach { records.remove(it) }
    writes++
  }

  override fun updateState(
    id: String,
    state: GeofenceState,
    enteredAt: Long?,
    dwellEmitted: Boolean,
  ) {
    val existing = records[id] ?: return
    records[id] = existing.copy(state = state, enteredAt = enteredAt, dwellEmitted = dwellEmitted)
    writes++
  }

  override fun setActive(ids: Set<String>) {
    records.keys.toList().forEach { id ->
      records[id] = records.getValue(id).copy(active = ids.contains(id))
    }
    writes++
  }

  override fun setActive(ids: Collection<String>, active: Boolean) {
    ids.forEach { id ->
      val existing = records[id] ?: return@forEach
      records[id] = existing.copy(active = active)
    }
    writes++
  }

  override fun activeIds(): Set<String> =
    records.values.filter { it.active }.map { it.id }.toSet()

  override fun enqueue(event: GeofenceEvent) = enqueue(listOf(event))

  override fun enqueue(events: List<GeofenceEvent>) {
    queue.addAll(events)
    while (queue.size > MAX_QUEUED_EVENTS) {
      queue.removeAt(0)
      meta = meta.copy(droppedCount = meta.droppedCount + 1)
    }
  }

  override fun drainQueue(): List<GeofenceEvent> {
    val drained = queue.toList()
    queue.clear()
    return drained
  }

  override fun queueSize(): Int = queue.size

  /** Seeds records directly, bypassing the upsert merge rules. */
  fun seed(vararg seeded: GeofenceRecord) {
    seeded.forEach { records[it.id] = it }
  }
}

/** Records the call order, which is what makes invariant 3 assertable. */
class FakeRegionRegistry(
  private var available: Boolean = true,
  var addResult: RegistryResult = RegistryResult.Success,
  var removeResult: RegistryResult = RegistryResult.Success,
) : RegionRegistry {

  /** Every call in order, e.g. `["add:a,b", "remove:c", "removeBoundary", "addBoundary:1300"]`. */
  val calls = mutableListOf<String>()

  override fun isAvailable(): Boolean = available

  override fun addRegions(
    records: List<GeofenceRecord>,
    initialTriggerEntry: Boolean,
    responsiveness: Int,
  ): RegistryResult {
    calls.add("add:${records.joinToString(",") { it.id }}")
    return addResult
  }

  override fun removeRegions(ids: List<String>): RegistryResult {
    calls.add("remove:${ids.sorted().joinToString(",")}")
    return removeResult
  }

  override fun addBoundary(center: LatLng, radius: Double): RegistryResult {
    calls.add("addBoundary:${radius.toInt()}")
    return RegistryResult.Success
  }

  override fun removeBoundary(): RegistryResult {
    calls.add("removeBoundary")
    return RegistryResult.Success
  }

  override fun removeAll(): RegistryResult {
    calls.add("removeAll")
    return RegistryResult.Success
  }
}

/** Builds a record with sane defaults so tests only state what they care about. */
fun record(
  id: String,
  latitude: Double = 0.0,
  longitude: Double = 0.0,
  radius: Double = 200.0,
  notifyOnEntry: Boolean = true,
  notifyOnExit: Boolean = true,
  notifyOnDwell: Boolean = false,
  loiteringDelay: Int = 30_000,
  state: GeofenceState = GeofenceState.UNKNOWN,
  enteredAt: Long? = null,
  dwellEmitted: Boolean = false,
  active: Boolean = false,
  extras: String? = null,
) =
  GeofenceRecord(
    id = id,
    latitude = latitude,
    longitude = longitude,
    radius = radius,
    notifyOnEntry = notifyOnEntry,
    notifyOnExit = notifyOnExit,
    notifyOnDwell = notifyOnDwell,
    loiteringDelay = loiteringDelay,
    extras = extras,
    state = state,
    enteredAt = enteredAt,
    dwellEmitted = dwellEmitted,
    active = active,
  )

/**
 * Offsets a coordinate by [metresNorth] metres.
 *
 * Latitude-only so the conversion stays exact enough for a test: one degree of
 * latitude is ~111.32 km everywhere, unlike longitude.
 */
fun north(from: LatLng, metresNorth: Double) =
  LatLng(from.latitude + metresNorth / 111_320.0, from.longitude)
