package org.example.takeout.Merchant.Mapper;

import org.example.takeout.Merchant.VO.MerchantOrderDetailVO;
import org.example.takeout.Merchant.VO.MerchantOrderListVO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface MerchantOrderConverter {

    @Mapping(source = "id", target = "orderId")
    @Mapping(target = "productSummary", ignore = true)
    @Mapping(source = "status", target = "statusDesc", qualifiedByName = "orderStatusDescription")
    MerchantOrderListVO toMerchantOrderListVO(Order order);

    @Mapping(source = "id", target = "orderId")
    @Mapping(target = "items", ignore = true)
    @Mapping(source = "status", target = "statusDesc", qualifiedByName = "orderStatusDescription")
    MerchantOrderDetailVO toMerchantOrderDetailVO(Order order);

    @Named("orderStatusDescription")
    default String orderStatusDescription(Integer status) {
        return OrderStatusEnum.descriptionOf(status);
    }
}
