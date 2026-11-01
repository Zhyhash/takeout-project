package org.example.takeout.Common.Utils;
public final class GeoDistanceUtils {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoDistanceUtils() {}

    public static boolean isValidLongitude(double longitude) {
        return Double.isFinite(longitude) && longitude >= -180.0 && longitude <= 180.0;
    }

    public static boolean isValidLatitude(double latitude) {
        return Double.isFinite(latitude) && latitude >= -90.0 && latitude <= 90.0;
    }

    public static double distanceKm(
            double longitude1, double latitude1,
            double longitude2, double latitude2) {

        double lat1 = Math.toRadians(latitude1);
        double lat2 = Math.toRadians(latitude2);
        double deltaLat = Math.toRadians(latitude2 - latitude1);
        double deltaLon = Math.toRadians(longitude2 - longitude1);

        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.pow(Math.sin(deltaLon / 2), 2);

        double c = 2 * Math.asin(
                Math.sqrt(Math.min(1.0, Math.max(0.0, a)))
        );

        return EARTH_RADIUS_KM * c;
    }
}
