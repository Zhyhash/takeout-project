package org.example.takeout.integration;

import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskConverter;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskGeoService;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Merchant.Service.FulfillmentService;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Record.MarkReadyResult;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.example.takeout.Rider.Service.RiderService;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(DeliveryTaskGeoTransactionIntegrationTest.TestConfig.class)
class DeliveryTaskGeoTransactionIntegrationTest {
    private static final long TASK_ID = 701L;
    private static final long RIDER_ID = 301L;
    private static final long MERCHANT_ID = 401L;
    private static final long ORDER_ID = 801L;
    private static final int TASK_COUNT = 205;
    private static final long WAIT_SECONDS = 10;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DeliveryTaskGeoService deliveryTaskGeoService;
    @Autowired private DeliveryTaskService deliveryTaskService;
    @Autowired private FulfillmentService fulfillmentService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private GeoOperations<String, String> geoOperations;
    @Autowired private ZSetOperations<String, String> zSetOperations;
    @Autowired private RiderService riderService;
    @Autowired private OrderCommandService orderCommandService;
    @Autowired private MerchantMapper merchantMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS delivery_task");
        jdbcTemplate.execute("""
                CREATE TABLE delivery_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_id BIGINT NOT NULL,
                    rider_id BIGINT NULL,
                    merchant_name VARCHAR(255) NOT NULL,
                    delivery_reward DECIMAL(10,2) NOT NULL,
                    status INT NOT NULL,
                    create_time TIMESTAMP NOT NULL,
                    accepted_time TIMESTAMP NULL,
                    delivered_time TIMESTAMP NULL,
                    update_time TIMESTAMP NULL,
                    receiver_phone VARCHAR(20) NOT NULL,
                    receiver_address VARCHAR(255) NOT NULL,
                    merchant_address VARCHAR(255) NOT NULL,
                    pickup_longitude DECIMAL(10,7) NULL,
                    pickup_latitude DECIMAL(10,7) NULL,
                    merchant_phone VARCHAR(20) NOT NULL,
                    receiver_name VARCHAR(20) NOT NULL
                )
                """);

