package org.example.takeout.Rider.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Rider.VO.RiderDistanceVO;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Service
public class RiderLocationService {
    private static final String RIDER_LOCATION_KEY = "rider:locations";
    private static final String RIDER_LAST_ACTIVE_KEY = "rider:last_active";
    private static final int NEARBY_RIDER_LIMIT = 100;
    private static final long RIDER_TIMEOUT_MS = 300_000L;
    private static final int CLEANUP_BATCH_SIZE = 100;
    private static final int CLEANUP_MAX_BATCHES = 10;

    private static final DefaultRedisScript<Long> UPDATE_LOCATION_SCRIPT = new DefaultRedisScript<>("""
            local locationKey    = KEYS[1]
            local lastActiveKey  = KEYS[2]
            local longitude      = ARGV[1]
            local latitude       = ARGV[2]
            local riderId        = ARGV[3]
            local lastActiveTime = ARGV[4]

            redis.call('GEOADD', locationKey, longitude, latitude, riderId)
            redis.call('ZADD', lastActiveKey, lastActiveTime, riderId)

            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> REMOVE_LOCATION_SCRIPT = new DefaultRedisScript<>("""
            local locationKey   = KEYS[1]
            local lastActiveKey = KEYS[2]
            local riderId       = ARGV[1]

            redis.call('ZREM', locationKey, riderId)
            redis.call('ZREM', lastActiveKey, riderId)

            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> REMOVE_TIMEOUT_RIDERS_SCRIPT = new DefaultRedisScript<>("""
            local locationKey   = KEYS[1]
            local lastActiveKey = KEYS[2]
            local staleBefore   = ARGV[1]
            local batchSize     = tonumber(ARGV[2])

            local staleRiderIds = redis.call('ZRANGEBYSCORE', lastActiveKey, '-inf', staleBefore, 'LIMIT', 0, batchSize)
            for _, riderId in ipairs(staleRiderIds) do
                redis.call('ZREM', locationKey, riderId)
                redis.call('ZREM', lastActiveKey, riderId)
            end
            return #staleRiderIds
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RiderService riderService;

    public void updateLocation(Long riderId, double longitude, double latitude) {

        if (riderId == null) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "骑手ID不能为空");
        }

        // 仅处于活跃状态的骑手可以上报位置，同时确保提交的是当前登录骑手自己的位置。
        Long activeRiderId = riderService.requireActiveRiderId();
        if (!activeRiderId.equals(riderId)) {
            throw new AuthException("只能提交自己的位置信息");
        }

        validateCoordinates(longitude, latitude);

        long lastActiveTime = System.currentTimeMillis();
        try {
            Long scriptResult = redisTemplate.execute(
                    UPDATE_LOCATION_SCRIPT,
                    List.of(RIDER_LOCATION_KEY, RIDER_LAST_ACTIVE_KEY),
                    String.valueOf(longitude),
                    String.valueOf(latitude),
                    String.valueOf(riderId),
                    String.valueOf(lastActiveTime)
            );

            // GEOADD 对已存在的骑手只更新坐标，返回 0 也属于写入成功，只有无返回结果才算失败
            if (scriptResult == null) {
                throw new RedisGeoOperationException("骑手位置写入未得到成功确认");
            }
        } catch (RedisGeoOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisGeoOperationException("骑手位置上传失败，请稍后重试", e);
        }
    }

    public List<RiderDistanceVO> findNearbyRiders(double longitude, double latitude, double radiusKm) {
        validateCoordinates(longitude, latitude);
        if (!Double.isFinite(radiusKm) || radiusKm <= 0) {
            throw new BusinessException(
                    ResultCodeEnum.PARAM_ERROR,
                    "附近距离必须大于0"
            );
        }

        Circle searchArea = new Circle(
                new Point(longitude, latitude),
                new Distance(radiusKm, Metrics.KILOMETERS)
        );
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults;
        try {
            geoResults = redisTemplate.opsForGeo().radius(
                    RIDER_LOCATION_KEY,
                    searchArea,
                    RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                            .includeDistance()
                            .sortAscending()
                            .limit(NEARBY_RIDER_LIMIT)
            );
        } catch (Exception e) {
            throw new RedisGeoOperationException("附近骑手查询失败，请稍后重试", e);
        }

        if (geoResults == null) {
            throw new RedisGeoOperationException("GEO查询未返回结果");
        }

        List<RiderDistanceVO> nearbyRiders = new ArrayList<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult : geoResults) {
            String riderId = geoResult.getContent().getName();
            Distance distance = geoResult.getDistance();
            nearbyRiders.add(new RiderDistanceVO(riderId, distance));
        }
        return nearbyRiders;
    }

    public Optional<Point> getRiderLocation(Long riderId) {
        if (riderId == null) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "骑手id不能为空");
        }
        if (riderId <= 0) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "骑手id必须大于0");
        }

        List<Point> positions;
        try {
            positions = redisTemplate.opsForGeo().position(
                    RIDER_LOCATION_KEY,
                    String.valueOf(riderId)
            );
        } catch (Exception e) {
            throw new RedisGeoOperationException("查询骑手地理位置失败，riderId=" + riderId, e);
        }

        if (positions == null || positions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(positions.get(0));
    }

    public void removeRiderLocation(Long riderId) {
        if (riderId == null || riderId <= 0) {
            throw new BusinessException(
                    ResultCodeEnum.PARAM_ERROR,
                    "骑手ID不合法"
            );
        }

        try {
            redisTemplate.execute(
                    REMOVE_LOCATION_SCRIPT,
                    List.of(RIDER_LOCATION_KEY, RIDER_LAST_ACTIVE_KEY),
                    String.valueOf(riderId)
            );
        } catch (RedisGeoOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisGeoOperationException("删除骑手地理位置信息失败", e);
        }
    }

    public void cleanupExpiredRiders() {
        String staleBefore = String.valueOf(
                System.currentTimeMillis() - RIDER_TIMEOUT_MS
        );

        try {
            for (int batchIndex = 0; batchIndex < CLEANUP_MAX_BATCHES; batchIndex++) {
                Long removedCount = redisTemplate.execute(
                        REMOVE_TIMEOUT_RIDERS_SCRIPT,
                        List.of(RIDER_LOCATION_KEY, RIDER_LAST_ACTIVE_KEY),
                        staleBefore,
                        String.valueOf(CLEANUP_BATCH_SIZE)
                );

                if (removedCount == null) {
                    throw new RedisGeoOperationException("过期骑手清理未得到执行结果");
                }
                if (removedCount < CLEANUP_BATCH_SIZE) {
                    break;
                }
            }
        } catch (RedisGeoOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisGeoOperationException("过期骑手位置自动清理失效", e);
        }
    }

    private void validateCoordinates(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180
                || !Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new BusinessException(
                    ResultCodeEnum.PARAM_ERROR,
                    "经纬度不合法：经度必须在-180到180之间，纬度必须在-90到90之间"
            );
        }
    }
}
