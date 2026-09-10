package com.rngeofencing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The headless drain (§6.5).
 *
 * `deliver()` queues an event **and** hands it to the headless service, so that a
 * service which never starts loses nothing. Once React Native accepts the task the
 * queued copy has to go, or the next launch's flush delivers the same crossing a
 * second time to a host that both registered the headless task and subscribed. The
 * key below is what makes "exactly those events, and nothing else" possible.
 */
class EventQueueTest {

  private fun event(id: String, action: GeofenceAction, timestamp: Long) =
    GeofenceEvent(id = id, action = action, timestamp = timestamp)

  @Test
  fun `queueKey separates events that differ in id, action or time`() {
    val enter = event("a", GeofenceAction.ENTER, 1_000L)

    assertNotEquals(enter.queueKey, event("b", GeofenceAction.ENTER, 1_000L).queueKey)
    assertNotEquals(enter.queueKey, event("a", GeofenceAction.EXIT, 1_000L).queueKey)
    assertNotEquals(enter.queueKey, event("a", GeofenceAction.ENTER, 1_001L).queueKey)
    // Same crossing carried through the intent and back: it has to match, or the
    // drain silently keeps a duplicate.
    assertEquals(enter.queueKey, event("a", GeofenceAction.ENTER, 1_000L).queueKey)
  }

  @Test
  fun `removeQueued drops the delivered events and keeps the rest`() {
    val store = FakeGeofenceStore()
    val delivered = listOf(event("a", GeofenceAction.ENTER, 1L), event("b", GeofenceAction.EXIT, 2L))
    val stillPending = event("c", GeofenceAction.ENTER, 3L)
    store.enqueue(delivered + stillPending)

    store.removeQueued(delivered.map { it.queueKey }.toSet())

    val remaining = store.drainQueue()
    assertEquals(1, remaining.size)
    assertEquals("c", remaining.first().id)
  }

  @Test
  fun `removeQueued with no match leaves the queue alone`() {
    val store = FakeGeofenceStore()
    store.enqueue(listOf(event("a", GeofenceAction.ENTER, 1L)))

    // The shape of a headless start that was never accepted: nothing is removed, and
    // the crossing survives to the next launch.
    store.removeQueued(setOf("a|ENTER|999"))

    assertTrue(store.queueSize() == 1)
  }
}
