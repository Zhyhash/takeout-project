package org.example.takeout.DeliveryTask.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;

import java.util.List;

@Mapper
public interface DeliveryTaskMapper extends BaseMapper<DeliveryTask> {

    @Select("""
            SELECT *
            FROM delivery_task
            WHERE status = #{status}
              AND rider_id IS NULL
            ORDER BY id ASC
            """)
    List<DeliveryTask> selectWaitAssignTasks(
            @Param("status") Integer status);
}
