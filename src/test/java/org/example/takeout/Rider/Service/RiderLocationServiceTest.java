package org.example.takeout.Rider.Service;

import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Rider.VO.RiderDistanceVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiderLocationServiceTest {

    private static final String LOCATION_KEY = "rider:locations";
    private static final String LAST_ACTIVE_KEY = "rider:last_active";
    private static final String RIDER_ID = "301";
    private static final double LONGITUDE = 121.4737;
    private static final double LATITUDE = 31.2304;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RiderService riderService;

    @Mock
    private GeoOperations<String, String> geoOperations;

    @InjectMocks
    private RiderLocationService riderLocationService;

    @Test
    void updateLocationWritesGeoLocationAndLastActiveTime() {
        long startedAt = System.currentTimeMillis();
        givenActiveRider(301L);
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any());

        riderLocationService.updateLocation(301L, LONGITUDE, LATITUDE);
        long finishedAt = System.currentTimeMillis();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(LOCATION_KEY, LAST_ACTIVE_KEY)),
                eq("121.4737"),
                eq("31.2304"),
                eq(RIDER_ID),
                argThat(value -> value instanceof String text
                        && Long.parseLong(text) >= startedAt
                        && Long.parseLong(text) <= finishedAt)
        );
    }

    @Test
    void updateLocationAcceptsZeroAndBoundaryCoordinates() {
        givenActiveRider(301L);
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any());

        riderLocationService.updateLocation(301L, 0, 0);
        riderLocationService.updateLocation(301L, 180, 90);
        riderLocationService.updateLocation(301L, -180, -90);

        verify(redisTemplate, times(3)).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    void updateLocationRejectsNullRiderId() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> riderLocationService.updateLocation(null, LONGITUDE, LATITUDE));

        assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
        assertEquals("骑手ID不能为空", exception.getMessage());
        verifyNoInteractions(redisTemplate, riderService);
    }

    @Test
    void updateLocationRejectsInactiveRider() {
        when(riderService.requireActiveRiderId())
                .thenThrow(new AuthException("骑手账号已禁用或不存在，请重新登录"));

        AuthException exception = assertThrows(AuthException.class,
                () -> riderLocationService.updateLocation(301L, LONGITUDE, LATITUDE));

        assertEquals("骑手账号已禁用或不存在，请重新登录", exception.getMessage());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void updateLocationRejectsSubmittingAnotherRiderLocation() {
        givenActiveRider(301L);

        AuthException exception = assertThrows(AuthException.class,
                () -> riderLocationService.updateLocation(302L, LONGITUDE, LATITUDE));

        assertEquals("只能提交自己的位置信息", exception.getMessage());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void updateLocationRejectsInvalidCoordinates() {
        givenActiveRider(301L);
        double[][] invalidCoordinates = {
                {180.0001, LATITUDE},
                {-180.0001, LATITUDE},
                {LONGITUDE, 90.0001},
                {LONGITUDE, -90.0001},
                {Double.NaN, LATITUDE},
                {LONGITUDE, Double.POSITIVE_INFINITY}
        };

        for (double[] coordinate : invalidCoordinates) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> riderLocationService.updateLocation(301L, coordinate[0], coordinate[1]));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
            assertEquals("经纬度不合法：经度必须在-180到180之间，纬度必须在-90到90之间",
                    exception.getMessage());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void updateLocationFailsWhenScriptReturnsNoResult() {
        givenActiveRider(301L);

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> riderLocationService.updateLocation(301L, LONGITUDE, LATITUDE));

        assertEquals("骑手位置写入未得到成功确认", exception.getMessage());
    }

    @Test
    void updateLocationWrapsRedisFailure() {
        IllegalStateException redisFailure = new IllegalStateException("redis down");
        givenActiveRider(301L);
        doThrow(redisFailure).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any());

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> riderLocationService.updateLocation(301L, LONGITUDE, LATITUDE));

        assertEquals("骑手位置上传失败，请稍后重试", exception.getMessage());
        assertSame(redisFailure, exception.getCause());
    }

    @Test
    void findNearbyRidersReturnsRidersInRedisOrder() {
        Distance firstDistance = new Distance(1.5, Metrics.KILOMETERS);
        Distance secondDistance = new Distance(3.2, Metrics.KILOMETERS);
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = geoResults(List.of(
                geoResult("301", 121.48, 31.23, firstDistance),
                geoResult("302", 121.50, 31.25, secondDistance)
        ));
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(LOCATION_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class))).thenReturn(geoResults);

        List<RiderDistanceVO> nearbyRiders =
                riderLocationService.findNearbyRiders(LONGITUDE, LATITUDE, 5.0);

        assertEquals(2, nearbyRiders.size());
        assertEquals("301", nearbyRiders.get(0).getRiderId());
        assertEquals(firstDistance.toString(), nearbyRiders.get(0).getDistance());
        assertEquals("302", nearbyRiders.get(1).getRiderId());
        assertEquals(secondDistance.toString(), nearbyRiders.get(1).getDistance());
    }

    @Test
    void findNearbyRidersBuildsDistanceSortedLimitedQuery() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(LOCATION_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(List.of()));

        riderLocationService.findNearbyRiders(LONGITUDE, LATITUDE, 5.0);

        ArgumentCaptor<Circle> circleCaptor = ArgumentCaptor.forClass(Circle.class);
        ArgumentCaptor<RedisGeoCommands.GeoRadiusCommandArgs> commandArgsCaptor =
                ArgumentCaptor.forClass(RedisGeoCommands.GeoRadiusCommandArgs.class);
        verify(geoOperations).radius(
                eq(LOCATION_KEY), circleCaptor.capture(), commandArgsCaptor.capture());

        Circle searchArea = circleCaptor.getValue();
        assertEquals(LONGITUDE, searchArea.getCenter().getX(), 1e-9);
        assertEquals(LATITUDE, searchArea.getCenter().getY(), 1e-9);
        assertEquals(5.0, searchArea.getRadius().getValue(), 1e-9);
        assertEquals(Metrics.KILOMETERS, searchArea.getRadius().getMetric());

        RedisGeoCommands.GeoRadiusCommandArgs commandArgs = commandArgsCaptor.getValue();
        assertEquals(100L, commandArgs.getLimit().longValue());
        assertEquals(Sort.Direction.ASC, commandArgs.getSortDirection());
        assertTrue(commandArgs.getFlags().contains(
                RedisGeoCommands.GeoCommandArgs.GeoCommandFlag.withDist()));
    }

    @Test
    void findNearbyRidersReturnsEmptyListWhenNoRiderMatches() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(LOCATION_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(List.of()));

        List<RiderDistanceVO> nearbyRiders =
                riderLocationService.findNearbyRiders(LONGITUDE, LATITUDE, 5.0);

        assertTrue(nearbyRiders.isEmpty());
    }

    @Test
    void findNearbyRidersFailsWhenRedisReturnsNoResult() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(LOCATION_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class))).thenReturn(null);

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> riderLocationService.findNearbyRiders(LONGITUDE, LATITUDE, 5.0));

        assertEquals("GEO查询未返回结果", exception.getMessage());
    }

    @Test
    void findNearbyRidersWrapsRedisFailure() {
        IllegalStateException redisFailure = new IllegalStateException("redis down");
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(LOCATION_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class))).thenThrow(redisFailure);

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> riderLocationService.findNearbyRiders(LONGITUDE, LATITUDE, 5.0));

        assertEquals("附近骑手查询失败，请稍后重试", exception.getMessage());
        assertSame(redisFailure, exception.getCause());
    }

    @Test
    void findNearbyRidersRejectsInvalidRadius() {
        for (double radiusKm : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> riderLocationService.findNearbyRiders(LONGITUDE, LATITUDE, radiusKm));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
            assertEquals("附近距离必须大于0", exception.getMessage());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void findNearbyRidersRejectsInvalidCoordinates() {
        double[][] invalidCoordinates = {
                {180.0001, LATITUDE},
                {-180.0001, LATITUDE},
                {LONGITUDE, 90.0001},
                {LONGITUDE, -90.0001},
                {LONGITUDE, Double.NaN}
        };

        for (double[] coordinate : invalidCoordinates) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> riderLocationService.findNearbyRiders(coordinate[0], coordinate[1], 5.0));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void getRiderLocationReturnsStoredPoint() {
        Point storedPoint = new Point(LONGITUDE, LATITUDE);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(LOCATION_KEY, RIDER_ID)).thenReturn(List.of(storedPoint));

        Optional<Point> result = riderLocationService.getRiderLocation(301L);

        assertTrue(result.isPresent());
        assertEquals(storedPoint, result.get());
    }

    @Test
    void getRiderLocationReturnsEmptyWhenRiderHasNoPosition() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(LOCATION_KEY, RIDER_ID)).thenReturn(List.of());

        assertTrue(riderLocationService.getRiderLocation(301L).isEmpty());
    }

    @Test
    void getRiderLocationReturnsEmptyWhenRedisReturnsNull() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(LOCATION_KEY, RIDER_ID)).thenReturn(null);

        assertTrue(riderLocationService.getRiderLocation(301L).isEmpty());
    }

    @Test
    void getRiderLocationReturnsEmptyWhenMemberIsMissing() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(LOCATION_KEY, RIDER_ID))
                .thenReturn(Arrays.asList((Point) null));

        assertTrue(riderLocationService.getRiderLocation(301L).isEmpty());
    }

    @Test
    void getRiderLocationRejectsNullRiderId() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> riderLocationService.getRiderLocation(null));

        assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
        assertEquals("骑手id不能为空", exception.getMessage());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void getRiderLocationRejectsNonPositiveRiderId() {
        for (Long riderId : new Long[]{0L, -1L}) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> riderLocationService.getRiderLocation(riderId));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
            assertEquals("骑手id必须大于0", exception.getMessage());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void getRiderLocationWrapsRedisFailure() {
        IllegalStateException redisFailure = new IllegalStateException("redis down");
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(LOCATION_KEY, RIDER_ID)).thenThrow(redisFailure);

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> riderLocationService.getRiderLocation(301L));

        assertEquals("查询骑手地理位置失败，riderId=301", exception.getMessage());
        assertSame(redisFailure, exception.getCause());
    }

    @Test
    void removeRiderLocationDeletesGeoAndLastActiveEntries() {
        riderLocationService.removeRiderLocation(301L);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(LOCATION_KEY, LAST_ACTIVE_KEY)),
                eq(RIDER_ID)
        );
    }

    @Test
    void removeRiderLocationRejectsInvalidRiderId() {
        for (Long riderId : new Long[]{null, 0L, -1L}) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> riderLocationService.removeRiderLocation(riderId));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
            assertEquals("骑手ID不合法", exception.getMessage());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void removeRiderLocationWrapsRedisFailure() {
        IllegalStateException redisFailure = new IllegalStateException("redis down");
        doThrow(redisFailure).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any());

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> riderLocationService.removeRiderLocation(301L));

        assertEquals("删除骑手地理位置信息失败", exception.getMessage());
        assertSame(redisFailure, exception.getCause());
    }

    @Test
    void cleanupExpiredRidersStopsWhenBatchIsNotFull() {
        doReturn(37L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any());
        long startedAt = System.currentTimeMillis();

        riderLocationService.cleanupExpiredRiders();
        long finishedAt = System.currentTimeMillis();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(LOCATION_KEY, LAST_ACTIVE_KEY)),
                argThat(value -> value instanceof String text
                        && Long.parseLong(text) >= startedAt - 300_000L
                        && Long.parseLong(text) <= finishedAt - 300_000L),
                eq("100")
        );
    }

    @Test
    void cleanupExpiredRidersKeepsDrainingUntilShortBatch() {
        doReturn(100L, 100L, 12L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any());

        riderLocationService.cleanupExpiredRiders();

        verify(redisTemplate, times(3)).execute(
                any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void cleanupExpiredRidersStopsAtBatchLimit() {
        doReturn(100L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any());

        riderLocationService.cleanupExpiredRiders();

        verify(redisTemplate, times(10)).execute(
                any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void cleanupExpiredRidersFailsWhenScriptReturnsNoResult() {
        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> riderLocationService.cleanupExpiredRiders());

        assertEquals("过期骑手清理未得到执行结果", exception.getMessage());
    }

    @Test
    void cleanupExpiredRidersWrapsRedisFailure() {
        IllegalStateException redisFailure = new IllegalStateException("redis down");
        doThrow(redisFailure).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any());

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> riderLocationService.cleanupExpiredRiders());

        assertEquals("过期骑手位置自动清理失效", exception.getMessage());
        assertSame(redisFailure, exception.getCause());
    }

    private void givenActiveRider(Long riderId) {
        when(riderService.requireActiveRiderId()).thenReturn(riderId);
    }

    private GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult(
            String riderId, double longitude, double latitude, Distance distance) {
        return new GeoResult<>(
                new RedisGeoCommands.GeoLocation<>(riderId, new Point(longitude, latitude)),
                distance
        );
    }

    private GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults(
            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> results) {
        return new GeoResults<>(results);
    }
}
