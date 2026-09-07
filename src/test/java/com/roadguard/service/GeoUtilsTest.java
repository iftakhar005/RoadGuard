package com.roadguard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoUtilsTest {

    private static final double DHAKA_LAT = 23.8103;
    private static final double DHAKA_LNG = 90.4125;

    @Test
    @DisplayName("distance from a point to itself is zero")
    void zeroDistance() {
        assertEquals(0.0, GeoUtils.haversineKm(DHAKA_LAT, DHAKA_LNG, DHAKA_LAT, DHAKA_LNG), 1e-9);
    }

    @Test
    @DisplayName("one degree of latitude is about 111.19 km")
    void oneDegreeOfLatitude() {
        double d = GeoUtils.haversineKm(DHAKA_LAT, DHAKA_LNG, DHAKA_LAT + 1.0, DHAKA_LNG);
        assertEquals(111.19, d, 0.1);
    }

    @Test
    @DisplayName("one degree of longitude shrinks by cos(latitude)")
    void oneDegreeOfLongitude() {
        double expected = 111.19 * Math.cos(Math.toRadians(DHAKA_LAT));
        double d = GeoUtils.haversineKm(DHAKA_LAT, DHAKA_LNG, DHAKA_LAT, DHAKA_LNG + 1.0);
        assertEquals(expected, d, 0.2);
    }

    @Test
    @DisplayName("distance is the same in both directions")
    void symmetric() {
        double there = GeoUtils.haversineKm(23.80, 90.40, 23.90, 90.50);
        double back = GeoUtils.haversineKm(23.90, 90.50, 23.80, 90.40);
        assertEquals(there, back, 1e-9);
    }

    @Test
    @DisplayName("the bounding box fully contains the search circle")
    void boxContainsCircle() {
        double radiusKm = 5.0;
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(DHAKA_LAT, DHAKA_LNG, radiusKm);

        for (int bearing = 0; bearing < 360; bearing += 5) {
            double rad = Math.toRadians(bearing);
            double latOffset = (radiusKm / 111.195) * Math.cos(rad);
            double lngOffset = (radiusKm / (111.195 * Math.cos(Math.toRadians(DHAKA_LAT)))) * Math.sin(rad);

            double pLat = DHAKA_LAT + latOffset;
            double pLng = DHAKA_LNG + lngOffset;

            assertTrue(pLat >= box.minLat() && pLat <= box.maxLat(),
                    "latitude outside box at bearing " + bearing);
            assertTrue(pLng >= box.minLng() && pLng <= box.maxLng(),
                    "longitude outside box at bearing " + bearing);
        }
    }

    @Test
    @DisplayName("bounding box does not blow up at the pole")
    void poleDoesNotDivideByZero() {
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(90.0, 0.0, 10.0);
        assertTrue(Double.isFinite(box.minLng()));
        assertTrue(Double.isFinite(box.maxLng()));
        assertTrue(box.maxLat() <= 90.0);
    }
}
