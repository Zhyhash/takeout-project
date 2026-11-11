package org.example.takeout.DeliveryTask.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskConverter;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.DeliveryTask.VO.DeliveryTaskDistanceVO;
import org.example.takeout.DeliveryTask.VO.RiderTaskListVO;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.example.takeout.Rider.Service.RiderService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryTaskHallServiceTest {

    private static final long RIDER_ID = 301L;
    private static final Point RIDER_POINT = new Point(121.4737, 31.2304);
    private static final double DEFAULT_RADIUS_KM = 5.0;

    @Mock
    private DeliveryTaskMapper deliveryTaskMapper;

    @Mock
    private OrderCommandService orderCommandService;

    @Mock
    private OrderService orderService;

    @Mock
    private RiderService riderService;

    @Mock
    private DeliveryTaskGeoService deliveryTaskGeoService;

    @Mock
    private RiderLocationService riderLocationService;

    private DeliveryTaskService deliveryTaskService;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DeliveryTask.class);
    }

    @BeforeEach
    void setUp() {
        deliveryTaskService = new DeliveryTaskService(
                deliveryTaskMapper,
                orderCommandService,
                orderService,
                org.mapstruct.factory.Mappers.getMapper(DeliveryTaskConverter.class),
                riderService,
                deliveryTaskGeoService,
                riderLocationService);
    }

    @Test
    void nearbyTasksComeFirstOrderedByDistanceThenById() {
        givenRiderAt(RIDER_POINT);
        givenWaitingTasks(tasks(9001L, 9002L, 9003L, 9004L));
        when(deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, DEFAULT_RADIUS_KM))
                .thenReturn(List.of(distance(9004L, 0.5), distance(9002L, 0.1)));

        List<RiderTaskListVO> result =
                deliveryTaskService.getAvailableRiderTask(DEFAULT_RADIUS_KM);

        assertThat(taskIds(result)).containsExactly(9002L, 9004L, 9001L, 9003L);
        assertThat(result).extracting(RiderTaskListVO::getPickupDistanceKm)
                .containsExactly(0.1, 0.5, null, null);
        verify(deliveryTaskGeoService).findNearbyTasks(121.4737, 31.2304, DEFAULT_RADIUS_KM);
    }

    @Test
    void equalDistanceTasksAreOrderedByTaskId() {
        givenRiderAt(RIDER_POINT);
        givenWaitingTasks(tasks(9102L, 9101L));
        when(deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, DEFAULT_RADIUS_KM))
                .thenReturn(List.of(distance(9102L, 0.3), distance(9101L, 0.3)));

        List<RiderTaskListVO> result =
                deliveryTaskService.getAvailableRiderTask(DEFAULT_RADIUS_KM);

        assertThat(taskIds(result)).containsExactly(9101L, 9102L);
        assertThat(result).extracting(RiderTaskListVO::getPickupDistanceKm).containsExactly(0.3, 0.3);
    }

    @Test
    void geoFailureFallsBackToNewestWaitingTasks() {
        givenRiderAt(RIDER_POINT);
        givenWaitingTasks(tasks(7203L, 7202L, 7201L));
        when(deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, DEFAULT_RADIUS_KM))
                .thenThrow(new RedisGeoOperationException("simulated GEO failure"));

        List<RiderTaskListVO> result =
                deliveryTaskService.getAvailableRiderTask(DEFAULT_RADIUS_KM);

        assertThat(taskIds(result)).containsExactly(7203L, 7202L, 7201L);
        assertThat(result).extracting(RiderTaskListVO::getPickupDistanceKm).containsOnlyNulls();
        verify(deliveryTaskMapper).selectList(any());
    }

    @Test
    void geoFailureFallbackKeepsAtMostFiveHundredTasks() {
        givenRiderAt(RIDER_POINT);
        givenWaitingTasks(tasks(LongStream.rangeClosed(8101L, 8601L).toArray()));
        when(deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, DEFAULT_RADIUS_KM))
                .thenThrow(new RedisGeoOperationException("simulated GEO failure"));

        List<RiderTaskListVO> result =
                deliveryTaskService.getAvailableRiderTask(DEFAULT_RADIUS_KM);

        assertThat(result).hasSize(500);
        assertThat(taskIds(result).get(0)).isEqualTo(8601L);
        assertThat(taskIds(result).get(499)).isEqualTo(8102L);
        assertThat(taskIds(result)).doesNotContain(8101L);
        assertThat(result).extracting(RiderTaskListVO::getPickupDistanceKm).containsOnlyNulls();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Wrapper<DeliveryTask>> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(deliveryTaskMapper).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment())
                .contains("ORDER BY id DESC")
                .contains("LIMIT 500");
    }

    @Test
    void emptyGeoResultFallsBackToNewestWaitingTasks() {
        givenRiderAt(RIDER_POINT);
        givenWaitingTasks(tasks(7101L, 7103L, 7102L));
        when(deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, DEFAULT_RADIUS_KM))
                .thenReturn(List.of());

        List<RiderTaskListVO> result =
                deliveryTaskService.getAvailableRiderTask(DEFAULT_RADIUS_KM);

        assertThat(taskIds(result)).containsExactly(7103L, 7102L, 7101L);
        assertThat(result).extracting(RiderTaskListVO::getPickupDistanceKm).containsOnlyNulls();
    }

    @Test
    void riderWithoutReportedLocationIsRejected() {
        when(riderService.requireActiveRiderId()).thenReturn(RIDER_ID);
        when(riderLocationService.getRiderLocation(RIDER_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> deliveryTaskService.getAvailableRiderTask(DEFAULT_RADIUS_KM));

        assertEquals(ResultCodeEnum.BUSINESS_ERROR, exception.getCodeEnum());
        assertEquals("请先上传骑手位置", exception.getMessage());
        verifyNoInteractions(deliveryTaskGeoService, deliveryTaskMapper);
    }

    @Test
    void riderLocationGeoFailureFallsBackToNewestWaitingTasksWithDatabaseLimit() {
        when(riderService.requireActiveRiderId()).thenReturn(RIDER_ID);
        when(riderLocationService.getRiderLocation(RIDER_ID))
                .thenThrow(new RedisGeoOperationException("rider GEO unavailable"));
        givenWaitingTasks(tasks(7203L, 7202L, 7201L));

        List<RiderTaskListVO> result =
                deliveryTaskService.getAvailableRiderTask(DEFAULT_RADIUS_KM);

        assertThat(taskIds(result)).containsExactly(7203L, 7202L, 7201L);
        assertThat(result).extracting(RiderTaskListVO::getPickupDistanceKm).containsOnlyNulls();
        verifyNoInteractions(deliveryTaskGeoService);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Wrapper<DeliveryTask>> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(deliveryTaskMapper).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment())
                .contains("ORDER BY id DESC")
                .contains("LIMIT 500");
    }

    @Test
    void malformedGeoMembersAndDistancesAreIgnoredWhenOrderingTasks() {
        givenRiderAt(RIDER_POINT);
        givenWaitingTasks(tasks(9001L, 9002L, 9003L));
        DeliveryTaskDistanceVO invalidId = distance("bad-id", 0.0);
        DeliveryTaskDistanceVO nonPositiveId = distance(0L, 0.0);
        DeliveryTaskDistanceVO negativeDistance = distance(9001L, -1.0);
        DeliveryTaskDistanceVO nearby = distance(9002L, 0.1);
        DeliveryTaskDistanceVO nanDistance = distance(9002L, 0.0);
        nanDistance.setDistance(Double.NaN);
        DeliveryTaskDistanceVO infiniteDistance = distance(9003L, 0.0);
        infiniteDistance.setDistance(Double.POSITIVE_INFINITY);
        when(deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, DEFAULT_RADIUS_KM))
                .thenReturn(Arrays.asList(null, invalidId, nonPositiveId, negativeDistance,
                        nearby, nanDistance, infiniteDistance));

        List<RiderTaskListVO> result =
                deliveryTaskService.getAvailableRiderTask(DEFAULT_RADIUS_KM);

        assertThat(taskIds(result)).containsExactly(9002L, 9001L, 9003L);
        assertThat(result).extracting(RiderTaskListVO::getPickupDistanceKm).containsExactly(0.1, null, null);
    }

    @Test
    void zeroGeoPickupDistanceIsPreservedAndNotConfusedWithUnknownDistance() {
        givenRiderAt(RIDER_POINT);
        givenWaitingTasks(tasks(9001L, 9002L));
        when(deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, DEFAULT_RADIUS_KM))
                .thenReturn(List.of(distance(9002L, 0.0)));

        List<RiderTaskListVO> result =
                deliveryTaskService.getAvailableRiderTask(DEFAULT_RADIUS_KM);

        assertThat(taskIds(result)).containsExactly(9002L, 9001L);
        assertThat(result).extracting(RiderTaskListVO::getPickupDistanceKm)
                .containsExactly(0.0, null);
        verify(deliveryTaskGeoService).findNearbyTasks(121.4737, 31.2304, DEFAULT_RADIUS_KM);
    }

    @Test
    void nonPositiveOrNonFiniteRadiusIsRejectedBeforeAnyLookup() {
        double[] invalidRadii = {0, -1, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY};

        for (double radiusKm : invalidRadii) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> deliveryTaskService.getAvailableRiderTask(radiusKm));

            assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
            assertEquals("查询半径必须大于0", exception.getMessage());
        }

        verifyNoInteractions(riderService, riderLocationService,
                deliveryTaskGeoService, deliveryTaskMapper);
    }

    @Test
    void requestedRadiusIsPassedToGeoQuery() {
        givenRiderAt(RIDER_POINT);
        givenWaitingTasks(tasks(9201L));
        when(deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, 2.0))
                .thenReturn(List.of(distance(9201L, 1.2)));

        List<RiderTaskListVO> result = deliveryTaskService.getAvailableRiderTask(2.0);

        assertThat(taskIds(result)).containsExactly(9201L);
        verify(deliveryTaskGeoService).findNearbyTasks(121.4737, 31.2304, 2.0);
    }

    @Test
    void noWaitingTasksReturnsEmptyList() {
        givenRiderAt(RIDER_POINT);
        givenWaitingTasks(List.of());
        when(deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, DEFAULT_RADIUS_KM))
                .thenReturn(List.of(distance(9001L, 0.1)));

        assertThat(deliveryTaskService.getAvailableRiderTask(DEFAULT_RADIUS_KM)).isEmpty();
    }

    private void givenRiderAt(Point point) {
        when(riderService.requireActiveRiderId()).thenReturn(RIDER_ID);
        when(riderLocationService.getRiderLocation(RIDER_ID)).thenReturn(Optional.of(point));
    }

    private void givenWaitingTasks(List<DeliveryTask> tasks) {
        when(deliveryTaskMapper.selectList(any()))
                .thenAnswer(invocation -> {
                    Wrapper<DeliveryTask> query = invocation.getArgument(0);
                    List<DeliveryTask> result = new ArrayList<>(tasks);
                    if (query.getSqlSegment().contains("ORDER BY id DESC")) {
                        result.sort(Comparator.comparing(DeliveryTask::getId).reversed());
                        return result.subList(0, Math.min(result.size(), 500));
                    }
                    return result;
                });
    }

    private List<DeliveryTask> tasks(long... ids) {
        List<DeliveryTask> result = new ArrayList<>();
        for (long id : ids) {
            DeliveryTask task = new DeliveryTask();
            task.setId(id);
            task.setStatus(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
            task.setMerchantName("实验商家 " + id);
            task.setMerchantAddress("商家地址");
            task.setReceiverAddress("收货地址");
            task.setDeliveryReward(BigDecimal.valueOf(5));
            result.add(task);
        }
        return result;
    }

    private DeliveryTaskDistanceVO distance(long taskId, double kilometers) {
        return distance(String.valueOf(taskId), kilometers);
    }

    private DeliveryTaskDistanceVO distance(String taskId, double kilometers) {
        return new DeliveryTaskDistanceVO(
                String.valueOf(taskId),
                new Distance(kilometers, Metrics.KILOMETERS));
    }

    private List<Long> taskIds(List<RiderTaskListVO> tasks) {
        return tasks.stream().map(RiderTaskListVO::getTaskId).toList();
    }
}
