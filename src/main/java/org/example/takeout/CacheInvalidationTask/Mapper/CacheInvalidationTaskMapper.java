package org.example.takeout.CacheInvalidationTask.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.CacheInvalidationTask.Entity.CacheInvalidationTask;

import java.time.LocalDateTime;

@Mapper
public interface CacheInvalidationTaskMapper extends BaseMapper<CacheInvalidationTask> {
    @Delete("""
            DELETE FROM cache_invalidation_task
            WHERE status = #{successStatus}
              AND created_time <= #{createdBefore}
              AND id IN (
                  SELECT id FROM (
                      SELECT id FROM cache_invalidation_task
                      WHERE status = #{successStatus}
                        AND created_time <= #{createdBefore}
                      ORDER BY created_time ASC, id ASC
                      LIMIT 1000
                  ) expired_success_tasks
              )
            """)
    int deleteExpiredSuccessfulTasks(@Param("successStatus") Integer successStatus,
                                    @Param("createdBefore") LocalDateTime createdBefore);

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
            SET status = CASE
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
                            ELSE 300
                        END,
                        CURRENT_TIMESTAMP
                    )
                END,
                retry_count = retry_count + 1
            WHERE id = #{id}
              AND status = #{status}
            ORDER BY next_retry_time
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
            ORDER BY next_retry_time
             """)
    int recordLongIntervalRetryFailure(@Param("id") Long id,
                            @Param("status") Integer status);
}
