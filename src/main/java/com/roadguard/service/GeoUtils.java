package com.roadguard.service;

public final class GeoUtils {

    public static final double EARTH_RADIUS_KM = 6371.0;

    private static final double KM_PER_DEGREE_LAT = 111.195;

    private GeoUtils() {
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static BoundingBox boundingBox(double lat, double lng, double radiusKm) {
        double latDelta = radiusKm / KM_PER_DEGREE_LAT;

        double cosLat = Math.cos(Math.toRadians(lat));
        double lngDelta = Math.abs(cosLat) < 1e-6
                ? 180.0
                : radiusKm / (KM_PER_DEGREE_LAT * Math.abs(cosLat));

        return new BoundingBox(
                clampLat(lat - latDelta),
                clampLat(lat + latDelta),
                lng - lngDelta,
                lng + lngDelta);
    }

    private static double clampLat(double lat) {
        return Math.max(-90.0, Math.min(90.0, lat));
    }

    public record BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {
    }
}
