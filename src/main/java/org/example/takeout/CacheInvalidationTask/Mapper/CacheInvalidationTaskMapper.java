package org.example.takeout.CacheInvalidationTask.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.CacheInvalidationTask.Entity.CacheInvalidationTask;

@Mapper
public interface CacheInvalidationTaskMapper extends BaseMapper<CacheInvalidationTask> {
    @Update("""
            UPDATE cache_invalidation_task
            SET status = 1
            WHERE cache_key = #{cacheKey}
              AND status = #{status}
            """)
    int updatePendingToSuccess(@Param("cacheKey") String cacheKey,
                               @Param("status") Integer status);

    @Update("""
            UPDATE cache_invalidation_task
            SET status = 1
            WHERE id = #{id}
              AND status = #{status}
            """)
    int markSuccess(@Param("id") Long id,
                    @Param("status") Integer status);

    @Update("""
            UPDATE cache_invalidation_task
            SET retry_count = retry_count + 1,
                status = CASE
                    WHEN retry_count >= 4 THEN #{failedStatus}
                    ELSE #{pendingStatus}
                END,
                next_retry_time = CASE
                    WHEN retry_count >= 4 THEN next_retry_time
                    ELSE TIMESTAMPADD(
                        SECOND,
                        CASE retry_count
                            WHEN 0 THEN 10
                            WHEN 1 THEN 30
                            WHEN 2 THEN 60
                            WHEN 3 THEN 120
                        END,
                        CURRENT_TIMESTAMP
                    )
                END
            WHERE id = #{id}
              AND status = #{status}
            """)
    int recordShortPeriodRetryFailure(@Param("id") Long id,
                                      @Param("status") Integer status,
                                      @Param("pendingStatus") Integer pendingStatus,
                                      @Param("failedStatus") Integer failedStatus);

    @Update("""
             
            UPDATE cache_invalidation_task
             SET retry_count = retry_count + 1,
                 next_retry_time = TIMESTAMPADD(HOUR, 1, NOW())
             WHERE id = #{id}
               AND status = #{status}
             """)
    int recordLongIntervalRetryFailure(@Param("id") Long id,
                            @Param("status") Integer status);
}
