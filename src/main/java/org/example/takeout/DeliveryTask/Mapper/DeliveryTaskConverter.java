package org.example.takeout.DeliveryTask.Mapper;

import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.VO.RiderDeliveryDetailVO;
import org.example.takeout.DeliveryTask.VO.RiderTaskListVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DeliveryTaskConverter {

    @Mapping(source = "id", target = "taskId")
    RiderTaskListVO toRiderTaskListVO(DeliveryTask deliveryTask);


    RiderDeliveryDetailVO toRiderDeliveryDetailVO(DeliveryTask deliveryTask);
}