        reset(redisTemplate, geoOperations, zSetOperations, riderService,
                orderCommandService, merchantMapper);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range(anyString(), anyLong(), anyLong())).thenReturn(Set.of());
        when(geoOperations.add(anyString(), any(Point.class), anyString())).thenReturn(1L);
        when(geoOperations.remove(anyString(), anyString())).thenReturn(1L);
        MerchantContextHolder.clear();
        RiderContextHolder.clear();
    }

    @AfterEach
    void clearContexts() {
        MerchantContextHolder.clear();
        RiderContextHolder.clear();
    }

    @Test
    void reconciliationProcessesAll205TasksWithoutPagination() {
        for (long id = 1; id <= TASK_COUNT; id++) {
            insertWaitingTask(id);
        }
        Set<String> geoMembers = new HashSet<>();
        doAnswer(invocation -> {
            geoMembers.add(invocation.getArgument(2));
            return 1L;
        }).when(geoOperations).add(anyString(), any(Point.class), anyString());

        deliveryTaskGeoService.reconcileAllAvailableTasksGeo();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE status = ? AND rider_id IS NULL",
                Integer.class, DeliveryTaskEnums.WAIT_ASSIGN.getCode())).isEqualTo(TASK_COUNT);
        verify(zSetOperations).range("delivery:available:geo", 0, -1);
        assertThat(geoMembers).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, TASK_COUNT)
                        .mapToObj(Long::toString)
                        .collect(Collectors.toSet()));
        verify(geoOperations, times(TASK_COUNT)).add(
                eq("delivery:available:geo"), any(Point.class), anyString());
    }

    @Test
    void taskLockIsHeldAcrossRedisWriteAndClaimRemovesAfterCommit() throws Exception {
        insertWaitingTask(TASK_ID);
        CountDownLatch geoAddStarted = new CountDownLatch(1);
        CountDownLatch releaseGeoAdd = new CountDownLatch(1);
        CountDownLatch claimStarted = new CountDownLatch(1);
        when(riderService.requireActiveRiderId()).thenAnswer(invocation -> {
            claimStarted.countDown();
            return RIDER_ID;
        });
        doAnswer(invocation -> {
            if (Long.toString(TASK_ID).equals(invocation.getArgument(2))) {
                geoAddStarted.countDown();
                assertTrue(releaseGeoAdd.await(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            return 1L;
        }).when(geoOperations).add(anyString(), any(Point.class), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> reconciliation = executor.submit(
                    () -> deliveryTaskGeoService.reconcileAvailableTaskGeo(TASK_ID));
            assertTrue(geoAddStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));

            Future<?> claim = executor.submit(() -> {
                RiderContextHolder.setRiderId(RIDER_ID);
                try {
                    deliveryTaskService.claimTask(TASK_ID);
                } finally {
                    RiderContextHolder.clear();
                }
            });
            assertTrue(claimStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> claim.get(200, TimeUnit.MILLISECONDS));

            releaseGeoAdd.countDown();
            reconciliation.get(WAIT_SECONDS, TimeUnit.SECONDS);
            claim.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseGeoAdd.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_task WHERE id = ?", Integer.class, TASK_ID))
                .isEqualTo(DeliveryTaskEnums.DELIVERING.getCode());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rider_id FROM delivery_task WHERE id = ?", Long.class, TASK_ID))
                .isEqualTo(RIDER_ID);
        var redisOrder = inOrder(geoOperations);
        redisOrder.verify(geoOperations).add(
                eq("delivery:available:geo"), any(Point.class), eq(Long.toString(TASK_ID)));
        redisOrder.verify(geoOperations).remove(
                "delivery:available:geo", Long.toString(TASK_ID));
    }

    @Test
    void fulfillmentAfterCommitRepairsInAnIndependentTransaction() {
        Merchant merchant = new Merchant();
        merchant.setId(MERCHANT_ID);
        merchant.setAddress("Merchant Road");
        merchant.setPhone("13900139000");
        merchant.setLongitude(new BigDecimal("121.4737000"));
        merchant.setLatitude(new BigDecimal("31.2304000"));
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant);

        Order order = new Order();
        order.setId(ORDER_ID);
        order.setMerchantName("GEO Shop");
        order.setDeliveryFee(new BigDecimal("5.00"));
        order.setReceiverName("Receiver");
        order.setReceiverPhone("13800138000");
        order.setReceiverAddress("Receiver Road");
        AtomicLong fulfillmentSession = new AtomicLong();
        when(orderCommandService.markReadyByMerchant(ORDER_ID, MERCHANT_ID)).thenAnswer(invocation -> {
            fulfillmentSession.set(currentSessionId());
            return new MarkReadyResult(true, order);
        });

        AtomicLong repairSession = new AtomicLong();
        AtomicLong repairedTaskId = new AtomicLong();
        AtomicReference<Integer> committedStatusSeenByRedis = new AtomicReference<>();
        AtomicReference<Boolean> transactionActiveDuringRedisWrite = new AtomicReference<>();
        doAnswer(invocation -> {
            long taskId = Long.parseLong(invocation.getArgument(2));
            repairedTaskId.set(taskId);
            repairSession.set(currentSessionId());
            committedStatusSeenByRedis.set(jdbcTemplate.queryForObject(
                    "SELECT status FROM delivery_task WHERE id = ?", Integer.class, taskId));
            transactionActiveDuringRedisWrite.set(
                    TransactionSynchronizationManager.isActualTransactionActive());
            return 1L;
        }).when(geoOperations).add(anyString(), any(Point.class), anyString());

        MerchantContextHolder.setMerchantId(MERCHANT_ID);
        try {
            fulfillmentService.completePreparation(ORDER_ID);
        } finally {
            MerchantContextHolder.clear();
        }

        assertThat(repairedTaskId.get()).isPositive();
        assertThat(fulfillmentSession.get()).isPositive();
        assertThat(repairSession.get()).isPositive().isNotEqualTo(fulfillmentSession.get());
        assertThat(committedStatusSeenByRedis.get())
                .isEqualTo(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        assertThat(transactionActiveDuringRedisWrite.get()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", Integer.class, ORDER_ID))
                .isEqualTo(1);
    }

    @Test
    void redisFailureRollsBackRepairAndReleasesTheRowLock() {
        insertWaitingTask(TASK_ID);
        when(geoOperations.add(anyString(), any(Point.class), anyString()))
                .thenThrow(new IllegalStateException("simulated Redis failure"));

        assertThrows(RedisGeoOperationException.class,
                () -> deliveryTaskGeoService.reconcileAvailableTaskGeo(TASK_ID));

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        int updatedRows = transaction.execute(status -> jdbcTemplate.update(
                "UPDATE delivery_task SET rider_id = ?, status = ? WHERE id = ? AND status = ?",
                RIDER_ID, DeliveryTaskEnums.DELIVERING.getCode(), TASK_ID,
                DeliveryTaskEnums.WAIT_ASSIGN.getCode()));

        assertThat(updatedRows).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rider_id FROM delivery_task WHERE id = ?", Long.class, TASK_ID))
                .isEqualTo(RIDER_ID);
    }

    private void insertWaitingTask(long taskId) {
        jdbcTemplate.update("""
                INSERT INTO delivery_task (
                    id, order_id, rider_id, merchant_name, delivery_reward, status, create_time,
                    accepted_time, delivered_time, update_time, receiver_phone, receiver_address,
                    merchant_address, pickup_longitude, pickup_latitude, merchant_phone, receiver_name
                ) VALUES (?, ?, NULL, 'GEO Shop', 5.00, ?, CURRENT_TIMESTAMP, NULL, NULL, NULL,
                    '13800138000', 'Receiver Road', 'Merchant Road', 121.4737, 31.2304,
                    '13900139000', 'Receiver')
                """, taskId, taskId + 1000, DeliveryTaskEnums.WAIT_ASSIGN.getCode());
    }

    private long currentSessionId() {
        Long sessionId = jdbcTemplate.queryForObject("SELECT SESSION_ID()", Long.class);
        assertThat(sessionId).isNotNull();
        return sessionId;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(MybatisPlusConfig.class)
    @MapperScan(basePackageClasses = DeliveryTaskMapper.class, annotationClass = Mapper.class)
    static class TestConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:delivery-geo-transaction;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                    "sa", "");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        StringRedisTemplate redisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        GeoOperations<String, String> geoOperations(StringRedisTemplate redisTemplate) {
            GeoOperations<String, String> operations = mock(GeoOperations.class);
            when(redisTemplate.opsForGeo()).thenReturn(operations);
            return operations;
        }

        @Bean
        ZSetOperations<String, String> zSetOperations(StringRedisTemplate redisTemplate) {
            ZSetOperations<String, String> operations = mock(ZSetOperations.class);
            when(redisTemplate.opsForZSet()).thenReturn(operations);
            return operations;
        }

        @Bean
        RiderService riderService() {
            return mock(RiderService.class);
        }

        @Bean
        RiderLocationService riderLocationService() {
            return mock(RiderLocationService.class);
        }

        @Bean
        OrderCommandService orderCommandService() {
            return mock(OrderCommandService.class);
        }

        @Bean
        OrderService orderService() {
            return mock(OrderService.class);
        }

        @Bean
        MerchantMapper merchantMapper() {
            return mock(MerchantMapper.class);
        }

        @Bean
        DeliveryTaskGeoService deliveryTaskGeoService(
                StringRedisTemplate redisTemplate,
                DeliveryTaskMapper deliveryTaskMapper,
                PlatformTransactionManager transactionManager) {
            return new DeliveryTaskGeoService(redisTemplate, deliveryTaskMapper, transactionManager);
        }

        @Bean
        DeliveryTaskService deliveryTaskService(
                DeliveryTaskMapper deliveryTaskMapper,
                OrderCommandService orderCommandService,
                OrderService orderService,
                RiderService riderService,
                RiderLocationService riderLocationService,
                DeliveryTaskGeoService deliveryTaskGeoService) {
            return new DeliveryTaskService(
                    deliveryTaskMapper,
                    orderCommandService,
                    orderService,
                    mock(DeliveryTaskConverter.class),
                    riderService,
                    deliveryTaskGeoService,
                    riderLocationService);
        }

        @Bean
        FulfillmentService fulfillmentService(
                OrderCommandService orderCommandService,
                MerchantMapper merchantMapper,
                DeliveryTaskService deliveryTaskService,
                DeliveryTaskGeoService deliveryTaskGeoService) {
            return new FulfillmentService(
                    orderCommandService, merchantMapper, deliveryTaskService, deliveryTaskGeoService);
        }
    }
}
