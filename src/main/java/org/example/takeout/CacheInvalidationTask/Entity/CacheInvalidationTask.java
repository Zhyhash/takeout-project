package org.example.takeout.CacheInvalidationTask.Entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cache_invalidation_task")
public class CacheInvalidationTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cacheKey;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private LocalDateTime createdTime;
}
