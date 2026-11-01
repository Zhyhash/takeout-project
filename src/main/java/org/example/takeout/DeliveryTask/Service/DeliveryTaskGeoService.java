package org.example.takeout.DeliveryTask.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.GeoCoordinates;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.DeliveryTask.VO.DeliveryTaskDistanceVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 可接取配送任务的 GEO 位置服务。
 * 配送任务出餐后写入 GEO，被抢单或任务失效后删除，骑手按距离查询附近可接任务。
 * 与骑手位置不同，待接取任务只要没被抢单就应持续保留，因此这里不维护“最后活跃时间”，
 * 也不做超时清理；任务是否仍然可接以数据库中配送任务状态为准。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryTaskGeoService {

    private static final String DEFAULT_AVAILABLE_TASK_GEO_KEY = "delivery:available:geo";
    private static final int NEARBY_TASK_LIMIT = 100;

    private final StringRedisTemplate redisTemplate;
    private final DeliveryTaskMapper deliveryTaskMapper;
    private final PlatformTransactionManager transactionManager;

    @Value("${delivery.geo.available-key:delivery:available:geo}")
    private String availableTaskGeoKey = DEFAULT_AVAILABLE_TASK_GEO_KEY;



    /**
     * 写入或更新待接取配送任务的取货坐标。
     * GEOADD 对已存在的任务只更新坐标，返回 0 同样表示写入成功。
     */
    public void addAvailableTask(Long taskId, double longitude, double latitude) {
        validateTaskId(taskId);
        validateCoordinates(longitude, latitude);

        Long addResult;
        try {
            addResult = redisTemplate.opsForGeo().add(
                    availableTaskGeoKey,
                    new Point(longitude, latitude),
                    String.valueOf(taskId)
            );
        } catch (Exception e) {
            throw new RedisGeoOperationException("可接配送任务写入失败，taskId=" + taskId, e);
        }

        if (addResult == null) {
            throw new RedisGeoOperationException("配送任务位置写入未得到成功确认，taskId=" + taskId);
        }
    }

    /**
     * 删除待接取配送任务的位置，适用于抢单成功或任务失效。
     * 任务本来就不在 GEO 中时 ZREM 返回 0，按幂等成功处理。
     */
    public void removeAvailableTask(Long taskId) {
        validateTaskId(taskId);

        try {
            Long removeResult = redisTemplate.opsForGeo().remove(
                    availableTaskGeoKey,
                    String.valueOf(taskId)
            );
            if (removeResult == null) {
                throw new RedisGeoOperationException("配送任务位置删除未得到成功确认，taskId=" + taskId);
            }
        } catch (RedisGeoOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisGeoOperationException("可接配送任务删除失败，taskId=" + taskId, e);
        }
    }

    /**
     * 查询指定坐标附近的可接取配送任务，按距离升序返回，最多 100 条。
     */
    public List<DeliveryTaskDistanceVO> findNearbyTasks(
            double longitude, double latitude, double radiusKm) {

        validateCoordinates(longitude, latitude);
        validateRadius(radiusKm);

        Circle searchArea = new Circle(
                new Point(longitude, latitude),
                new Distance(radiusKm, Metrics.KILOMETERS)
        );

        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults;
        try {
            geoResults = redisTemplate.opsForGeo().radius(
                    availableTaskGeoKey,
                    searchArea,
                    RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                            .includeDistance()
                            .sortAscending()
                            .limit(NEARBY_TASK_LIMIT)
            );
        } catch (Exception e) {
            throw new RedisGeoOperationException("附近可接配送任务查询失败，请稍后重试", e);
        }

        if (geoResults == null) {
            throw new RedisGeoOperationException("GEO查询未返回结果");
        }

        List<DeliveryTaskDistanceVO> nearbyTasks = new ArrayList<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult : geoResults) {

            String taskId = geoResult.getContent().getName();
            Distance distance = geoResult.getDistance();
            if (parseCanonicalTaskId(taskId) == null
                    || !Double.isFinite(distance.getValue())
                    || distance.getValue() < 0) {
                log.warn("忽略非法的可接配送任务 GEO 结果，taskId={}", taskId);
                continue;
            }
            nearbyTasks.add(new DeliveryTaskDistanceVO(taskId, distance));
        }
        return nearbyTasks;
    }

    /**
     * 查询单个待接取任务的取货坐标，任务不在 GEO 中时返回空。
     */
    public Optional<Point> getAvailableTaskLocation(Long taskId) {
        validateTaskId(taskId);

        List<Point> positions;
        try {
            positions = redisTemplate.opsForGeo().position(
                    availableTaskGeoKey,
                    String.valueOf(taskId)
            );
        } catch (Exception e) {
            throw new RedisGeoOperationException("查询配送任务地理位置失败，taskId=" + taskId, e);
        }

        if (positions == null) {
            throw new RedisGeoOperationException("查询配送任务地理位置未得到结果，taskId=" + taskId);
        }
        if (positions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(positions.get(0));
    }


    public void reconcileAllAvailableTasksGeo() {
        try {
            // MySQL 权威集合
            Set<Long> mysqlIds = deliveryTaskMapper
                    .selectWaitAssignTasks(DeliveryTaskEnums.WAIT_ASSIGN.getCode())
                    .stream()
                    .map(DeliveryTask::getId)
                    .collect(Collectors.toSet());

            // Redis GEO 实际集合
            Set<String> redisMembers = redisTemplate.opsForZSet()
                    .range(availableTaskGeoKey, 0, -1);

            Set<Long> redisIds = new HashSet<>();

            if (redisMembers != null) {
                for (String member : redisMembers) {
                    Long taskId = parseCanonicalTaskId(member);

                    if (taskId != null) {
                        redisIds.add(taskId);
                        continue;
                    }

                    try {
                        Long result = redisTemplate.opsForGeo()
                                .remove(availableTaskGeoKey, member);

                        if (result == null) {
                            log.error("删除非法可接配送任务 GEO 成员未得到成功确认，member={}", member);
                        }
                    } catch (RuntimeException e) {
                        log.error(
                                "删除非法可接配送任务 GEO 成员失败，留待后续重试，member={}",
                                member, e);
                    }
                }
            }

            // 两边不一致的任务都需要重新按 MySQL 当前状态修复
            Set<Long> needRepair = new HashSet<>(mysqlIds);
            needRepair.removeAll(redisIds);

            Set<Long> needRemove = new HashSet<>(redisIds);
            needRemove.removeAll(mysqlIds);

            needRepair.addAll(needRemove);

            for (Long taskId : needRepair) {
                try {
                    reconcileAvailableTaskGeo(taskId);
                } catch (RuntimeException e) {
                    log.error(
                            "配送任务 {} GEO 对账失败，留待后续重试",
                            taskId, e);
                }
            }

        } catch (RedisGeoOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RedisGeoOperationException(
                    "可接配送任务 GEO 全量对账失败", e);
        }
    }


    public void reconcileAvailableTaskGeo(Long taskId) {
        validateTaskId(taskId);

        try {
            // 全量对账的内部调用和 afterCommit 回调都需要独立事务，
            // 行锁必须持续到 Redis 更新结束，防止抢单删除后又按旧状态补回 GEO。
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status -> {
                DeliveryTask task = deliveryTaskMapper.selectOne(
                        Wrappers.<DeliveryTask>lambdaQuery()
                                .eq(DeliveryTask::getId, taskId)
                                .last("FOR UPDATE"));

                if (task == null) {
                    removeAvailableTask(taskId);
                    return;
                }

                repairTaskGeo(task);
            });
        } catch (RedisGeoOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RedisGeoOperationException(
                    "可接配送任务 GEO 对账失败，taskId=" + taskId, e);
        }
    }


    private void repairTaskGeo(DeliveryTask task) {
        Long taskId = task.getId();

        if (!Objects.equals(task.getStatus(), DeliveryTaskEnums.WAIT_ASSIGN.getCode())
                || task.getRiderId() != null
                || hasInvalidPickupCoordinates(task)) {

            removeAvailableTask(taskId);

            if (Objects.equals(task.getStatus(), DeliveryTaskEnums.WAIT_ASSIGN.getCode())
                    && hasInvalidPickupCoordinates(task)) {
                log.warn("配送任务 {} 缺少有效取货坐标，已清除可接任务 GEO 位置", taskId);
            }
            return;
        }

        addAvailableTask(taskId,
                task.getPickupLongitude().doubleValue(),
                task.getPickupLatitude().doubleValue());
    }

    private boolean hasInvalidPickupCoordinates(DeliveryTask task) {
        if (task.getPickupLongitude() == null || task.getPickupLatitude() == null) {
            return true;
        }
        return !GeoCoordinates.isValid(
                task.getPickupLongitude().doubleValue(),
                task.getPickupLatitude().doubleValue());
    }



    private Long parseCanonicalTaskId(String member) {
        if (member == null || member.isEmpty()) {
            return null;
        }
        try {
            long taskId = Long.parseLong(member);
            return taskId > 0 && Long.toString(taskId).equals(member) ? taskId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }



    private void validateTaskId(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "配送任务ID不合法");
        }
    }

    private void validateRadius(double radiusKm) {
        if (!Double.isFinite(radiusKm) || radiusKm <= 0) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "附近距离必须大于0");
        }
    }

    private void validateCoordinates(double longitude, double latitude) {
        GeoCoordinates.validate(longitude, latitude);
    }
}
