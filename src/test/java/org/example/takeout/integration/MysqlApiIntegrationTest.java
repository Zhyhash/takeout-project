package org.example.takeout.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.takeout.Cart.DTO.AddCartDTO;
import org.example.takeout.Cart.DTO.UpdateCartDTO;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.User.DTO.LoginDTO;
import org.example.takeout.User.DTO.RegisterDTO;
import org.example.takeout.testsupport.ConcurrentTestTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=jdbc:mysql://localhost:3306/takeout_integration_test?createDatabaseIfNotExist=true&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.sql.init.mode=never",
        "jwt.secret=test-secret-key-at-least-32-characters-long!!",
        "jwt.expire-days=7"
})
class MysqlApiIntegrationTest {

    private static final String PASSWORD = "password123";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryTaskService deliveryTaskService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;

    @BeforeEach
    void resetMysqlSchema() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        recreateSchema();
    }

    @Test
    void userRegisterInsertsUserRow() throws Exception {
        String suffix = suffix();
        RegisterDTO dto = userRegisterDTO("it_user_" + suffix, phone());

        postJson("/user/register", dto)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from `user` where username = ? and phone = ?",
                Integer.class,
                dto.getUsername(),
                dto.getPhone());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void loginReturnsTokenThatCanAccessUserEndpoint() throws Exception {
        String suffix = suffix();
        RegisterDTO registerDTO = userRegisterDTO("it_login_" + suffix, phone());
        postJson("/user/register", registerDTO).andExpect(jsonPath("$.code").value(200));

        String token = loginUser(registerDTO.getUsername());

        mockMvc.perform(get("/cart/items").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void createCategoryInsertsCategoryRow() throws Exception {
        MerchantSeed merchant = insertMerchant("it_merchant_" + suffix());
        String token = loginMerchant(merchant.username());

        mockMvc.perform(post("/category")
                        .header("Authorization", bearer(token))
                        .param("categoryName", "c_" + suffix()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from category where merchant_id = ? and is_default = 1",
                Integer.class,
                merchant.id());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void createProductInsertsProductRow() throws Exception {
        MerchantSeed merchant = insertMerchant("it_product_merchant_" + suffix());
        Long categoryId = insertCategory(merchant.id(), "it_product_category_" + suffix());
        String token = loginMerchant(merchant.username());
        CreateProductDTO dto = productDTO(categoryId, productName());

        postJson("/category/products", dto, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select merchant_id, category_id, product_name, price, stock, status, is_deleted " +
                        "from product where product_name = ?",
                dto.getProductName());
        assertThat(row.get("merchant_id")).isEqualTo(merchant.id());
        assertThat(row.get("category_id")).isEqualTo(categoryId);
        assertThat(row.get("price").toString()).isEqualTo("18.80");
        assertThat(row.get("stock")).isEqualTo(20);
        assertThat(row.get("status")).isEqualTo(ProductStatusEnum.OFF_SALE.getCode());
        assertThat(row.get("is_deleted")).isEqualTo(0);
    }

    @Test
    void addCartItemInsertsCartRow() throws Exception {
        UserSeed user = insertUser("it_cart_user_" + suffix());
        MerchantSeed merchant = insertMerchant("it_cart_merchant_" + suffix());
        Long categoryId = insertCategory(merchant.id(), "it_cart_category_" + suffix());
        Long productId = insertProduct(merchant.id(), categoryId, productName(), 0, 10);
        String token = loginUser(user.username());

        AddCartDTO dto = new AddCartDTO();
        dto.setProductId(productId);
        postJson("/cart/items", dto, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from cart where user_id = ? and product_id = ? and quantity = 1",
                Integer.class,
                user.id(),
                productId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void createOrderInsertsOrderAndOrderItemRows() throws Exception {
        OrderSeed seed = createOrderThroughApi();

        Integer orderCount = jdbcTemplate.queryForObject(
                "select count(*) from orders where id = ? and user_id = ? and status = 0",
                Integer.class,
                seed.orderId(),
                seed.userId());
        Integer itemCount = jdbcTemplate.queryForObject(
                "select count(*) from order_item where order_id = ? and product_id = ?",
                Integer.class,
                seed.orderId(),
                seed.productId());
        Integer cartCount = jdbcTemplate.queryForObject(
                "select count(*) from cart where user_id = ?",
                Integer.class,
                seed.userId());

        assertThat(orderCount).isEqualTo(1);
        assertThat(itemCount).isEqualTo(1);
        assertThat(cartCount).isZero();
    }

    @Test
    void payOrderUpdatesOrderStatus() throws Exception {
        OrderSeed seed = createOrderThroughApi();

        mockMvc.perform(patch("/order/{id}/pay", seed.orderId())
                        .header("Authorization", bearer(seed.userToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, pay_time from orders where id = ?",
                seed.orderId());
        assertThat(row.get("status")).isEqualTo(OrderStatusEnum.PAID.getCode());
        assertThat(row.get("pay_time")).isNotNull();
    }

    @Test
    void orderDeliveryLifecycleCompletesSuccessfully() throws Exception {
        OrderSeed seed = createOrderThroughApi();
        String merchantUsername = jdbcTemplate.queryForObject(
                "select username from merchant where id = ?",
                String.class,
                seed.merchantId());
        String merchantPhone = jdbcTemplate.queryForObject(
                "select phone from merchant where id = ?",
                String.class,
                seed.merchantId());
        String merchantToken = loginMerchant(merchantUsername);
        Long riderId = insertRider("it_rider_" + suffix());

        //支付
        mockMvc.perform(patch("/order/{id}/pay", seed.orderId())
                        .header("Authorization", bearer(seed.userToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Map<String, Object> paidOrder = jdbcTemplate.queryForMap(
                "select status, pay_time from orders where id = ?",
                seed.orderId());
        assertThat(paidOrder.get("status")).isEqualTo(OrderStatusEnum.PAID.getCode());
        assertThat(paidOrder.get("pay_time")).isNotNull();

        //商家接单
        mockMvc.perform(patch("/merchant/orders/{orderId}/accept", seed.orderId())
                        .header("Authorization", bearer(merchantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(patch("/merchant/orders/{orderId}/accept", seed.orderId())
                        .header("Authorization", bearer(merchantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(orderStatus(seed.orderId())).isEqualTo(OrderStatusEnum.PREPARING.getCode());

        //制作完成
        mockMvc.perform(patch("/merchant/orders/{orderId}/ready", seed.orderId())
                        .header("Authorization", bearer(merchantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(orderStatus(seed.orderId())).isEqualTo(OrderStatusEnum.READY.getCode());

        Map<String, Object> waitingTask = jdbcTemplate.queryForMap(
                "select id, rider_id, status, create_time, delivery_reward, receiver_phone, receiver_address, " +
                        "receiver_name, merchant_address, merchant_phone from delivery_task where order_id = ?",
                seed.orderId());
        Long taskId = ((Number) waitingTask.get("id")).longValue();
        assertThat(waitingTask.get("rider_id")).isNull();
        assertThat(waitingTask.get("status")).isEqualTo(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        assertThat(waitingTask.get("create_time")).isNotNull();
        assertThat((BigDecimal) waitingTask.get("delivery_reward")).isEqualByComparingTo("5.00");
        assertThat(waitingTask.get("receiver_phone")).isEqualTo("13800138000");
        assertThat(waitingTask.get("receiver_address")).isEqualTo("Integration Road 1");
        assertThat(waitingTask.get("receiver_name")).isEqualTo("Integration Tester");
        assertThat(waitingTask.get("merchant_address")).isEqualTo("Integration Address");
        assertThat(waitingTask.get("merchant_phone")).isEqualTo(merchantPhone);

        //重复出餐必须同时验证订单与配送任务状态，且不能创建第二条任务
        mockMvc.perform(patch("/merchant/orders/{orderId}/ready", seed.orderId())
                        .header("Authorization", bearer(merchantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        Integer taskCount = jdbcTemplate.queryForObject(
                "select count(*) from delivery_task where order_id = ?",
                Integer.class,
                seed.orderId());
        assertThat(taskCount).isEqualTo(1);

        RiderContextHolder.setRiderId(riderId);
        try {
            //骑手抢单
            deliveryTaskService.claimTask(taskId);
            deliveryTaskService.claimTask(taskId);

            Map<String, Object> deliveringTask = jdbcTemplate.queryForMap(
                    "select rider_id, status, accepted_time from delivery_task where id = ?",
                    taskId);
            assertThat(deliveringTask.get("rider_id")).isEqualTo(riderId);
            assertThat(deliveringTask.get("status")).isEqualTo(DeliveryTaskEnums.DELIVERING.getCode());
            assertThat(deliveringTask.get("accepted_time")).isNotNull();
            assertThat(orderStatus(seed.orderId())).isEqualTo(OrderStatusEnum.DELIVERING.getCode());

            //骑手送达
            deliveryTaskService.completeDelivery(taskId);
            deliveryTaskService.completeDelivery(taskId);

            Map<String, Object> completedTask = jdbcTemplate.queryForMap(
                    "select status, delivered_time from delivery_task where id = ?",
                    taskId);
            assertThat(completedTask.get("status")).isEqualTo(DeliveryTaskEnums.COMPLETED.getCode());
            assertThat(completedTask.get("delivered_time")).isNotNull();
            assertThat(orderStatus(seed.orderId())).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        } finally {
            RiderContextHolder.clear();
        }

        //用户收货
        mockMvc.perform(patch("/order/{id}/confirm", seed.orderId())
                        .header("Authorization", bearer(seed.userToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Map<String, Object> finishedOrder = jdbcTemplate.queryForMap(
                "select status, finish_time from orders where id = ?",
                seed.orderId());
        assertThat(finishedOrder.get("status")).isEqualTo(OrderStatusEnum.FINISHED.getCode());
        assertThat(finishedOrder.get("finish_time")).isNotNull();
    }

    @Test
    void concurrentRidersClaimExactlyOnceOnMysql() throws Exception {
        OrderSeed seed = createOrderThroughApi();
        String merchantUsername = jdbcTemplate.queryForObject(
                "select username from merchant where id = ?",
                String.class,
                seed.merchantId());
        String merchantToken = loginMerchant(merchantUsername);
        Long riderAId = insertRider("it_concurrent_rider_a_" + suffix());
        Long riderBId = insertRider("it_concurrent_rider_b_" + suffix());

        mockMvc.perform(patch("/order/{id}/pay", seed.orderId())
                        .header("Authorization", bearer(seed.userToken())))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(patch("/merchant/orders/{orderId}/accept", seed.orderId())
                        .header("Authorization", bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(patch("/merchant/orders/{orderId}/ready", seed.orderId())
                        .header("Authorization", bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(200));

        Long taskId = jdbcTemplate.queryForObject(
                "select id from delivery_task where order_id = ?",
                Long.class,
                seed.orderId());
        ConcurrentTestTemplate.TwoTaskResult<Boolean, Boolean> attempts =
                ConcurrentTestTemplate.runTwoTasks(
                        Duration.ofSeconds(10),
                        () -> tryClaimTask(riderAId, taskId),
                        () -> tryClaimTask(riderBId, taskId));

        assertThat(List.of(attempts.firstResult(), attempts.secondResult()))
                .containsExactlyInAnyOrder(true, false);
        Map<String, Object> task = jdbcTemplate.queryForMap(
                "select rider_id, status from delivery_task where id = ?",
                taskId);
        assertThat(((Number) task.get("rider_id")).longValue()).isIn(riderAId, riderBId);
        assertThat(task.get("status")).isEqualTo(DeliveryTaskEnums.DELIVERING.getCode());
        assertThat(orderStatus(seed.orderId())).isEqualTo(OrderStatusEnum.DELIVERING.getCode());
    }

    @Test
    void cancelOrderUpdatesOrderStatus() throws Exception {
        OrderSeed seed = createOrderThroughApi();

        mockMvc.perform(patch("/order/{id}/cancel", seed.orderId())
                        .header("Authorization", bearer(seed.userToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status from orders where id = ?",
                seed.orderId());
        Integer stock = jdbcTemplate.queryForObject(
                "select stock from product where id = ?",
                Integer.class,
                seed.productId());
        assertThat(row.get("status")).isEqualTo(OrderStatusEnum.CANCELLED.getCode());
        assertThat(stock).isEqualTo(10);
    }

    @Test
    void updateMerchantInfoUpdatesMerchantRow() throws Exception {
        MerchantSeed merchant = insertMerchant("it_update_merchant_" + suffix());
        jdbcTemplate.update(
                "update merchant set status = ? where id = ?",
                MerchantStatusEnum.BUSINESS_CLOSED.getCode(),
                merchant.id());
        String token = loginMerchant(merchant.username());
        String updatedPhone = phone();

        MerchantUpdateDTO dto = new MerchantUpdateDTO();
        dto.setMerchantName("Updated Shop " + suffix());
        dto.setAddress("Updated Integration Road");
        dto.setPhone(updatedPhone);
        dto.setDescription("freshly updated by integration test");
        dto.setPictureURL("https://example.test/merchant.png");
        dto.setOpeningTime(LocalTime.of(9, 30));
        dto.setClosingTime(LocalTime.of(21, 45));

        mockMvc.perform(put("/merchant/info")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.merchantName").value(dto.getMerchantName()))
                .andExpect(jsonPath("$.data.address").value(dto.getAddress()))
                .andExpect(jsonPath("$.data.phone").value(updatedPhone));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select merchant_name, address, phone, merchant_description, picture, opening_time, closing_time, version " +
                        "from merchant where id = ?",
                merchant.id());
        assertThat(row.get("merchant_name")).isEqualTo(dto.getMerchantName());
        assertThat(row.get("address")).isEqualTo(dto.getAddress());
        assertThat(row.get("phone")).isEqualTo(updatedPhone);
        assertThat(row.get("merchant_description")).isEqualTo(dto.getDescription());
        assertThat(row.get("picture")).isEqualTo(dto.getPictureURL());
        assertThat(row.get("opening_time").toString()).startsWith("09:30");
        assertThat(row.get("closing_time").toString()).startsWith("21:45");
        assertThat(row.get("version")).isEqualTo(1);
    }

    @Test
    void updateMerchantInfoRejectsBusinessOpenMerchant() throws Exception {
        MerchantSeed merchant = insertMerchant("it_open_merchant_" + suffix());
        String token = loginMerchant(merchant.username());

        MerchantUpdateDTO dto = new MerchantUpdateDTO();
        dto.setMerchantName("Updated Shop " + suffix());
        dto.setAddress("Updated Integration Road");
        dto.setPhone(phone());
        dto.setOpeningTime(LocalTime.of(9, 30));
        dto.setClosingTime(LocalTime.of(21, 45));

        mockMvc.perform(put("/merchant/info")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void deleteCategoryDeletesCategoryRowAndMovesProductsToDefaultCategory() throws Exception {
        MerchantSeed merchant = insertMerchant("it_delete_category_merchant_" + suffix());
        Long defaultCategoryId = insertDefaultCategory(merchant.id(), "default_" + suffix());
        Long categoryId = insertCategory(merchant.id(), "it_delete_category_" + suffix());
        Long productId = insertProduct(merchant.id(), categoryId, productName(), 0, 10);
        String token = loginMerchant(merchant.username());

        mockMvc.perform(delete("/category/{id}", categoryId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Integer categoryCount = jdbcTemplate.queryForObject(
                "select count(*) from category where id = ?",
                Integer.class,
                categoryId);
        Long productCategoryId = jdbcTemplate.queryForObject(
                "select category_id from product where id = ?",
                Long.class,
                productId);

        assertThat(categoryCount).isZero();
        assertThat(productCategoryId).isEqualTo(defaultCategoryId);
    }

    @Test
    void productShelfEndpointsUpdateProductStatus() throws Exception {
        MerchantSeed merchant = insertMerchant("it_shelf_merchant_" + suffix());
        Long categoryId = insertCategory(merchant.id(), "it_shelf_category_" + suffix());
        Long productId = insertProduct(merchant.id(), categoryId, productName(), 1, 10);
        String token = loginMerchant(merchant.username());

        mockMvc.perform(patch("/category/products/{id}/on-shelf", productId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Integer onShelfStatus = jdbcTemplate.queryForObject(
                "select status from product where id = ?",
                Integer.class,
                productId);
        assertThat(onShelfStatus).isEqualTo(0);

        mockMvc.perform(patch("/category/products/{id}/off-shelf", productId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Integer offShelfStatus = jdbcTemplate.queryForObject(
                "select status from product where id = ?",
                Integer.class,
                productId);
        assertThat(offShelfStatus).isEqualTo(1);
    }

    @Test
    void updateCartQuantityUpdatesCartRow() throws Exception {
        UserSeed user = insertUser("it_update_cart_user_" + suffix());
        MerchantSeed merchant = insertMerchant("it_update_cart_merchant_" + suffix());
        Long categoryId = insertCategory(merchant.id(), "it_update_cart_category_" + suffix());
        Long productId = insertProduct(merchant.id(), categoryId, productName(), 0, 10);
        String token = loginUser(user.username());
        Long cartItemId = addCartItemThroughApi(productId, token);

        UpdateCartDTO dto = new UpdateCartDTO();
        dto.setCartItemId(cartItemId);
        dto.setQuantityChange(1);

        mockMvc.perform(patch("/cart/items")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.quantity").value(2));

        Integer quantity = jdbcTemplate.queryForObject(
                "select quantity from cart where id = ? and user_id = ?",
                Integer.class,
                cartItemId,
                user.id());
        assertThat(quantity).isEqualTo(2);
    }

    @Test
    void clearCartDeletesOnlyCurrentUsersCartRows() throws Exception {
        UserSeed user = insertUser("it_clear_cart_user_" + suffix());
        UserSeed otherUser = insertUser("it_clear_cart_other_" + suffix());
        MerchantSeed merchant = insertMerchant("it_clear_cart_merchant_" + suffix());
        Long categoryId = insertCategory(merchant.id(), "it_clear_cart_category_" + suffix());
        Long firstProductId = insertProduct(merchant.id(), categoryId, productName(), 0, 10);
        Long secondProductId = insertProduct(merchant.id(), categoryId, productName(), 0, 10);
        String token = loginUser(user.username());
        String otherToken = loginUser(otherUser.username());

        addCartItemThroughApi(firstProductId, token);
        addCartItemThroughApi(secondProductId, token);
        addCartItemThroughApi(firstProductId, otherToken);

        mockMvc.perform(delete("/cart/items/all")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Integer currentUserCartCount = jdbcTemplate.queryForObject(
                "select count(*) from cart where user_id = ?",
                Integer.class,
                user.id());
        Integer otherUserCartCount = jdbcTemplate.queryForObject(
                "select count(*) from cart where user_id = ?",
                Integer.class,
                otherUser.id());
        assertThat(currentUserCartCount).isZero();
        assertThat(otherUserCartCount).isEqualTo(1);
    }

    @Test
    void confirmOrderUpdatesOrderStatus() throws Exception {
        OrderSeed seed = createOrderThroughApi();

        jdbcTemplate.update(
                "update orders set status = ? where id = ?",
                OrderStatusEnum.DELIVERED.getCode(),
                seed.orderId());

        mockMvc.perform(patch("/order/{id}/confirm", seed.orderId())
                        .header("Authorization", bearer(seed.userToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, finish_time from orders where id = ?",
                seed.orderId());
        assertThat(row.get("status")).isEqualTo(OrderStatusEnum.FINISHED.getCode());
        assertThat(row.get("finish_time")).isNotNull();
    }

    @Test
    void listAndDetailOrdersReturnRowsFromMysql() throws Exception {
        OrderSeed seed = createOrderThroughApi();
        Map<String, Object> orderRow = jdbcTemplate.queryForMap(
                "select order_no, merchant_name, total_amount, status, receiver_name, receiver_phone, receiver_address " +
                        "from orders where id = ?",
                seed.orderId());
        Map<String, Object> itemRow = jdbcTemplate.queryForMap(
                "select product_id, product_name, product_price, quantity, subtotal from order_item where order_id = ?",
                seed.orderId());

        mockMvc.perform(get("/order")
                        .header("Authorization", bearer(seed.userToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].id").value(seed.orderId()))
                .andExpect(jsonPath("$.data.list[0].orderNo").value(orderRow.get("order_no")))
                .andExpect(jsonPath("$.data.list[0].merchantName").value(orderRow.get("merchant_name")))
                .andExpect(jsonPath("$.data.list[0].status").value(orderRow.get("status")))
                .andExpect(jsonPath("$.data.list[0].productSummary").value(itemRow.get("product_name")));

        mockMvc.perform(get("/order/{id}", seed.orderId())
                        .header("Authorization", bearer(seed.userToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(seed.orderId()))
                .andExpect(jsonPath("$.data.orderNo").value(orderRow.get("order_no")))
                .andExpect(jsonPath("$.data.receiverName").value(orderRow.get("receiver_name")))
                .andExpect(jsonPath("$.data.receiverPhone").value(orderRow.get("receiver_phone")))
                .andExpect(jsonPath("$.data.receiverAddress").value(orderRow.get("receiver_address")))
                .andExpect(jsonPath("$.data.items[0].productId").value(itemRow.get("product_id")))
                .andExpect(jsonPath("$.data.items[0].productName").value(itemRow.get("product_name")))
                .andExpect(jsonPath("$.data.items[0].quantity").value(itemRow.get("quantity")));
    }

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/cart/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void protectedEndpointRejectsInvalidToken() throws Exception {
        mockMvc.perform(get("/cart/items")
                        .header("Authorization", bearer("invalid-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void crossUserAccessToCartAndOrderIsRejected() throws Exception {
        UserSeed owner = insertUser("it_owner_user_" + suffix());
        UserSeed otherUser = insertUser("it_other_user_" + suffix());
        MerchantSeed merchant = insertMerchant("it_cross_user_merchant_" + suffix());
        Long categoryId = insertCategory(merchant.id(), "it_cross_user_category_" + suffix());
        Long productId = insertProduct(merchant.id(), categoryId, productName(), 0, 10);
        String ownerToken = loginUser(owner.username());
        String otherToken = loginUser(otherUser.username());
        Long cartItemId = addCartItemThroughApi(productId, ownerToken);

        UpdateCartDTO dto = new UpdateCartDTO();
        dto.setCartItemId(cartItemId);
        dto.setQuantityChange(1);

        mockMvc.perform(patch("/cart/items")
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        Integer quantity = jdbcTemplate.queryForObject(
                "select quantity from cart where id = ? and user_id = ?",
                Integer.class,
                cartItemId,
                owner.id());
        assertThat(quantity).isEqualTo(1);

        OrderSeed orderSeed = createOrderThroughApi();

        mockMvc.perform(get("/order/{id}", orderSeed.orderId())
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        Integer orderCount = jdbcTemplate.queryForObject(
                "select count(*) from orders where id = ? and user_id = ? and status = 0",
                Integer.class,
                orderSeed.orderId(),
                orderSeed.userId());
        assertThat(orderCount).isEqualTo(1);
    }

    @Test
    void crossMerchantAccessToCategoryAndProductIsRejected() throws Exception {
        MerchantSeed owner = insertMerchant("it_owner_merchant_" + suffix());
        MerchantSeed otherMerchant = insertMerchant("it_other_merchant_" + suffix());
        Long categoryId = insertCategory(owner.id(), "it_cross_merchant_category_" + suffix());
        Long productId = insertProduct(owner.id(), categoryId, productName(), 0, 10);
        String otherToken = loginMerchant(otherMerchant.username());

        mockMvc.perform(delete("/category/{id}", categoryId)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        mockMvc.perform(patch("/category/products/{id}/off-shelf", productId)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        Integer categoryCount = jdbcTemplate.queryForObject(
                "select count(*) from category where id = ? and merchant_id = ?",
                Integer.class,
                categoryId,
                owner.id());
        Integer productStatus = jdbcTemplate.queryForObject(
                "select status from product where id = ? and merchant_id = ?",
                Integer.class,
                productId,
                owner.id());
        assertThat(categoryCount).isEqualTo(1);
        assertThat(productStatus).isEqualTo(0);
    }

    private OrderSeed createOrderThroughApi() throws Exception {
        UserSeed user = insertUser("it_order_user_" + suffix());
        MerchantSeed merchant = insertMerchant("it_order_merchant_" + suffix());
        Long categoryId = insertCategory(merchant.id(), "it_order_category_" + suffix());
        String productName = productName();
        Long productId = insertProduct(merchant.id(), categoryId, productName, 0, 10);
        String token = loginUser(user.username());

        AddCartDTO cartDTO = new AddCartDTO();
        cartDTO.setProductId(productId);
        postJson("/cart/items", cartDTO, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        CreateOrderDTO orderDTO = new CreateOrderDTO();
        orderDTO.setRequestId(UUID.randomUUID().toString());
        orderDTO.setReceiverName("Integration Tester");
        orderDTO.setReceiverPhone("13800138000");
        orderDTO.setReceiverAddress("Integration Road 1");
        orderDTO.setRemark("integration");

        MvcResult result = postJson("/order", orderDTO, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        Long orderId = responseJson(result).at("/data/orderId").asLong();
        return new OrderSeed(user.id(), merchant.id(), productId, orderId, productName, token);
    }

    private RegisterDTO userRegisterDTO(String username, String phone) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username);
        dto.setPassword(PASSWORD);
        dto.setConfirmPassword(PASSWORD);
        dto.setPhone(phone);
        return dto;
    }

    private CreateProductDTO productDTO(Long categoryId, String productName) {
        CreateProductDTO dto = new CreateProductDTO();
        dto.setProductName(productName);
        dto.setDescription("integration product");
        dto.setPrice(new BigDecimal("18.80"));
        dto.setStock(20);
        dto.setImageUrl("https://example.test/product.png");
        dto.setCategoryId(categoryId);
        return dto;
    }

    private String loginUser(String username) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(PASSWORD);
        MvcResult result = postJson("/user/login", dto)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return responseJson(result).at("/data/token").asText();
    }

    private String loginMerchant(String username) throws Exception {
        MerchantLoginDTO dto = new MerchantLoginDTO();
        dto.setUserName(username);
        dto.setPassword(PASSWORD);
        MvcResult result = postJson("/merchant/login", dto)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return responseJson(result).at("/data/token").asText();
    }

    private UserSeed insertUser(String username) {
        String phone = phone();
        jdbcTemplate.update("""
                insert into `user` (username, phone, password, nickname, status)
                values (?, ?, ?, ?, 1)
                """, username, phone, BCrypt.encode(PASSWORD), username);
        Long id = jdbcTemplate.queryForObject(
                "select id from `user` where username = ?",
                Long.class,
                username);
        return new UserSeed(id, username);
    }

    private MerchantSeed insertMerchant(String username) {
        String phone = phone();
        jdbcTemplate.update("""
                insert into merchant
                (username, phone, password, address, status, merchant_name, picture,
                 merchant_description, opening_time, closing_time)
                values (?, ?, ?, 'Integration Address', 0, ?, '', '', '08:00:00', '22:00:00')
                """, username, phone, BCrypt.encode(PASSWORD), username + " shop");
        Long id = jdbcTemplate.queryForObject(
                "select id from merchant where username = ?",
                Long.class,
                username);
        return new MerchantSeed(id, username);
    }

    private Long insertRider(String name) {
        String phone = phone();
        jdbcTemplate.update("""
                insert into rider (name, phone, password, status, create_time, is_delete)
                values (?, ?, ?, 1, now(), 0)
                """, name, phone, BCrypt.encode(PASSWORD));
        return jdbcTemplate.queryForObject(
                "select id from rider where name = ?",
                Long.class,
                name);
    }

    private boolean tryClaimTask(Long riderId, Long taskId) {
        RiderContextHolder.setRiderId(riderId);
        try {
            deliveryTaskService.claimTask(taskId);
            return true;
        } catch (BusinessException exception) {
            return false;
        } finally {
            RiderContextHolder.clear();
        }
    }

    private Integer orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject(
                "select status from orders where id = ?",
                Integer.class,
                orderId);
    }

    private Long insertCategory(Long merchantId, String categoryName) {
        jdbcTemplate.update("""
                insert into category (merchant_id, category_name, status, is_default)
                values (?, ?, 0, 1)
                """, merchantId, categoryName);
        return jdbcTemplate.queryForObject(
                "select id from category where merchant_id = ? and category_name = ?",
                Long.class,
                merchantId,
                categoryName);
    }

    private Long insertDefaultCategory(Long merchantId, String categoryName) {
        jdbcTemplate.update("""
                insert into category (merchant_id, category_name, status, is_default)
                values (?, ?, 0, 0)
                """, merchantId, categoryName);
        return jdbcTemplate.queryForObject(
                "select id from category where merchant_id = ? and category_name = ?",
                Long.class,
                merchantId,
                categoryName);
    }

    private Long insertProduct(Long merchantId, Long categoryId, String productName, int status, int stock) {
        jdbcTemplate.update("""
                insert into product
                (merchant_id, category_id, product_name, description, price, stock, image_url,
                 status, is_deleted)
                values (?, ?, ?, 'integration product', 12.50, ?, '', ?, 0)
                """, merchantId, categoryId, productName, stock, status);
        return jdbcTemplate.queryForObject(
                "select id from product where merchant_id = ? and product_name = ?",
                Long.class,
                merchantId,
                productName);
    }

    private Long addCartItemThroughApi(Long productId, String token) throws Exception {
        AddCartDTO dto = new AddCartDTO();
        dto.setProductId(productId);
        MvcResult result = postJson("/cart/items", dto, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return responseJson(result).at("/data/id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, Object body, String token) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    private String productName() {
        return "p" + suffix().substring(0, 10);
    }

    private String phone() {
        long value = Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000_000L);
        return "13" + String.format("%09d", value);
    }

    private void recreateSchema() {
        jdbcTemplate.execute("drop table if exists delivery_task");
        jdbcTemplate.execute("drop table if exists rider");
        jdbcTemplate.execute("drop table if exists order_item");
        jdbcTemplate.execute("drop table if exists orders");
        jdbcTemplate.execute("drop table if exists cart");
        jdbcTemplate.execute("drop table if exists product");
        jdbcTemplate.execute("drop table if exists category");
        jdbcTemplate.execute("drop table if exists merchant");
        jdbcTemplate.execute("drop table if exists `user`");

        jdbcTemplate.execute("""
                CREATE TABLE `user` (
                    `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT,
                    `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '',
                    `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
                    `status` tinyint NOT NULL DEFAULT 1,
                    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    PRIMARY KEY (`id`) USING BTREE,
                    UNIQUE INDEX `uk_user_phone` (`phone` ASC) USING BTREE,
                    UNIQUE INDEX `uk_user_username` (`username` ASC) USING BTREE
                ) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4
                  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic
                """);

        jdbcTemplate.execute("""
                CREATE TABLE `merchant` (
                    `id` bigint NOT NULL AUTO_INCREMENT,
                    `username` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '',
                    `status` tinyint NOT NULL DEFAULT 0,
                    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    `merchant_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `picture` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
                    `merchant_description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
                    `opening_time` time NOT NULL DEFAULT '08:00:00',
                    `closing_time` time NOT NULL DEFAULT '22:00:00',
                    `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
                    PRIMARY KEY (`id`) USING BTREE,
                    UNIQUE INDEX `uk_merchant_phone` (`phone` ASC) USING BTREE,
                    UNIQUE INDEX `uk_merchant_username` (`username` ASC) USING BTREE
                ) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4
                  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic
                """);

        jdbcTemplate.execute("""
                CREATE TABLE `category` (
                    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '分类ID',
                    `merchant_id` bigint NOT NULL COMMENT '所属商家ID',
                    `category_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '分类名称',
                    `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态: 0-正常使用, 1-非法分类 (对应 CategoryStatusEnum)',
                    `is_default` tinyint NOT NULL DEFAULT 0 COMMENT '类型: 0-默认分类, 1-商家自主分类 (对应 CategoryDefaultEnum)',
                    PRIMARY KEY (`id`) USING BTREE,
                    UNIQUE INDEX `uk_merchant_category` (`merchant_id` ASC, `category_name` ASC) USING BTREE,
                    INDEX `idx_merchant_id` (`merchant_id` ASC) USING BTREE
                ) ENGINE = InnoDB AUTO_INCREMENT = 19 CHARACTER SET = utf8mb4
                  COLLATE = utf8mb4_0900_ai_ci COMMENT = '商品分类表' ROW_FORMAT = Dynamic
                """);

        jdbcTemplate.execute("""
                CREATE TABLE `product` (
                    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '商品ID',
                    `category_id` bigint NOT NULL COMMENT '分类ID',
                    `product_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商品名称',
                    `image_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '商品图片URL',
                    `price` decimal(10, 2) NOT NULL COMMENT '商品价格',
                    `stock` int NOT NULL COMMENT '库存数量',
                    `merchant_id` bigint NOT NULL COMMENT '所属商家ID',
                    `is_deleted` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除: 0-未删除, 1-已删除',
                    `status` tinyint NOT NULL DEFAULT 0 COMMENT '商品状态',
                    `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '商品描述',
                    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
                    PRIMARY KEY (`id`) USING BTREE,
                    INDEX `idx_category_id` (`category_id` ASC) USING BTREE,
                    INDEX `idx_merchant_id` (`merchant_id` ASC) USING BTREE,
                    -- 添加联合唯一索引
                    UNIQUE INDEX `uk_merchant_product` (`merchant_id`, `product_name`) USING BTREE,
                    -- 添加外键约束
                    CONSTRAINT `fk_product_category`
                    FOREIGN KEY (`category_id`) REFERENCES `category` (`id`)
                    ON DELETE RESTRICT ON UPDATE RESTRICT
                ) ENGINE = InnoDB CHARACTER SET = utf8mb4
                COLLATE = utf8mb4_0900_ai_ci COMMENT = '商品表' ROW_FORMAT = Dynamic;
""");

        jdbcTemplate.execute("""
                CREATE TABLE `cart` (
                    `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '购物车id',
                    `user_id` bigint UNSIGNED NOT NULL COMMENT '用户id',
                    `product_id` bigint UNSIGNED NOT NULL COMMENT '商品id',
                    `product_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `quantity` int NOT NULL DEFAULT 1 COMMENT '商品数量',
                    `price` decimal(10, 2) NOT NULL COMMENT '加入购物车时的商品价格',
                    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
                    `merchant_id` bigint NOT NULL,
                    `product_image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `version` int NOT NULL DEFAULT 0,
                    PRIMARY KEY (`id`) USING BTREE,
                    UNIQUE INDEX `uk_user_product` (`user_id` ASC, `product_id` ASC) USING BTREE
                        COMMENT '一个用户对同一个商品只能有一条购物车记录',
                    INDEX `idx_user_id` (`user_id` ASC) USING BTREE,
                    INDEX `idx_product_id` (`product_id` ASC) USING BTREE
                ) ENGINE = InnoDB AUTO_INCREMENT = 41 CHARACTER SET = utf8mb4
                  COLLATE = utf8mb4_0900_ai_ci COMMENT = '购物车表' ROW_FORMAT = Dynamic
                """);

        jdbcTemplate.execute("""
                CREATE TABLE `orders` (
                    `id` bigint NOT NULL AUTO_INCREMENT,
                    `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `user_id` bigint NOT NULL,
                    `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '客户端下单请求唯一标识',
                    `merchant_id` bigint NOT NULL,
                    `merchant_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `total_amount` decimal(10, 2) NOT NULL,
                    `status` tinyint NOT NULL DEFAULT 0,
                    `receiver_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `receiver_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `receiver_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
                    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    `finish_time` datetime NULL DEFAULT NULL,
                    `original_amount` decimal(10, 2) NOT NULL DEFAULT 0.00,
                    `discount_amount` decimal(10, 2) NOT NULL DEFAULT 0.00,
                    `pay_time` datetime NULL DEFAULT NULL,
                    PRIMARY KEY (`id`) USING BTREE,
                    UNIQUE INDEX `uk_order_no` (`order_no` ASC) USING BTREE,
                    UNIQUE INDEX `uk_orders_user_request_id` (`user_id` ASC, `request_id` ASC) USING BTREE,
                    INDEX `idx_order_user` (`user_id` ASC) USING BTREE,
                    INDEX `idx_order_merchant` (`merchant_id` ASC) USING BTREE
                ) ENGINE = InnoDB AUTO_INCREMENT = 16 CHARACTER SET = utf8mb4
                  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic
                """);

        jdbcTemplate.execute("""
                CREATE TABLE `order_item` (
                    `id` bigint NOT NULL AUTO_INCREMENT,
                    `order_id` bigint NOT NULL,
                    `product_id` bigint NOT NULL,
                    `product_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `product_price` decimal(10, 2) NOT NULL,
                    `quantity` int NOT NULL DEFAULT 1,
                    `subtotal` decimal(10, 2) NOT NULL,
                    `product_picture` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
                    PRIMARY KEY (`id`) USING BTREE,
                    INDEX `idx_order_item_order` (`order_id` ASC) USING BTREE,
                    INDEX `idx_order_item_product` (`product_id` ASC) USING BTREE
                ) ENGINE = InnoDB CHARACTER SET = utf8mb4
                  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic
                """);

        jdbcTemplate.execute("""
                CREATE TABLE `rider` (
                    `id` bigint NOT NULL AUTO_INCREMENT,
                    `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `phone` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `status` int NOT NULL,
                    `create_time` datetime NOT NULL,
                    `update_time` datetime NULL DEFAULT NULL,
                    `is_delete` int NOT NULL,
                    PRIMARY KEY (`id`) USING BTREE,
                    UNIQUE INDEX `uk_rider_name` (`name` ASC) USING BTREE,
                    UNIQUE INDEX `uk_rider_phone` (`phone` ASC) USING BTREE
                ) ENGINE = InnoDB CHARACTER SET = utf8mb4
                  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic
                """);

        jdbcTemplate.execute("""
                CREATE TABLE `delivery_task` (
                    `id` bigint NOT NULL AUTO_INCREMENT,
                    `order_id` bigint NOT NULL,
                    `rider_id` bigint NULL DEFAULT NULL,
                    `merchant_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    `delivery_reward` decimal(10,2) NOT NULL COMMENT '配送奖励金额快照',
                    `receiver_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收货人姓名快照',
                    `status` int NOT NULL,
                    `create_time` datetime NOT NULL COMMENT '商家制作完成，任务创建时间',
                    `accepted_time` datetime NULL DEFAULT NULL COMMENT '骑手接取时间，当前也代表开始配送时间',
                    `delivered_time` datetime NULL DEFAULT NULL COMMENT '骑手确认送达时间',
                    `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
                    `receiver_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收货人联系电话快照',
                    `receiver_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收货地址快照',
                    `merchant_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商家地址快照',
                    `merchant_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商家联系电话快照',
                    PRIMARY KEY (`id`) USING BTREE,
                    UNIQUE INDEX `uk_delivery_task_order_id` (`order_id` ASC) USING BTREE
                ) ENGINE = InnoDB CHARACTER SET = utf8mb4
                  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic
                """);
    }

    private record UserSeed(Long id, String username) {
    }

    private record MerchantSeed(Long id, String username) {
    }

    private record OrderSeed(Long userId, Long merchantId, Long productId, Long orderId, String productName,
                             String userToken) {
    }
}
