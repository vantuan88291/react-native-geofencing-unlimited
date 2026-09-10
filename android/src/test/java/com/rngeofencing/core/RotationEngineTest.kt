package com.rngeofencing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4 under test — capacity, the safe zone, the degenerate cluster, and the diff
 * ordering (§14).
 *
 * Every case here corresponds to a way the module can "silently stop working": a
 * geofence wrongly excluded, a boundary larger than the safe zone, or a diff that
 * removes before it adds.
 */
class RotationEngineTest {

  private val origin = LatLng(59.9139, 10.7522)

  private fun engine(
    store: GeofenceStore,
    registry: RegionRegistry = FakeRegionRegistry(),
    capacity: Int = 19,
  ) = RotationEngine(store, registry, TransitionGate(store) { NOW }, capacity)

  // -------------------------------------------------------------------------
  // computeActiveSet — §4.3
  // -------------------------------------------------------------------------

  @Test
  fun `arms everything and no boundary when the whole set fits`() {
    val store = FakeGeofenceStore()
    store.seed(*(1..10).map { record("g$it", latitude = origin.latitude) }.toTypedArray())

    val result = engine(store, capacity = 19).computeActiveSet(origin)

    assertEquals(10, result.active.size)
    // No rotation is needed, so there is nothing to detect staleness of (§4.3 step 2).
    assertNull(result.boundaryRadius)
  }

  @Test
  fun `caps the active set at capacity and picks the nearest`() {
    val store = FakeGeofenceStore()
    // 30 geofences at 100 m intervals going north.
    (1..30).forEach { i ->
      val at = north(origin, i * 100.0)
      store.seed(record("g$i", latitude = at.latitude, longitude = at.longitude, radius = 50.0))
    }

    val result = engine(store, capacity = 19).computeActiveSet(origin)

    assertEquals(19, result.active.size)
    // Nearest N, in order: g1 is 100 m away, g19 is 1900 m away.
    assertEquals((1..19).map { "g$it" }, result.active.map { it.id })
  }

  @Test
  fun `boundary stays inside the safe zone of the nearest excluded geofence`() {
    // proximityRadius is wide enough that the excluded geofence IS a candidate — that
    // is what engages the safe-zone branch rather than the fallback below.
    val store = FakeGeofenceStore(config = Config(proximityRadius = 5000.0))
    // 19 geofences packed close, then one excluded at 3000 m with a 300 m radius.
    (1..19).forEach { i ->
      val at = north(origin, i * 10.0)
      store.seed(record("near$i", latitude = at.latitude, longitude = at.longitude, radius = 50.0))
    }
    val excludedAt = north(origin, 3000.0)
    store.seed(
      record("far", latitude = excludedAt.latitude, longitude = excludedAt.longitude, radius = 300.0)
    )

    val result = engine(store, capacity = 19).computeActiveSet(origin)
    val boundary = requireNotNull(result.boundaryRadius)

    // safeZone = 3000 - 300 = 2700; boundary = 2700 - 200 margin = 2500.
    assertEquals(2500.0, boundary, 25.0)
    // The load-bearing property: the boundary must trip before an un-armed region can
    // be reached, so it stays strictly inside (distance - radius).
    assertTrue(
      "boundary $boundary must stay inside the safe zone",
      boundary < 3000.0 - 300.0
    )
  }

  @Test
  fun `safe zone falls back to the proximity radius when nothing excluded is a candidate`() {
    val store = FakeGeofenceStore(config = Config(proximityRadius = 2000.0))
    (1..19).forEach { i ->
      val at = north(origin, i * 10.0)
      store.seed(record("near$i", latitude = at.latitude, longitude = at.longitude, radius = 50.0))
    }
    // 3000 m away with a 300 m radius: its nearest edge is at 2700 m, past
    // `proximityRadius + radius` (2300 m), so it is filtered out before capacity is
    // even considered and there is no "first excluded candidate" to measure against.
    val beyondAt = north(origin, 3000.0)
    store.seed(
      record("beyond", latitude = beyondAt.latitude, longitude = beyondAt.longitude, radius = 300.0)
    )

    val boundary = requireNotNull(engine(store, capacity = 19).computeActiveSet(origin).boundaryRadius)

    // 2000 proximity - 200 margin. Still safe: the boundary trips at 1800 m, well
    // before the un-armed region's edge at 2700 m.
    assertEquals(1800.0, boundary, 1.0)
    assertTrue(boundary < 3000.0 - 300.0)
  }

