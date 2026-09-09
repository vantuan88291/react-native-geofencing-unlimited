package com.rngeofencing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every `(persistedState, platformEvent)` pair (§14).
 *
 * The gate exists because rotation introduces two failure modes a fixed set never
 * has: a spurious ENTER when a region is re-armed while the device is already inside
 * it (§5.1), and an EXIT that is never delivered because the region was rotated out
 * before the user left (§5.2).
 */
class TransitionGateTest {

  private val origin = LatLng(59.9139, 10.7522)

  private fun gate(store: GeofenceStore) = TransitionGate(store) { NOW }

  // -------------------------------------------------------------------------
  // §5.1 — spurious ENTER on re-arm
  // -------------------------------------------------------------------------

  @Test
  fun `first ENTER on an unknown geofence is emitted`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", state = GeofenceState.UNKNOWN))

    val events = gate(store).onPlatformTransition(listOf("a"), GeofenceAction.ENTER, origin)

    assertEquals(1, events.size)
    assertEquals(GeofenceAction.ENTER, events.first().action)
    assertEquals(GeofenceState.INSIDE, requireNotNull(store.get("a")).state)
  }

  @Test
  fun `ENTER while already INSIDE is dropped as a re-arm artifact`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", state = GeofenceState.INSIDE, enteredAt = NOW - 1000))

    val events = gate(store).onPlatformTransition(listOf("a"), GeofenceAction.ENTER, origin)

    // Both platforms report entry when a region the device is already inside is armed
    // — Android via INITIAL_TRIGGER_ENTER, iOS via didDetermineState(.inside).
    assertTrue("a re-arm must not surface as a crossing", events.isEmpty())
  }

  @Test
  fun `ENTER on a region rotated back in is a genuine crossing`() {
    val store = FakeGeofenceStore(config = Config(initialTriggerEntry = false))
    // OUTSIDE, not UNKNOWN: this region has been armed before, so `initialTriggerEntry`
    // does not apply to it. This is the §5.1 trap — that flag must only ever govern
    // the very first arming.
    store.seed(record("a", state = GeofenceState.OUTSIDE))

    val events = gate(store).onPlatformTransition(listOf("a"), GeofenceAction.ENTER, origin)

    assertEquals(1, events.size)
    assertEquals(GeofenceAction.ENTER, events.first().action)
  }

  @Test
  fun `initialTriggerEntry false suppresses only the very first arming`() {
    val store = FakeGeofenceStore(config = Config(initialTriggerEntry = false))
    store.seed(record("a", state = GeofenceState.UNKNOWN))

    val events = gate(store).onPlatformTransition(listOf("a"), GeofenceAction.ENTER, origin)

    assertTrue(events.isEmpty())
    // State is still advanced: suppressing the *event* must not leave the gate blind.
    assertEquals(GeofenceState.INSIDE, requireNotNull(store.get("a")).state)
  }

  @Test
  fun `notifyOnEntry false advances state without emitting`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", notifyOnEntry = false, state = GeofenceState.OUTSIDE))

    val events = gate(store).onPlatformTransition(listOf("a"), GeofenceAction.ENTER, origin)

    assertTrue(events.isEmpty())
    assertEquals(GeofenceState.INSIDE, requireNotNull(store.get("a")).state)
  }

  // -------------------------------------------------------------------------
  // EXIT
  // -------------------------------------------------------------------------

  @Test
  fun `EXIT while INSIDE is emitted and clears the dwell bookkeeping`() {
    val store = FakeGeofenceStore()
    store.seed(
      record("a", state = GeofenceState.INSIDE, enteredAt = NOW - 60_000, dwellEmitted = true)
    )

    val events = gate(store).onPlatformTransition(listOf("a"), GeofenceAction.EXIT, origin)

    assertEquals(1, events.size)
    assertEquals(GeofenceAction.EXIT, events.first().action)
    val record = requireNotNull(store.get("a"))
    assertEquals(GeofenceState.OUTSIDE, record.state)
    assertNull("enteredAt must be cleared on exit", record.enteredAt)
    assertFalse("a pending dwell is always cancelled on exit", record.dwellEmitted)
  }

  @Test
  fun `EXIT while already OUTSIDE is dropped as a duplicate`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", state = GeofenceState.OUTSIDE))

    val events = gate(store).onPlatformTransition(listOf("a"), GeofenceAction.EXIT, origin)

    assertTrue(events.isEmpty())
  }

  @Test
  fun `EXIT from UNKNOWN is emitted rather than swallowed`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", state = GeofenceState.UNKNOWN))

    val events = gate(store).onPlatformTransition(listOf("a"), GeofenceAction.EXIT, origin)

    // A real OS transition. Dropping it because we had no prior state would lose a
    // genuine crossing, which is worse than an EXIT with no matching ENTER.
    assertEquals(1, events.size)
    assertEquals(GeofenceState.OUTSIDE, requireNotNull(store.get("a")).state)
  }

  // -------------------------------------------------------------------------
  // DWELL — §5.3
  // -------------------------------------------------------------------------

  @Test
  fun `DWELL is emitted once per visit`() {
    val store = FakeGeofenceStore()
    store.seed(
      record("a", notifyOnDwell = true, state = GeofenceState.INSIDE, enteredAt = NOW - 40_000)
    )
    val gate = gate(store)

    val first = gate.onPlatformTransition(listOf("a"), GeofenceAction.DWELL, origin)
    val second = gate.onPlatformTransition(listOf("a"), GeofenceAction.DWELL, origin)

    assertEquals(1, first.size)
    assertEquals(GeofenceAction.DWELL, first.first().action)
    assertTrue("a second DWELL for the same visit is a duplicate", second.isEmpty())
    assertTrue(requireNotNull(store.get("a")).dwellEmitted)
  }

  @Test
  fun `ENTER resets dwellEmitted so the next visit can dwell again`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", notifyOnDwell = true, state = GeofenceState.OUTSIDE, dwellEmitted = true))

    gate(store).onPlatformTransition(listOf("a"), GeofenceAction.ENTER, origin)

    assertFalse(requireNotNull(store.get("a")).dwellEmitted)
  }

  // -------------------------------------------------------------------------
  // §5.2 — missed EXIT while rotated out
  // -------------------------------------------------------------------------

  @Test
  fun `rotating out a region we left emits a synthetic EXIT at the rotation centre`() {
    val store = FakeGeofenceStore()
    val far = north(origin, 3000.0)
    store.seed(
      record(
        "a",
        latitude = far.latitude,
        longitude = far.longitude,
        radius = 200.0,
        state = GeofenceState.INSIDE,
      )
    )

    val events = gate(store).onRotationApplied(listOf("a"), origin)

    assertEquals(1, events.size)
    val event = events.first()
    assertTrue(event.synthetic)
    assertEquals(GeofenceAction.EXIT, event.action)
    // Location is the rotation centre — there is no fix for an event we synthesised.
    assertEquals(origin.latitude, requireNotNull(event.latitude), 1e-9)
    assertEquals(GeofenceState.OUTSIDE, requireNotNull(store.get("a")).state)
  }

  @Test
  fun `rotating out a region we are still inside leaves it INSIDE`() {
    val store = FakeGeofenceStore()
    store.seed(
      record(
        "a",
        latitude = origin.latitude,
        longitude = origin.longitude,
        radius = 500.0,
        state = GeofenceState.INSIDE,
      )
    )

    val events = gate(store).onRotationApplied(listOf("a"), origin)

    assertTrue(events.isEmpty())
    assertEquals(GeofenceState.INSIDE, requireNotNull(store.get("a")).state)
  }

  @Test
  fun `rotating out a region already OUTSIDE emits nothing`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", state = GeofenceState.OUTSIDE))

    assertTrue(gate(store).onRotationApplied(listOf("a"), origin).isEmpty())
  }

  @Test
  fun `state reconciliation emits a synthetic EXIT when the OS says outside`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", state = GeofenceState.INSIDE))

    val event = gate(store).onStateDetermined("a", isInside = false, center = origin)

    assertNotNull(event)
    assertTrue(requireNotNull(event).synthetic)
    assertEquals(GeofenceAction.EXIT, event.action)
  }

  @Test
  fun `state reconciliation is silent when the OS agrees we are inside`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", state = GeofenceState.INSIDE))

    // The INSIDE case is left to the platform's own ENTER, which onEnter() dedups.
    assertNull(gate(store).onStateDetermined("a", isInside = true, center = origin))
  }

  // -------------------------------------------------------------------------
  // Misc
  // -------------------------------------------------------------------------

  @Test
  fun `a transition for an unknown identifier is dropped`() {
    val store = FakeGeofenceStore()

    val events = gate(store).onPlatformTransition(listOf("ghost"), GeofenceAction.ENTER, origin)

    // A leftover registration from a previous install, or a removal that raced the
    // broadcast. There is nothing to report it against.
    assertTrue(events.isEmpty())
  }

  @Test
  fun `one broadcast naming several geofences produces one event each`() {
    val store = FakeGeofenceStore()
    store.seed(
      record("a", state = GeofenceState.OUTSIDE),
      record("b", state = GeofenceState.OUTSIDE),
      record("c", state = GeofenceState.INSIDE),
    )

    val events = gate(store).onPlatformTransition(listOf("a", "b", "c"), GeofenceAction.ENTER, origin)

    // `c` is already INSIDE, so it is deduped; the other two are real.
    assertEquals(listOf("a", "b"), events.map { it.id })
  }

  @Test
  fun `extras ride along on the event verbatim`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", state = GeofenceState.OUTSIDE, extras = """{"placeId":"123"}"""))

    val events = gate(store).onPlatformTransition(listOf("a"), GeofenceAction.ENTER, origin)

    assertEquals("""{"placeId":"123"}""", events.first().extras)
  }

  @Test
  fun `an event with a real fix is not marked approximate`() {
    val store = FakeGeofenceStore()
    store.seed(record("a", state = GeofenceState.OUTSIDE))

    val events =
      gate(store).onPlatformTransition(listOf("a"), GeofenceAction.ENTER, origin, accuracy = 12.0)

    assertFalse(events.first().approximate)
    assertEquals(12.0, requireNotNull(events.first().accuracy), 1e-9)
  }

  private companion object {
    const val NOW = 1_736_412_000_000L
  }
}
