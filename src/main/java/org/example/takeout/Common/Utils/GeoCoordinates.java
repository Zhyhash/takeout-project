package org.example.takeout.Common.Utils;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;

/** Redis GEO 使用的坐标范围；纬度受 Web Mercator 投影限制。 */
public final class GeoCoordinates {

    public static final double MAX_LONGITUDE = 180.0;
    public static final double MAX_LATITUDE = 85.05112878;

    private GeoCoordinates() {
    }

    public static boolean isValid(double longitude, double latitude) {
        return Double.isFinite(longitude) && Double.isFinite(latitude)
                && longitude >= -MAX_LONGITUDE && longitude <= MAX_LONGITUDE
                && latitude >= -MAX_LATITUDE && latitude <= MAX_LATITUDE;
    }

    public static void validate(double longitude, double latitude) {
        if (!isValid(longitude, latitude)) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR,
                    "经纬度不合法：经度必须在-180到180之间，纬度必须在-85.05112878到85.05112878之间");
        }
    }
}
