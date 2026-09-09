package com.rngeofencing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haversine and clamp (§14).
 *
 * Distance is what the whole rotation engine rests on: it picks the active set, sizes
 * the safe zone, and decides whether a rotated-out region owes the host a synthetic
 * EXIT. An error here is invisible and affects everything.
 */
class GeoTest {

  private val oslo = LatLng(59.9139, 10.7522)

  @Test
  fun `distance to itself is zero`() {
    assertEquals(0.0, haversine(oslo, oslo), 1e-9)
  }

  @Test
  fun `one degree of latitude is about 111 kilometres`() {
    val north = LatLng(oslo.latitude + 1.0, oslo.longitude)
    assertEquals(111_195.0, haversine(oslo, north), 200.0)
  }

  @Test
  fun `matches a known city pair`() {
    // Oslo → Copenhagen, ~483 km by great circle.
    val copenhagen = LatLng(55.6761, 12.5683)
    assertEquals(483_000.0, haversine(oslo, copenhagen), 5_000.0)
  }

  @Test
  fun `is symmetric`() {
    val other = LatLng(48.8566, 2.3522)
    assertEquals(haversine(oslo, other), haversine(other, oslo), 1e-6)
  }

  @Test
  fun `is accurate at the small distances rotation actually uses`() {
    // 100 m north. This is the range that decides active-set membership, and the
    // range where a law-of-cosines implementation loses precision.
    val near = north(oslo, 100.0)
    assertEquals(100.0, haversine(oslo, near), 0.5)
  }

  @Test
  fun `handles the antimeridian without blowing up`() {
    val west = LatLng(0.0, 179.9)
    val east = LatLng(0.0, -179.9)
    val distance = haversine(west, east)
    // ~22 km apart across the line, not most of the way round the planet.
    assertTrue("expected a short distance, got $distance", distance < 30_000.0)
  }

  @Test
  fun `clamp bounds a value both ways`() {
    assertEquals(5.0, clamp(5.0, 1.0, 10.0), 1e-9)
    assertEquals(1.0, clamp(-5.0, 1.0, 10.0), 1e-9)
    assertEquals(10.0, clamp(50.0, 1.0, 10.0), 1e-9)
  }

  @Test
  fun `clamp resolves a degenerate range to the high bound`() {
    // Happens in a dense cluster, where `safeZone - margin` falls below the minimum
    // boundary radius and the two bounds cross. The conservative answer is the
    // proximity radius — never something larger (§4.3).
    assertEquals(200.0, clamp(1000.0, low = 500.0, high = 200.0), 1e-9)
  }

  @Test
  fun `coordinate validity rejects the values that would arm the wrong regions`() {
    assertTrue(LatLng(0.0, 0.0).isValid)
    assertTrue(LatLng(-90.0, 180.0).isValid)
    assertTrue(!LatLng(Double.NaN, 0.0).isValid)
    assertTrue(!LatLng(0.0, Double.POSITIVE_INFINITY).isValid)
    assertTrue(!LatLng(91.0, 0.0).isValid)
    assertTrue(!LatLng(0.0, -181.0).isValid)
  }
}
