package org.example.takeout.DeliveryTask.Mapper;

import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.VO.RiderDeliveryDetailVO;
import org.example.takeout.DeliveryTask.VO.RiderTaskListVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface DeliveryTaskConverter {

    @Mapping(source = "id", target = "taskId")
    @Mapping(source = "status", target = "statusDesc", qualifiedByName = "deliveryStatusDescription")
    RiderTaskListVO toRiderTaskListVO(DeliveryTask deliveryTask);


    @Mapping(source = "status", target = "statusDesc", qualifiedByName = "deliveryStatusDescription")
    RiderDeliveryDetailVO toRiderDeliveryDetailVO(DeliveryTask deliveryTask);

    @Named("deliveryStatusDescription")
    default String deliveryStatusDescription(Integer status) {
        return DeliveryTaskEnums.descriptionOf(status);
    }
}
