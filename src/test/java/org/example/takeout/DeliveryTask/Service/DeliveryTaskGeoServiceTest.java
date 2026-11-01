package org.example.takeout.DeliveryTask.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.DeliveryTask.VO.DeliveryTaskDistanceVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryTaskGeoServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), DeliveryTask.class);
    }

    private static final String TASK_GEO_KEY = "delivery:available:geo";
    private static final String TASK_ID = "501";
    private static final double LONGITUDE = 121.4737;
    private static final double LATITUDE = 31.2304;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private GeoOperations<String, String> geoOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private DeliveryTaskMapper deliveryTaskMapper;

    private DeliveryTaskGeoService deliveryTaskGeoService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        deliveryTaskGeoService = new DeliveryTaskGeoService(
                redisTemplate, deliveryTaskMapper, transactionManager);
    }

    @Test
    void addAvailableTaskWritesGeoPoint() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.add(eq(TASK_GEO_KEY), any(Point.class), eq(TASK_ID))).thenReturn(1L);

        deliveryTaskGeoService.addAvailableTask(501L, LONGITUDE, LATITUDE);

        ArgumentCaptor<Point> pointCaptor = ArgumentCaptor.forClass(Point.class);
        verify(geoOperations).add(eq(TASK_GEO_KEY), pointCaptor.capture(), eq(TASK_ID));
        assertEquals(LONGITUDE, pointCaptor.getValue().getX(), 1e-9);
        assertEquals(LATITUDE, pointCaptor.getValue().getY(), 1e-9);
    }

    @Test
    void addAvailableTaskAcceptsZeroAsExistingMemberUpdate() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.add(eq(TASK_GEO_KEY), any(Point.class), eq(TASK_ID))).thenReturn(0L);

        assertDoesNotThrow(() -> deliveryTaskGeoService.addAvailableTask(501L, LONGITUDE, LATITUDE));
    }

    @Test
    void addAvailableTaskAcceptsBoundaryCoordinates() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.add(eq(TASK_GEO_KEY), any(Point.class), eq(TASK_ID))).thenReturn(1L);

        deliveryTaskGeoService.addAvailableTask(501L, 0, 0);
        deliveryTaskGeoService.addAvailableTask(501L, 180, 85.05112878);
        deliveryTaskGeoService.addAvailableTask(501L, -180, -85.05112878);

        verify(geoOperations, times(3)).add(eq(TASK_GEO_KEY), any(Point.class), eq(TASK_ID));
    }

    @Test
    void addAvailableTaskFailsWhenRedisReturnsNoResult() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        doReturn(null).when(geoOperations)
                .add(eq(TASK_GEO_KEY), any(Point.class), eq(TASK_ID));

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.addAvailableTask(501L, LONGITUDE, LATITUDE));

        assertEquals("配送任务位置写入未得到成功确认，taskId=501", exception.getMessage());
    }

    @Test
    void addAvailableTaskWrapsRedisFailure() {
        IllegalStateException redisFailure = new IllegalStateException("redis down");
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.add(eq(TASK_GEO_KEY), any(Point.class), eq(TASK_ID)))
                .thenThrow(redisFailure);

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.addAvailableTask(501L, LONGITUDE, LATITUDE));

        assertEquals("可接配送任务写入失败，taskId=501", exception.getMessage());
        assertSame(redisFailure, exception.getCause());
    }

    @Test
    void addAvailableTaskRejectsInvalidTaskId() {
        for (Long taskId : new Long[]{null, 0L, -1L}) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> deliveryTaskGeoService.addAvailableTask(taskId, LONGITUDE, LATITUDE));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
            assertEquals("配送任务ID不合法", exception.getMessage());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void addAvailableTaskRejectsInvalidCoordinates() {
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
                    () -> deliveryTaskGeoService.addAvailableTask(501L, coordinate[0], coordinate[1]));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
            assertEquals("经纬度不合法：经度必须在-180到180之间，纬度必须在-85.05112878到85.05112878之间",
                    exception.getMessage());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void removeAvailableTaskDeletesGeoEntry() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq(TASK_ID))).thenReturn(1L);

        deliveryTaskGeoService.removeAvailableTask(501L);

        verify(geoOperations).remove(TASK_GEO_KEY, TASK_ID);
    }

    @Test
    void removeAvailableTaskTreatsMissingMemberAsIdempotentSuccess() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq(TASK_ID))).thenReturn(0L);

        assertDoesNotThrow(() -> deliveryTaskGeoService.removeAvailableTask(501L));
    }

    @Test
    void removeAvailableTaskFailsWhenRedisReturnsNoResult() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        doReturn(null).when(geoOperations).remove(eq(TASK_GEO_KEY), eq(TASK_ID));

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.removeAvailableTask(501L));

        assertEquals("配送任务位置删除未得到成功确认，taskId=501", exception.getMessage());
    }

    @Test
    void removeAvailableTaskWrapsRedisFailure() {
        IllegalStateException redisFailure = new IllegalStateException("redis down");
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq(TASK_ID))).thenThrow(redisFailure);

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.removeAvailableTask(501L));

        assertEquals("可接配送任务删除失败，taskId=501", exception.getMessage());
        assertSame(redisFailure, exception.getCause());
    }

    @Test
    void removeAvailableTaskRejectsInvalidTaskId() {
        for (Long taskId : new Long[]{null, 0L, -1L}) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> deliveryTaskGeoService.removeAvailableTask(taskId));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
            assertEquals("配送任务ID不合法", exception.getMessage());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void findNearbyTasksReturnsTasksInRedisOrder() {
        Distance firstDistance = new Distance(1.5, Metrics.KILOMETERS);
        Distance secondDistance = new Distance(3.2, Metrics.KILOMETERS);
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = geoResults(List.of(
                geoResult("501", 121.48, 31.23, firstDistance),
                geoResult("502", 121.50, 31.25, secondDistance)
        ));
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(TASK_GEO_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class))).thenReturn(geoResults);

        List<DeliveryTaskDistanceVO> nearbyTasks =
                deliveryTaskGeoService.findNearbyTasks(LONGITUDE, LATITUDE, 5.0);

        assertEquals(2, nearbyTasks.size());
        assertEquals("501", nearbyTasks.get(0).getTaskId());
        assertEquals(firstDistance.getValue(), nearbyTasks.get(0).getDistance(), 1e-9);
        assertEquals("502", nearbyTasks.get(1).getTaskId());
        assertEquals(secondDistance.getValue(), nearbyTasks.get(1).getDistance(), 1e-9);
    }

    @Test
    void findNearbyTasksBuildsDistanceSortedLimitedQuery() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(TASK_GEO_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(List.of()));

        deliveryTaskGeoService.findNearbyTasks(LONGITUDE, LATITUDE, 5.0);

        ArgumentCaptor<Circle> circleCaptor = ArgumentCaptor.forClass(Circle.class);
        ArgumentCaptor<RedisGeoCommands.GeoRadiusCommandArgs> commandArgsCaptor =
                ArgumentCaptor.forClass(RedisGeoCommands.GeoRadiusCommandArgs.class);
        verify(geoOperations).radius(
                eq(TASK_GEO_KEY), circleCaptor.capture(), commandArgsCaptor.capture());

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
    void findNearbyTasksReturnsEmptyListWhenNoTaskMatches() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(TASK_GEO_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(List.of()));

        assertTrue(deliveryTaskGeoService.findNearbyTasks(LONGITUDE, LATITUDE, 5.0).isEmpty());
    }

    @Test
    void findNearbyTasksFailsWhenRedisReturnsNoResult() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(TASK_GEO_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class))).thenReturn(null);

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.findNearbyTasks(LONGITUDE, LATITUDE, 5.0));

        assertEquals("GEO查询未返回结果", exception.getMessage());
    }

    @Test
    void findNearbyTasksWrapsRedisFailure() {
        IllegalStateException redisFailure = new IllegalStateException("redis down");
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(TASK_GEO_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class))).thenThrow(redisFailure);

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.findNearbyTasks(LONGITUDE, LATITUDE, 5.0));

        assertEquals("附近可接配送任务查询失败，请稍后重试", exception.getMessage());
        assertSame(redisFailure, exception.getCause());
    }

    @Test
    void findNearbyTasksRejectsInvalidRadius() {
        for (double radiusKm : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> deliveryTaskGeoService.findNearbyTasks(LONGITUDE, LATITUDE, radiusKm));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
            assertEquals("附近距离必须大于0", exception.getMessage());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void findNearbyTasksRejectsInvalidCoordinates() {
        double[][] invalidCoordinates = {
                {180.0001, LATITUDE},
                {-180.0001, LATITUDE},
                {LONGITUDE, 85.05112879},
                {LONGITUDE, -85.05112879},
                {LONGITUDE, Double.NaN}
        };

        for (double[] coordinate : invalidCoordinates) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> deliveryTaskGeoService.findNearbyTasks(coordinate[0], coordinate[1], 5.0));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void getAvailableTaskLocationReturnsStoredPoint() {
        Point storedPoint = new Point(LONGITUDE, LATITUDE);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(TASK_GEO_KEY, TASK_ID)).thenReturn(List.of(storedPoint));

        Optional<Point> result = deliveryTaskGeoService.getAvailableTaskLocation(501L);

        assertTrue(result.isPresent());
        assertEquals(storedPoint, result.get());
    }

    @Test
    void getAvailableTaskLocationReturnsEmptyWhenTaskHasNoPosition() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(TASK_GEO_KEY, TASK_ID)).thenReturn(List.of());

        assertTrue(deliveryTaskGeoService.getAvailableTaskLocation(501L).isEmpty());
    }

    @Test
    void getAvailableTaskLocationFailsWhenRedisReturnsNoResult() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(TASK_GEO_KEY, TASK_ID)).thenReturn(null);

        assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.getAvailableTaskLocation(501L));
    }

    @Test
    void getAvailableTaskLocationReturnsEmptyWhenMemberIsMissing() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(TASK_GEO_KEY, TASK_ID))
                .thenReturn(Arrays.asList((Point) null));

        assertTrue(deliveryTaskGeoService.getAvailableTaskLocation(501L).isEmpty());
    }

    @Test
    void getAvailableTaskLocationRejectsInvalidTaskId() {
        for (Long taskId : new Long[]{null, 0L, -1L}) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> deliveryTaskGeoService.getAvailableTaskLocation(taskId));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
            assertEquals("配送任务ID不合法", exception.getMessage());
        }

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void getAvailableTaskLocationWrapsRedisFailure() {
        IllegalStateException redisFailure = new IllegalStateException("redis down");
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(TASK_GEO_KEY, TASK_ID)).thenThrow(redisFailure);

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.getAvailableTaskLocation(501L));

        assertEquals("查询配送任务地理位置失败，taskId=501", exception.getMessage());
        assertSame(redisFailure, exception.getCause());
    }

    @Test
    void findNearbyTasksFiltersNonCanonicalTaskIds() {
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = geoResults(List.of(
                geoResult("0501", 121.47, 31.23, new Distance(0.2, Metrics.KILOMETERS)),
                geoResult("-1", 121.47, 31.23, new Distance(0.3, Metrics.KILOMETERS)),
                geoResult("501", 121.47, 31.23, new Distance(0.4, Metrics.KILOMETERS))));
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(TASK_GEO_KEY), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class))).thenReturn(results);

        List<DeliveryTaskDistanceVO> nearby =
                deliveryTaskGeoService.findNearbyTasks(LONGITUDE, LATITUDE, 5.0);

        assertEquals(List.of("501"), nearby.stream().map(DeliveryTaskDistanceVO::getTaskId).toList());
    }

    @Test
    void reconcileAllAvailableTasksGeoRepairsOnlyMembersInSymmetricDifference() {
        DeliveryTask withoutCoordinates = task(501L, null, null);
        DeliveryTask withCoordinates =
                task(502L, new BigDecimal("121.4737"), new BigDecimal("31.2304"));
        DeliveryTask existingMember =
                task(503L, new BigDecimal("121.5100"), new BigDecimal("31.2600"));
        when(deliveryTaskMapper.selectWaitAssignTasks(DeliveryTaskEnums.WAIT_ASSIGN.getCode()))
                .thenReturn(List.of(withoutCoordinates, withCoordinates, existingMember));
        mockRedisRangeMembers("503", "600");
        mockTaskLookups(Map.of(
                501L, withoutCoordinates,
                502L, withCoordinates,
                503L, existingMember));
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq("501"))).thenReturn(1L);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq("600"))).thenReturn(1L);
        when(geoOperations.add(eq(TASK_GEO_KEY), any(Point.class), eq("502"))).thenReturn(1L);

        assertDoesNotThrow(() -> deliveryTaskGeoService.reconcileAllAvailableTasksGeo());

        verify(geoOperations).remove(TASK_GEO_KEY, "501");
        verify(geoOperations).remove(TASK_GEO_KEY, "600");
        verify(geoOperations).add(eq(TASK_GEO_KEY), any(Point.class), eq("502"));
        verify(deliveryTaskMapper).selectWaitAssignTasks(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        verify(zSetOperations).range(TASK_GEO_KEY, 0, -1);
        verify(geoOperations, never()).remove(TASK_GEO_KEY, "503");
        verify(geoOperations, never()).add(eq(TASK_GEO_KEY), any(Point.class), eq("503"));

        ArgumentCaptor<LambdaQueryWrapper<DeliveryTask>> taskLookupCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(deliveryTaskMapper, times(3)).selectOne(taskLookupCaptor.capture());
        Set<Long> lookedUpTaskIds = taskLookupCaptor.getAllValues().stream()
                .map(this::boundTaskId)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(501L, 502L, 600L), lookedUpTaskIds);
    }

    @Test
    void reconcileAllAvailableTasksGeoRemovesRedisOnlyMember() {
        mockRedisRangeMembers("600");
        when(deliveryTaskMapper.selectOne(any())).thenReturn(null);
        when(deliveryTaskMapper.selectWaitAssignTasks(DeliveryTaskEnums.WAIT_ASSIGN.getCode()))
                .thenReturn(List.of());
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq("600"))).thenReturn(1L);

        deliveryTaskGeoService.reconcileAllAvailableTasksGeo();

        verify(geoOperations).remove(TASK_GEO_KEY, "600");
        verify(zSetOperations).range(TASK_GEO_KEY, 0, -1);
    }

    @Test
    void reconcileAllAvailableTasksGeoDeletesNonCanonicalRedisMembers() {
        mockRedisRangeMembers("0501", "not-a-task-id", Long.toString(Long.MAX_VALUE) + "0");
        when(deliveryTaskMapper.selectWaitAssignTasks(DeliveryTaskEnums.WAIT_ASSIGN.getCode()))
                .thenReturn(List.of());
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), any(String.class))).thenReturn(1L);

        assertDoesNotThrow(() -> deliveryTaskGeoService.reconcileAllAvailableTasksGeo());

        verify(geoOperations).remove(TASK_GEO_KEY, "0501");
        verify(geoOperations).remove(TASK_GEO_KEY, "not-a-task-id");
        verify(geoOperations).remove(TASK_GEO_KEY, Long.toString(Long.MAX_VALUE) + "0");
    }

    @Test
    void reconcileAllAvailableTasksGeoContinuesAfterMalformedMemberRemovalFailure() {
        DeliveryTask waitingTask = task(502L,
                new BigDecimal("121.5000"), new BigDecimal("31.2500"));
        mockRedisRangeMembers("not-a-task-id", "also-invalid");
        when(deliveryTaskMapper.selectWaitAssignTasks(DeliveryTaskEnums.WAIT_ASSIGN.getCode()))
                .thenReturn(List.of(waitingTask));
        mockTaskLookups(Map.of(502L, waitingTask));
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq("not-a-task-id")))
                .thenThrow(new IllegalStateException("redis remove failed"));
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq("also-invalid"))).thenReturn(1L);
        when(geoOperations.add(eq(TASK_GEO_KEY), any(Point.class), eq("502"))).thenReturn(1L);

        assertDoesNotThrow(() -> deliveryTaskGeoService.reconcileAllAvailableTasksGeo());

        verify(geoOperations).remove(TASK_GEO_KEY, "not-a-task-id");
        verify(geoOperations).remove(TASK_GEO_KEY, "also-invalid");
        verify(geoOperations).add(eq(TASK_GEO_KEY), any(Point.class), eq("502"));
    }

    @Test
    void reconcileAvailableTaskGeoRemovesLocationForMissingTask() {
        when(deliveryTaskMapper.selectOne(any())).thenReturn(null);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq(TASK_ID))).thenReturn(0L);

        assertDoesNotThrow(() -> deliveryTaskGeoService.reconcileAvailableTaskGeo(501L));

        verify(geoOperations).remove(TASK_GEO_KEY, TASK_ID);
    }

    @Test
    void reconcileAvailableTaskGeoRemovesLocationWhenWaitingTaskHasNoCoordinates() {
        when(deliveryTaskMapper.selectOne(any())).thenReturn(task(501L, null, null));
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq(TASK_ID))).thenReturn(1L);

        deliveryTaskGeoService.reconcileAvailableTaskGeo(501L);

        verify(geoOperations).remove(TASK_GEO_KEY, TASK_ID);
        verify(geoOperations, never()).add(anyString(), any(Point.class), anyString());
    }

    @Test
    void reconcileAvailableTaskGeoRemovesLocationWhenCoordinatesAreOutsideRedisRange() {
        when(deliveryTaskMapper.selectOne(any())).thenReturn(
                task(501L, new BigDecimal("121.4737"), new BigDecimal("90")));
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq(TASK_ID))).thenReturn(1L);

        deliveryTaskGeoService.reconcileAvailableTaskGeo(501L);

        verify(geoOperations).remove(TASK_GEO_KEY, TASK_ID);
        verify(geoOperations, never()).add(anyString(), any(Point.class), anyString());
    }

    @Test
    void reconcileAvailableTaskGeoUsesIndependentTransactionAndCommitsAfterLockedGeoWrite() {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(deliveryTaskMapper.selectOne(any())).thenReturn(
                task(501L, new BigDecimal("121.4737"), new BigDecimal("31.2304")));
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.add(eq(TASK_GEO_KEY), any(Point.class), eq(TASK_ID))).thenReturn(1L);

        deliveryTaskGeoService.reconcileAvailableTaskGeo(501L);

        ArgumentCaptor<TransactionDefinition> transactionCaptor =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        ArgumentCaptor<Point> pointCaptor = ArgumentCaptor.forClass(Point.class);
        ArgumentCaptor<LambdaQueryWrapper<DeliveryTask>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        InOrder operationOrder = inOrder(transactionManager, deliveryTaskMapper, geoOperations);
        operationOrder.verify(transactionManager).getTransaction(transactionCaptor.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                transactionCaptor.getValue().getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                transactionCaptor.getValue().getIsolationLevel());

        operationOrder.verify(deliveryTaskMapper).selectOne(wrapperCaptor.capture());
        operationOrder.verify(geoOperations).add(eq(TASK_GEO_KEY), pointCaptor.capture(), eq(TASK_ID));
        operationOrder.verify(transactionManager).commit(transactionStatus);

        assertEquals(121.4737, pointCaptor.getValue().getX(), 1e-9);
        assertEquals(31.2304, pointCaptor.getValue().getY(), 1e-9);
        assertLockedTaskLookup(wrapperCaptor.getValue(), 501L);
    }

    @Test
    void reconcileAvailableTaskGeoRollsBackLockedRowWhenRedisUpdateFails() {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(deliveryTaskMapper.selectOne(any())).thenReturn(
                task(501L, new BigDecimal("121.4737"), new BigDecimal("31.2304")));
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.add(eq(TASK_GEO_KEY), any(Point.class), eq(TASK_ID)))
                .thenThrow(new IllegalStateException("redis down"));

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.reconcileAvailableTaskGeo(501L));
        assertEquals("可接配送任务写入失败，taskId=501", exception.getMessage());

        ArgumentCaptor<LambdaQueryWrapper<DeliveryTask>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        InOrder operationOrder = inOrder(transactionManager, deliveryTaskMapper, geoOperations);
        operationOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        operationOrder.verify(deliveryTaskMapper).selectOne(wrapperCaptor.capture());
        operationOrder.verify(geoOperations).add(eq(TASK_GEO_KEY), any(Point.class), eq(TASK_ID));
        operationOrder.verify(transactionManager).rollback(transactionStatus);

        assertLockedTaskLookup(wrapperCaptor.getValue(), 501L);
        verify(transactionManager, never()).commit(transactionStatus);
    }

    @Test
    void reconcileAvailableTaskGeoRemovesNonWaitingOrAssignedTask() {
        DeliveryTask delivering =
                task(501L, new BigDecimal("121.4737"), new BigDecimal("31.2304"));
        delivering.setStatus(DeliveryTaskEnums.DELIVERING.getCode());
        when(deliveryTaskMapper.selectOne(any())).thenReturn(delivering);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq(TASK_ID))).thenReturn(1L);

        deliveryTaskGeoService.reconcileAvailableTaskGeo(501L);

        verify(geoOperations).remove(TASK_GEO_KEY, TASK_ID);
    }

    @Test
    void reconcileAvailableTaskGeoRemovesLocationForInconsistentAssignedWaitingTask() {
        DeliveryTask assigned = task(501L,
                new BigDecimal("121.4737"), new BigDecimal("31.2304"));
        assigned.setRiderId(99L);
        when(deliveryTaskMapper.selectOne(any())).thenReturn(assigned);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(eq(TASK_GEO_KEY), eq(TASK_ID))).thenReturn(1L);

        deliveryTaskGeoService.reconcileAvailableTaskGeo(501L);

        verify(geoOperations).remove(TASK_GEO_KEY, TASK_ID);
        verify(geoOperations, never()).add(anyString(), any(Point.class), anyString());
    }

    @Test
    void reconcileAllAvailableTasksGeoContinuesAfterIndividualMemberFailure() {
        DeliveryTask firstTask = task(501L,
                new BigDecimal("121.4737"), new BigDecimal("31.2304"));
        DeliveryTask secondTask = task(502L,
                new BigDecimal("121.5000"), new BigDecimal("31.2500"));
        mockRedisRangeMembers();
        when(deliveryTaskMapper.selectWaitAssignTasks(DeliveryTaskEnums.WAIT_ASSIGN.getCode()))
                .thenReturn(List.of(firstTask, secondTask));
        mockTaskLookups(Map.of(501L, firstTask, 502L, secondTask));
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.add(eq(TASK_GEO_KEY), any(Point.class), eq("501")))
                .thenThrow(new IllegalStateException("redis down"));
        when(geoOperations.add(eq(TASK_GEO_KEY), any(Point.class), eq("502"))).thenReturn(1L);

        assertDoesNotThrow(() -> deliveryTaskGeoService.reconcileAllAvailableTasksGeo());

        verify(geoOperations).add(eq(TASK_GEO_KEY), any(Point.class), eq("501"));
        verify(geoOperations).add(eq(TASK_GEO_KEY), any(Point.class), eq("502"));
    }

    @Test
    void reconcileAllAvailableTasksGeoWrapsRedisRangeFailure() {
        when(deliveryTaskMapper.selectWaitAssignTasks(DeliveryTaskEnums.WAIT_ASSIGN.getCode()))
                .thenReturn(List.of());
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range(TASK_GEO_KEY, 0, -1))
                .thenThrow(new IllegalStateException("redis down"));

        RedisGeoOperationException exception = assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.reconcileAllAvailableTasksGeo());

        assertEquals("可接配送任务 GEO 全量对账失败", exception.getMessage());
    }

    private void mockRedisRangeMembers(String... members) {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        Set<String> redisMembers = new HashSet<>(Arrays.asList(members));
        when(zSetOperations.range(TASK_GEO_KEY, 0, -1)).thenReturn(redisMembers);
    }

    private void mockTaskLookups(Map<Long, DeliveryTask> tasksById) {
        when(deliveryTaskMapper.selectOne(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            LambdaQueryWrapper<DeliveryTask> wrapper =
                    (LambdaQueryWrapper<DeliveryTask>) invocation.getArgument(0);
            return tasksById.get(boundTaskId(wrapper));
        });
    }

    private void assertLockedTaskLookup(LambdaQueryWrapper<DeliveryTask> wrapper, Long taskId) {
        String sqlSegment = wrapper.getSqlSegment().toUpperCase();
        assertTrue(sqlSegment.contains("FOR UPDATE"));
        assertEquals(taskId, boundTaskId(wrapper));
    }

    private Long boundTaskId(LambdaQueryWrapper<DeliveryTask> wrapper) {
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values().stream()
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).longValue())
                .findFirst()
                .orElse(null);
    }

    private DeliveryTask task(Long taskId, BigDecimal longitude, BigDecimal latitude) {
        DeliveryTask task = new DeliveryTask();
        task.setId(taskId);
        task.setStatus(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        task.setPickupLongitude(longitude);
        task.setPickupLatitude(latitude);
        return task;
    }

    private GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult(
            String taskId, double longitude, double latitude, Distance distance) {
        return new GeoResult<>(
                new RedisGeoCommands.GeoLocation<>(taskId, new Point(longitude, latitude)),
                distance
        );
    }

    private GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults(
            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> results) {
        return new GeoResults<>(results);
    }
}
