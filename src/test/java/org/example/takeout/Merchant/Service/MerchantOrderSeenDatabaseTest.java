package org.example.takeout.Merchant.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.example.takeout.Cart.Service.CartCheckoutService;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Merchant.Controller.MerchantControllerSold;
import org.example.takeout.Merchant.Mapper.MerchantOrderConverter;
import org.example.takeout.Order.Assembler.OrderVOAssembler;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderItemService;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Order.Service.OrderTransactionExecutor;
import org.example.takeout.Order.Support.OrderProductSummaryBuilder;
import org.example.takeout.Order.Support.OrderRequestFingerprint;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Properties;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实 H2、MyBatis-Plus Mapper、服务及 MapStruct 验证查询与 ACK 的 HTTP 闭环。
 * 身份由测试直接放入上下文；JWT/权限拦截由 API 测试覆盖，MySQL 迁移由 Flyway 集成测试覆盖。
 */
class MerchantOrderSeenDatabaseTest {
    private static final long MERCHANT_ID = 201L;
    private static final long OTHER_MERCHANT_ID = 202L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 17, 10, 0);

    private SqlSession session;
    private JdbcTemplate jdbcTemplate;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:merchant-order-seen-" + UUID.randomUUID() + ";MODE=MySQL");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("merchant-order-seen-test",
                new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        PageInterceptor pagination = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("helperDialect", "h2");
        pagination.setProperties(properties);
        configuration.addInterceptor(pagination);
        configuration.addMapper(OrderMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        // 保持会话连接，直到本用例结束；独立内存库不会连接本地 MySQL/Redis。
        session.getConnection();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    order_no VARCHAR(64),
                    user_id BIGINT,
                    request_id VARCHAR(64),
                    request_hash VARCHAR(64),
                    merchant_id BIGINT,
                    merchant_name VARCHAR(255),
                    total_amount DECIMAL(10, 2),
                    delivery_fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
                    original_amount DECIMAL(10, 2),
                    discount_amount DECIMAL(10, 2),
                    status INT,
                    cancel_source INT,
                    receiver_name VARCHAR(20),
                    receiver_phone VARCHAR(20),
                    receiver_address VARCHAR(255),
                    remark VARCHAR(200),
                    create_time TIMESTAMP,
                    update_time TIMESTAMP,
                    finish_time TIMESTAMP,
                    pay_time TIMESTAMP,
                    timeout_cancel_available_at TIMESTAMP,
                    merchant_seen BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
        OrderItemService orderItemService = mock(OrderItemService.class);
        MerchantSseService sseService = mock(MerchantSseService.class);
        OrderService orderService = new OrderService(mock(OrderTransactionExecutor.class),
                orderItemService, mock(OrderItemMapper.class), session.getMapper(OrderMapper.class),
                mock(OrderVOAssembler.class), mock(OrderConvertor.class), mock(CartCheckoutService.class),
                new OrderRequestFingerprint(), sseService,
                new org.example.takeout.Order.Domain.DeliveryFeeCalculator(), null);
        MerchantOrderViewService viewService = new MerchantOrderViewService(
                Mappers.getMapper(MerchantOrderConverter.class), orderItemService,
                orderService, new OrderProductSummaryBuilder());
        mockMvc = MockMvcBuilders.standaloneSetup(new MerchantControllerSold(
                mock(MerchantAccountService.class), mock(FulfillmentService.class), viewService, sseService)).build();
        MerchantContextHolder.setMerchantId(MERCHANT_ID);
    }

    @AfterEach
    void tearDown() {
        MerchantContextHolder.clear();
        PageHelper.clearPage();
        if (session != null) {
            session.close();
        }
    }

    @Test
    void pendingListSerializesPersistedSeenFlagsWithoutAcknowledgingThem() throws Exception {
        insertOrder(501, MERCHANT_ID, OrderStatusEnum.PAID, false, CREATED_AT);
        insertOrder(502, MERCHANT_ID, OrderStatusEnum.PAID, true, CREATED_AT.plusMinutes(1));
        insertOrder(503, OTHER_MERCHANT_ID, OrderStatusEnum.PAID, false, CREATED_AT.plusMinutes(2));

        mockMvc.perform(get("/merchant/orders").param("type", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[*].orderId", contains(502, 501)))
                .andExpect(jsonPath("$.data.list[0].merchantSeen").value(true))
                .andExpect(jsonPath("$.data.list[1].merchantSeen").value(false));

        assertEquals(false, seen(501));
        assertUnseen(501);
    }

    @Test
    void unseenRecoveryFiltersMerchantStatusAndSeenAndUsesStableOldestFirstOrder() throws Exception {
        insertOrder(504, MERCHANT_ID, OrderStatusEnum.PAID, false, CREATED_AT.plusMinutes(1));
        insertOrder(502, MERCHANT_ID, OrderStatusEnum.PAID, false, CREATED_AT);
        insertOrder(501, MERCHANT_ID, OrderStatusEnum.PAID, false, CREATED_AT);
        insertOrder(505, MERCHANT_ID, OrderStatusEnum.PAID, true, CREATED_AT.minusMinutes(1));
        insertOrder(506, OTHER_MERCHANT_ID, OrderStatusEnum.PAID, false, CREATED_AT.minusMinutes(2));
        insertNonPaidOrders();

        assertUnseen(501, 502, 504);
    }

    @Test
    void mixedAcknowledgementChangesOnlyCurrentMerchantsUnseenPaidOrdersAndIsIdempotent() throws Exception {
        insertOrder(501, MERCHANT_ID, OrderStatusEnum.PAID, false, CREATED_AT);
        insertOrder(502, MERCHANT_ID, OrderStatusEnum.PAID, true, CREATED_AT);
        insertOrder(503, OTHER_MERCHANT_ID, OrderStatusEnum.PAID, false, CREATED_AT);
        insertOrder(504, MERCHANT_ID, OrderStatusEnum.PAID, false, CREATED_AT.plusMinutes(1));
        insertNonPaidOrders();
        assertUnseen(501, 504);
        String mixedIds = "[501, 501, 502, 503, 999, 600, 601, 603, 604, 605, 606, 607, 608]";

        acknowledge(mixedIds);

        assertEquals(true, seen(501));
        assertEquals(true, seen(502));
        assertEquals(false, seen(503));
        assertEquals(false, seen(504));
        for (OrderStatusEnum orderStatus : OrderStatusEnum.values()) {
            if (orderStatus != OrderStatusEnum.PAID) {
                assertEquals(false, seen(600 + orderStatus.getCode()), orderStatus.name());
            }
        }
        assertUnseen(504);

        acknowledge(mixedIds);
        assertUnseen(504);
        acknowledge("[504]");
        assertUnseen();

        MerchantContextHolder.setMerchantId(OTHER_MERCHANT_ID);
        assertUnseen(503);
    }

    @Test
    void emptyAcknowledgementKeepsPersistedUnseenOrders() throws Exception {
        insertOrder(501, MERCHANT_ID, OrderStatusEnum.PAID, false, CREATED_AT);

        acknowledge("[]");

        assertEquals(false, seen(501));
        assertUnseen(501);
    }

    private void insertNonPaidOrders() {
        for (OrderStatusEnum orderStatus : OrderStatusEnum.values()) {
            if (orderStatus != OrderStatusEnum.PAID) {
                insertOrder(600 + orderStatus.getCode(), MERCHANT_ID,
                        orderStatus, false, CREATED_AT.minusDays(1));
            }
        }
    }

    private void insertOrder(long id, long merchantId, OrderStatusEnum orderStatus,
                             boolean merchantSeen, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO orders (id, order_no, merchant_id, status, merchant_seen, create_time)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, "ORDER-" + id, merchantId, orderStatus.getCode(), merchantSeen, createdAt);
    }

    private Boolean seen(long id) {
        return jdbcTemplate.queryForObject("SELECT merchant_seen FROM orders WHERE id = ?", Boolean.class, id);
    }

    private void acknowledge(String ids) throws Exception {
        mockMvc.perform(patch("/merchant/orders/seen")
                        .contentType(MediaType.APPLICATION_JSON).content(ids))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("success"));
    }

    private void assertUnseen(Integer... ids) throws Exception {
        ResultActions response = mockMvc.perform(get("/merchant/orders/unseen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
        if (ids.length == 0) {
            response.andExpect(jsonPath("$.data").isEmpty());
        } else {
            response.andExpect(jsonPath("$.data", contains(ids)));
        }
    }
}
