package org.example.takeout.Order.Mapper;

import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.VO.OrderDetailVO;
import org.example.takeout.Order.VO.OrderItemVO;
import org.example.takeout.Order.VO.OrderVO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface OrderConvertor {
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void toOrder(CreateOrderDTO createOrderDTO, @MappingTarget Order order);

    @Mapping(source = "status", target = "statusDesc", qualifiedByName = "orderStatusDescription")
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    OrderDetailVO toOrderDetailVO(Order order);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    OrderItemVO  toOrderItemVO(OrderItem orderitem);

    @Mapping(source = "status", target = "statusDesc", qualifiedByName = "orderStatusDescription")
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    OrderVO toOrderVO(Order order);

    @Named("orderStatusDescription")
    default String orderStatusDescription(Integer status) {
        return OrderStatusEnum.descriptionOf(status);
    }

}
