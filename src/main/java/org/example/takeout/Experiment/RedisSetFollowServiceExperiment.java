package org.example.takeout.Experiment;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 Redis Set 的用户关注关系实验服务，不参与实际业务流程。
 * 演示关注与取关、关注和粉丝查询，以及通过集合交集、差集查询共同关注及推荐候选。
 */
@Service
@RequiredArgsConstructor

//NOTE：这个类是实验类，不参与任何实际相关业务代码
public class RedisSetFollowServiceExperiment {
    private final StringRedisTemplate stringRedisTemplate;



    void follow(Long userId, Long targetUserId) {
        stringRedisTemplate.opsForSet().add(
                buildFollowsKey(userId),        // user:我:follows
                targetUserId.toString()         // 加进去的是：我关注的人
        );

        stringRedisTemplate.opsForSet().add(
                buildFollowersKey(targetUserId), // user:对方:followers
                userId.toString()                // 加进去的是：关注对方的人（我）
        );

    }

    void unfollow(Long userId, Long targetUserId) {
        stringRedisTemplate.opsForSet().remove(
                buildFollowsKey(userId),        // user:我:follows
                targetUserId.toString()         // 加进去的是：我关注的人
        );

        stringRedisTemplate.opsForSet().remove(
                buildFollowersKey(targetUserId), // user:对方:followers
                userId.toString()                // 加进去的是：关注对方的人（我）
        );
    }

    boolean isFollowing(Long userId, Long targetUserId) {
        return  stringRedisTemplate.opsForSet().
                isMember(buildFollowsKey(userId), targetUserId.toString());
    }

    Set<Long> getFollowing(Long userId) {
        Set<String> members = stringRedisTemplate.opsForSet().
                members(buildFollowsKey(userId));
        return getLongSet(members);
    }

    Set<Long> getFollowers(Long userId) {
        Set<String> members = stringRedisTemplate.opsForSet().
                members(buildFollowersKey(userId));
        return getLongSet(members);
    }

    private static @NonNull Set<Long> getLongSet(Set<String> members) {
        return members.stream().map(Long::parseLong).collect(Collectors.toSet());
    }

    Set<Long> getMutualFollowing(Long userA, Long userB) {
        Set<String> intersect = stringRedisTemplate.opsForSet().
                intersect(buildFollowsKey(userA), buildFollowsKey(userB));
        return getLongSet(intersect);

    }

    Set<Long> getFollowingDifference(Long userA, Long userB) {
        Set<String> difference = stringRedisTemplate.opsForSet().
                difference(buildFollowsKey(userA), buildFollowsKey(userB));
        return getLongSet(difference);
    }

    Set<Long> getRecommendationCandidates(Long userId, Long sourceUserId) {
        Set<String> difference = stringRedisTemplate.opsForSet().
                difference(buildFollowsKey(sourceUserId), buildFollowsKey(userId));
        difference.remove(userId.toString());
        return getLongSet(difference);
    }



    private String buildFollowsKey(Long userId) {
        return "user:"+userId+":follows";
    }
    private String buildFollowersKey(Long userId) {
        return "user:"+userId+":followers";
    }
}
