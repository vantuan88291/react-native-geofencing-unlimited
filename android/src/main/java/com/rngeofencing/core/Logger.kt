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

  @Volatile var debug: Boolean = false

  fun d(message: String) {
    if (debug) {
      write { Log.d(TAG, message) }
    }
  }

  fun w(message: String) = write { Log.w(TAG, message) }

  fun w(message: String, error: Throwable) = write { Log.w(TAG, message, error) }

  fun e(message: String, error: Throwable? = null) = write {
    if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
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
