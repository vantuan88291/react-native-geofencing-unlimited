package com.rngeofencing.core

import android.util.Log

/**
 * `debug: true` logging (§14).
 *
 * Almost every field report about this module resolves to reading a rotation log, so
 * the rotation logger prints the centre, the boundary radius and the on/off diff —
 * not just "rotated".
 *
 * Warnings and errors are always logged; only the verbose rotation trace is gated on
 * the flag.
 *
 * Every call is guarded: `android.util.Log` throws "not mocked" under a plain JVM
 * unit test, and the §14 unit tests exercise the rotation engine and the transition
 * gate — both of which log — without Robolectric.
 */
object Logger {
  const val TAG = "RNGeofencing"

  /** Lines kept for `getDebugLog()`. Bounded — this is a debugging aid, not an archive. */
  private const val RING_CAPACITY = 300

  @Volatile var debug: Boolean = false

  /**
   * An in-memory ring of recent lines.
   *
   * The reason it exists: the most interesting logging this module does happens during
   * a process wake — a rotation from a broadcast receiver, or the launch re-arm — which
   * is *before* JS is running and therefore impossible to observe from a JS logger.
   * Buffering natively and letting JS pull the lines later is what makes those visible
   * without attaching a native log viewer.
   */
  private val ring = ArrayDeque<String>(RING_CAPACITY)

  private fun record(level: String, message: String) {
    synchronized(ring) {
      if (ring.size >= RING_CAPACITY) {
        ring.removeFirst()
      }
      ring.addLast("${System.currentTimeMillis()} $level $message")
    }
  }

  /** Drains nothing — reading the log must not destroy it, so repeated calls are safe. */
  fun snapshot(): List<String> = synchronized(ring) { ring.toList() }

  fun clear() {
    synchronized(ring) { ring.clear() }
  }

  fun d(message: String) {
    if (debug) {
      record("D", message)
      write { Log.d(TAG, message) }
    }
  }

  fun w(message: String) {
    record("W", message)
    write { Log.w(TAG, message) }
  }

  fun w(message: String, error: Throwable) {
    record("W", "$message — ${error.message}")
    write { Log.w(TAG, message, error) }
  }

  fun e(message: String, error: Throwable? = null) {
    record("E", if (error != null) "$message — ${error.message}" else message)
    write { if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message) }
  }

  private inline fun write(block: () -> Unit) {
    try {
      block()
    } catch (_: Throwable) {
      // No logger available (JVM unit test). Losing a log line is never worth an
      // exception escaping into a broadcast receiver.
    }
  }
}
