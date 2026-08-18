package org.example.takeout.Common;

import org.example.takeout.Category.Entity.Category;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskConverter;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantOrderConverter;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Product.Cache.ProductDetailCacheDTO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductConverter;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusDescriptionMappingTest {

    private final OrderConvertor orderConvertor = Mappers.getMapper(OrderConvertor.class);
    private final MerchantOrderConverter merchantOrderConverter = Mappers.getMapper(MerchantOrderConverter.class);
    private final MerchantConverter merchantConverter = Mappers.getMapper(MerchantConverter.class);
    private final ProductConverter productConverter = Mappers.getMapper(ProductConverter.class);
    private final DeliveryTaskConverter deliveryTaskConverter = Mappers.getMapper(DeliveryTaskConverter.class);

    @Test
    void descriptionsCoverKnownNullAndUnknownCodes() {
        assertEquals("待支付", OrderStatusEnum.descriptionOf(OrderStatusEnum.WAIT_PAY.getCode()));
        assertEquals("店铺正常开启", MerchantStatusEnum.descriptionOf(MerchantStatusEnum.BUSINESS_OPEN.getCode()));
        assertEquals("已售罄", ProductStatusEnum.descriptionOf(ProductStatusEnum.SALE_OUT.getCode()));
        assertEquals("骑手正在配送", DeliveryTaskEnums.descriptionOf(DeliveryTaskEnums.DELIVERING.getCode()));

        assertEquals("未知状态", OrderStatusEnum.descriptionOf(null));
        assertEquals("未知状态", MerchantStatusEnum.descriptionOf(99));
        assertEquals("未知状态", ProductStatusEnum.descriptionOf(99));
        assertEquals("未知状态", DeliveryTaskEnums.descriptionOf(99));
    }

    @Test
    void orderConvertersPopulateStatusDescriptions() {
        Order order = new Order();
        order.setStatus(OrderStatusEnum.WAIT_PAY.getCode());

        assertEquals("待支付", orderConvertor.toOrderVO(order).getStatusDesc());
        assertEquals("待支付", orderConvertor.toOrderDetailVO(order).getStatusDesc());
        assertEquals("待支付", merchantOrderConverter.toMerchantOrderListVO(order).getStatusDesc());
        assertEquals("待支付", merchantOrderConverter.toMerchantOrderDetailVO(order).getStatusDesc());
    }

    @Test
    void merchantAndProductConvertersPopulateStatusDescriptions() {
        Merchant merchant = new Merchant();
        merchant.setStatus(MerchantStatusEnum.BUSINESS_CLOSED.getCode());

        assertEquals("店铺已经打烊", merchantConverter.toMerchantListVO(merchant).getStatusDesc());
        assertEquals("店铺已经打烊", merchantConverter.toMerchantDetailVO(merchant).getStatusDesc());
        assertEquals("店铺已经打烊", merchantConverter.toMerchantUpdateVO(merchant).getStatusDesc());

        Product product = new Product();
        product.setStatus(ProductStatusEnum.ON_SALE.getCode());
        product.setStock(0);
        Category category = new Category();
        assertEquals("正在销售", productConverter.toMerchantProductVO(product, category).getStatusDesc());
        assertEquals("正在销售", merchantConverter.toProductVO(product).getStatusDesc());
        assertFalse(merchantConverter.toProductVO(product).getInStock());

        ProductDetailCacheDTO cache = new ProductDetailCacheDTO();
        cache.setStatus(ProductStatusEnum.SALE_OUT.getCode());
        cache.setInStock(true);
        assertEquals("已售罄", productConverter.toProductVO(cache).getStatusDesc());
        assertTrue(productConverter.toProductVO(cache).getInStock());
    }

    @Test
    void deliveryConvertersPopulateStatusAndDescriptions() {
        DeliveryTask task = new DeliveryTask();
        task.setStatus(DeliveryTaskEnums.DELIVERING.getCode());

        assertEquals(DeliveryTaskEnums.DELIVERING.getCode(),
                deliveryTaskConverter.toRiderTaskListVO(task).getStatus());
        assertEquals("骑手正在配送",
                deliveryTaskConverter.toRiderTaskListVO(task).getStatusDesc());
        assertEquals("骑手正在配送",
                deliveryTaskConverter.toRiderDeliveryDetailVO(task).getStatusDesc());
    }
}
