-- 恢复机制简化：去掉跨实例租约、冗余的幂等过期时间列与诊断列。
--
-- 为什么可以删：
--   1. 租约（lease_token / lease_until）只为多实例同时恢复同一笔订单而存在。
--      占位改由 claimAttempt 把 next_retry_at 推到未来完成，第二个调用者的
--      "next_retry_at <= now" 条件随即不成立，互斥效果等价。
--   2. idempotency_expires_at 恒等于 create_time + 23h，属于可推导数据，不应单独存储。
--   3. last_error 是运维诊断信息，改由应用日志承载，不参与任何业务判断。
--
-- 保留：reconciliation_state（是否需要继续对账）、retry_count（退避与上限）、
--       next_retry_at（何时再试 + 占位标记）。
ALTER TABLE order_stripe_payments
    DROP COLUMN lease_token,
    DROP COLUMN lease_until,
    DROP COLUMN idempotency_expires_at,
    DROP COLUMN last_error;
