package org.example.takeout.dataFactory;

import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 非 API 测试共用的数据工厂。
 *
 * <p>工厂只负责创建完整、合法的测试对象，不负责将数据写入数据库。</p>
 */
public final class TestDataFactory {

    private static final BigDecimal DEFAULT_PRODUCT_PRICE = new BigDecimal("199.00");

    private TestDataFactory() {
    }

    public static Merchant createOpenMerchant(Long id) {
        Merchant merchant = new Merchant();
        merchant.setId(id);
        merchant.setUsername("test_merchant_" + id);
        merchant.setPassword("not-used-in-service-tests");
        merchant.setMerchantName("测试商家");
        merchant.setPhone(String.format("139%08d", Math.floorMod(id, 100_000_000L)));
        merchant.setAddress("测试地址");
        merchant.setStatus(MerchantStatusEnum.BUSINESS_OPEN.getCode());
        merchant.setOpeningTime(LocalTime.of(8, 0));
        merchant.setClosingTime(LocalTime.of(22, 0));
        merchant.setCreateTime(LocalDateTime.now());
        return merchant;
    }

    public static Product createProduct(Long id, String name, int stock, Long merchantId) {
        Product product = new Product();
        product.setId(id);
        product.setProductName(name);
        product.setImageUrl("https://example.com/images/default.jpg");
        product.setPrice(DEFAULT_PRODUCT_PRICE);
        product.setStock(stock);
        product.setMerchantId(merchantId);
        product.setIsDeleted(DeleteConstant.NOT_DELETED);
        product.setStatus(ProductStatusEnum.ON_SALE.getCode());
        product.setVersion(0);
        return product;
    }

    public static CartItem createCartItem(Long id, Long userId, Product product, int quantity) {
        CartItem cartItem = new CartItem();
        cartItem.setId(id);
        cartItem.setUserId(userId);
        cartItem.setProductId(product.getId());
        cartItem.setMerchantId(product.getMerchantId());
        cartItem.setQuantity(quantity);
        cartItem.setProductName(product.getProductName());
        cartItem.setProductImage(product.getImageUrl());
        cartItem.setPrice(product.getPrice());
        return cartItem;
    }

    public static CreateOrderDTO createOrderDTO() {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setReceiverName("张三");
        dto.setReceiverPhone("13812345678");
        dto.setReceiverAddress("北京市朝阳区科技路 A 栋 3 楼");
        dto.setRemark("麻烦多放点辣椒，谢谢！");
        return dto;
    }

    public static Order createOrder(Long userId, Long merchantId, Integer status) {
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setOrderNo("ORD_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        order.setUserId(userId);
        order.setMerchantId(merchantId);
        order.setMerchantName("测试商家");
        order.setTotalAmount(DEFAULT_PRODUCT_PRICE);
        order.setOriginalAmount(DEFAULT_PRODUCT_PRICE);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setStatus(status);
        order.setReceiverName("张三");
        order.setReceiverPhone("13800138000");
        order.setReceiverAddress("测试地址");
        order.setCreateTime(now);
        order.setUpdateTime(now);
        order.setVersion(0);
        return order;
    }

    public static OrderItem createOrderItem(Long orderId, Product product, int quantity) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setProductId(product.getId());
        item.setProductName(product.getProductName());
        item.setProductPrice(product.getPrice());
        item.setQuantity(quantity);
        item.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        item.setProductPicture(product.getImageUrl());
        return item;
    }
}