  @Test
  fun `boundary is clamped to the proximity radius when nothing is excluded nearby`() {
    val store = FakeGeofenceStore(config = Config(proximityRadius = 1000.0))
    // 25 geofences all within the proximity radius, so 6 are excluded by capacity but
    // the nearest excluded one is very close.
    (1..25).forEach { i ->
      val at = north(origin, i * 5.0)
      store.seed(record("g$i", latitude = at.latitude, longitude = at.longitude, radius = 50.0))
    }

    val result = engine(store, capacity = 19).computeActiveSet(origin)
    val boundary = requireNotNull(result.boundaryRadius)

    assertTrue("boundary must never exceed proximityRadius", boundary <= 1000.0)
  }

  @Test
  fun `dense cluster clamps up to the minimum boundary radius`() {
    val store = FakeGeofenceStore()
    // 25 geofences inside 250 m: the safe zone minus the margin goes negative, so the
    // clamp has to produce MIN_BOUNDARY_RADIUS_M. This is the §4.3 degenerate case —
    // unavoidable with a fixed slot budget, and it logs a warning.
    (1..25).forEach { i ->
      val at = north(origin, i * 10.0)
      store.seed(record("g$i", latitude = at.latitude, longitude = at.longitude, radius = 50.0))
    }

    val result = engine(store, capacity = 19).computeActiveSet(origin)

    assertEquals(MIN_BOUNDARY_RADIUS_M, requireNotNull(result.boundaryRadius), 0.001)
  }

  @Test
  fun `never excludes a geofence whose edge is reachable`() {
    val store = FakeGeofenceStore(config = Config(proximityRadius = 1000.0))
    // Centre is 1200 m away — beyond proximityRadius — but its radius is 500 m, so its
    // edge is only 700 m away and it IS reachable. The filter must be
    // `distance <= proximityRadius + radius`, not `distance <= proximityRadius` (§4.3).
    val at = north(origin, 1200.0)
    store.seed(record("edge-reachable", latitude = at.latitude, longitude = at.longitude, radius = 500.0))
    // Padding so rotation is actually engaged rather than short-circuited.
    (1..25).forEach { i ->
      val p = north(origin, i * 2000.0)
      store.seed(record("far$i", latitude = p.latitude, longitude = p.longitude, radius = 50.0))
    }

    val result = engine(store, capacity = 19).computeActiveSet(origin)

    assertTrue(
      "a geofence whose edge is within proximityRadius must be a candidate",
      result.active.any { it.id == "edge-reachable" }
    )
  }

  @Test
  fun `boundary id is never a rotation candidate`() {
    val store = FakeGeofenceStore()
    store.seed(record(BOUNDARY_ID, latitude = origin.latitude, longitude = origin.longitude))
    (1..25).forEach { i ->
      val at = north(origin, i * 100.0)
      store.seed(record("g$i", latitude = at.latitude, longitude = at.longitude))
    }

    val result = engine(store, capacity = 19).computeActiveSet(origin)

    assertFalse(result.active.any { it.id == BOUNDARY_ID })
  }

  // -------------------------------------------------------------------------
  // applyActiveSet — §4.4
  // -------------------------------------------------------------------------

  @Test
  fun `adds before it removes, and arms the boundary last`() {
    val store = FakeGeofenceStore()
    store.seed(record("stale", active = true), record("fresh", active = false))
    val registry = FakeRegionRegistry()

    engine(store, registry).applyActiveSet(
      next = listOf(store.get("fresh")!!),
      boundaryRadius = 1300.0,
      center = origin,
      forceReAddAll = false,
    )

    // Invariant 3: a kill between the two calls must leave a superset, never a hole.
    //
    // The boundary comes down first — it holds a platform slot the adds may need, and
    // it is re-armed at the new centre regardless — and goes back up last, because it
    // is the trigger for the next rotation.
    assertEquals(
      listOf("removeBoundary", "add:fresh", "remove:stale", "addBoundary:1300"),
      registry.calls
    )
  }

  @Test
  fun `no-op on an unchanged set still refreshes the boundary`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", active = true))
    val registry = FakeRegionRegistry()

    engine(store, registry).applyActiveSet(
      next = listOf(store.get("a")!!),
      boundaryRadius = 900.0,
      center = origin,
      forceReAddAll = false,
    )

    // Nothing added or removed — the region is already armed — but the boundary is
    // re-centred, because the rotation centre moved.
    assertEquals(listOf("removeBoundary", "addBoundary:900"), registry.calls)
  }

  @Test
  fun `a failed add marks the regions not-armed so the next rotation retries`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", active = false))
    val registry =
      FakeRegionRegistry(addResult = RegistryResult.Failure("E_PLATFORM", "GEOFENCE_TOO_MANY_GEOFENCES"))

    val result =
      engine(store, registry).applyActiveSet(
        next = listOf(store.get("a")!!),
        boundaryRadius = null,
        center = origin,
        forceReAddAll = false,
      )

    // Invariant 9: `active` may only be set after a call that actually resolved. A
    // flag set optimistically for a failed call makes the next diff skip a region that
    // was never armed.
    assertFalse(requireNotNull(store.get("a")).active)
    assertEquals(listOf("a"), result.failed)
    assertTrue("a failed add must not be reported as newly on", result.on.isEmpty())
  }

  @Test
  fun `forceReAddAll re-arms regions the flags already claim are active`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", active = true), record("b", active = true))
    val registry = FakeRegionRegistry()

    engine(store, registry).applyActiveSet(
      next = listOf(store.get("a")!!, store.get("b")!!),
      boundaryRadius = null,
      center = origin,
      forceReAddAll = true,
    )

    // A reboot or force-stop clears the OS registry while our flags still say
    // `active = true`; a blind re-add is the only thing that resolves that (§4.6).
    assertEquals(listOf("removeBoundary", "add:a,b"), registry.calls)
  }

  @Test
  fun `a full rotation under the iOS slot cap arms the whole new set`() {
    // Reproduces the reported bug. iOS holds at most 20 regions, so with 19 armed
    // plus the boundary the app is already at the cap. Moving far enough that the
    // entire set changes then asks for 19 more, and a naive add-before-remove has the
    // OS reject nearly all of them — the symptom being only the first two or three
    // showing as armed until the app is killed and relaunched.
    val store = FakeGeofenceStore()
    val old = (1..19).map { record("old$it", latitude = north(origin, 9000.0 + it).latitude, active = true) }
    val fresh = (1..19).map { i ->
      val at = north(origin, i * 10.0)
      record("new$i", latitude = at.latitude, longitude = at.longitude)
    }
    store.seed(*old.toTypedArray(), *fresh.toTypedArray())

    val registry = FakeRegionRegistry(platformMax = 20)
    registry.seedMonitored(*old.map { it.id }.toTypedArray(), BOUNDARY_ID)

    engine(store, registry, capacity = 19).applyActiveSet(
      next = fresh,
      boundaryRadius = 900.0,
      center = origin,
      forceReAddAll = false,
    )

    assertTrue(
      "the platform rejected ${registry.rejected.size} region(s): ${registry.rejected}",
      registry.rejected.isEmpty()
    )
    assertEquals(
      "all 19 new regions plus the boundary must end up armed",
      (fresh.map { it.id } + BOUNDARY_ID).toSet(),
      registry.monitored.toSet()
    )
    assertTrue("must never exceed the platform cap", registry.monitored.size <= 20)
  }

  @Test
  fun `slots are freed only from regions that were leaving anyway`() {
    val store = FakeGeofenceStore()
    // `keep` stays in the next set; `drop1`/`drop2` are on their way out.
    val keep = record("keep", latitude = origin.latitude, longitude = origin.longitude, active = true)
    val drop1 = record("drop1", latitude = north(origin, 8000.0).latitude, active = true)
    val drop2 = record("drop2", latitude = north(origin, 9000.0).latitude, active = true)
    val fresh = record("fresh", latitude = north(origin, 20.0).latitude)
    store.seed(keep, drop1, drop2, fresh)

    val registry = FakeRegionRegistry(platformMax = 4) // 3 usable + boundary
    registry.seedMonitored("keep", "drop1", "drop2", BOUNDARY_ID)

    engine(store, registry, capacity = 3).applyActiveSet(
      next = listOf(keep, fresh),
      boundaryRadius = 700.0,
      center = origin,
      forceReAddAll = false,
    )

    // The region we intend to keep is never taken down to make room, and the furthest
    // departing region is the one sacrificed first.
    val removeCalls = registry.calls.filter { it.startsWith("remove:") }
    assertFalse(
      "a region staying in the set must never be removed: $removeCalls",
      removeCalls.any { it.contains("keep") }
    )
    assertTrue(registry.rejected.isEmpty())
    assertEquals(setOf("keep", "fresh", BOUNDARY_ID), registry.monitored.toSet())
  }

  @Test
  fun `rotating out a region we have already left emits a synthetic exit`() {
    val store = FakeGeofenceStore()
    // We believe we are inside `left`, but it sits 5 km from the new centre.
    val far = north(origin, 5000.0)
    store.seed(
      record(
        "left",
        latitude = far.latitude,
        longitude = far.longitude,
        radius = 200.0,
        state = GeofenceState.INSIDE,
        active = true,
      ),
      record("keep", latitude = origin.latitude, longitude = origin.longitude, active = true),
    )
    val registry = FakeRegionRegistry()

    val result =
      engine(store, registry).applyActiveSet(
        next = listOf(store.get("keep")!!),
        boundaryRadius = null,
        center = origin,
        forceReAddAll = false,
      )

    // §5.2: without this the state stays INSIDE forever and the host app has a stuck
    // session.
    assertEquals(1, result.syntheticEvents.size)
    val event = result.syntheticEvents.first()
    assertEquals("left", event.id)
    assertEquals(GeofenceAction.EXIT, event.action)
    assertTrue(event.synthetic)
    assertEquals(GeofenceState.OUTSIDE, requireNotNull(store.get("left")).state)
  }

  @Test
  fun `rotating out a region we are still inside keeps it INSIDE`() {
    val store = FakeGeofenceStore()
    // Inside it, and only 50 m from the new centre — we have not left, we are just out
    // of slots. A synthetic EXIT here would be a lie.
    val close = north(origin, 50.0)
    store.seed(
      record(
        "still-inside",
        latitude = close.latitude,
        longitude = close.longitude,
        radius = 500.0,
        state = GeofenceState.INSIDE,
        active = true,
      )
    )

    val result =
      engine(store).applyActiveSet(
        next = emptyList(),
        boundaryRadius = null,
        center = origin,
        forceReAddAll = false,
      )

    assertTrue(result.syntheticEvents.isEmpty())
    assertEquals(GeofenceState.INSIDE, requireNotNull(store.get("still-inside")).state)
  }

  @Test
  fun `records the rotation centre and boundary radius in meta`() {
    val store = FakeGeofenceStore()
    store.seed(record("a"))

    engine(store).applyActiveSet(
      next = listOf(store.get("a")!!),
      boundaryRadius = 1234.0,
      center = origin,
      forceReAddAll = false,
    )

    // The example app reads these to show when rotation has stalled (§16).
    assertEquals(origin.latitude, requireNotNull(store.meta.centerLatitude), 1e-9)
    assertEquals(origin.longitude, requireNotNull(store.meta.centerLongitude), 1e-9)
    assertEquals(1234.0, requireNotNull(store.meta.boundaryRadius), 1e-9)
  }

  private companion object {
    const val NOW = 1_736_412_000_000L
  }
}
