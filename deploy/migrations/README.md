# 数据库迁移脚本镜像

`src/main/resources/db/migration` 是 Flyway 迁移的权威来源。应用启动时仅扫描该 classpath 目录，`deploy/migrations` 保存同名、同内容的 SQL 镜像，便于部署审阅。

本目录同步权威目录的所有 `B`、`V` SQL。`B20261008__initial_schema.sql` 用于已创建的空数据库，在应用启动时累计建表，再执行 `V20261009` 及以后的迁移；已有 Flyway 迁移历史的数据库会忽略该基线，继续升级。[Flyway 官方累计基线说明](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/baseline-migrations)介绍了 `B` 脚本的选择和执行规则。

当前两个目录各有 15 份 SQL，最新版本为 `V20261014`。空库执行累计基线后，再执行 `V20261009` 至 `V20261014` 共 6 份后续迁移；已处于 `20261008` 基线的旧库也升级这 6 个版本。

新增变更时，在权威目录创建更高版本的 `V<版本>__<描述>.sql`，再同步到本目录。同类迁移的版本不得重复；累计基线可以与相同版本的 `V` 脚本并存。超时取消退避使用 `V20261008__timeout_cancel_backoff.sql`。

`flyway_schema_history` 已记录成功的版本必须保持文件名和内容不变。Flyway 会校验其校验和；不要通过修改旧脚本或直接修改迁移历史来修复结构差异，应新增版本迁移。不要手工重复执行已有成功记录的 SQL。

## 新迁移的数据处理

`V20261009` 只为缺失的历史配送奖励快照回填 `5.00` 元，该值来自当前 `DeliveryFeeCalculator` 固定奖励规则。已有奖励及时间字段保持原值；新任务继续由应用计算奖励。

`V20261010`、`V20261011` 在修改目标业务数据或约束前，使用临时表的命名 `CHECK` 约束进行预检。约束只用于本次会话，不会添加到业务表。历史 `V20261012` 将 `merchant.username` 和作为骑手登录名的 `rider.name` 改为 `utf8mb4_0900_as_cs`，但大小写/重音敏感规则仍可能把部分不同 Unicode 字符序列视为相同。

`V20261013__login_name_binary_collation.sql` 是此前已执行过的二进制排序迁移，保留文件及校验和。当前方案由 `V20261014__restore_login_name_as_cs.sql` 恢复 `user.username`、`merchant.username`、`rider.name` 的 `utf8mb4_0900_as_cs`；输入边界由三个角色注册、登录共六个 DTO 的统一 `@Pattern` 限制为英文字母、数字、下划线、连字符，长度 1–50。Redis 原文 key、账号数据及基线/历史迁移均不改写。

V14 在任何列修改前，先检查三张表是否有不同名称在 `as_cs` 下合并为同一唯一键。任一冲突会中止迁移；不得静默合并、删除或改名。预检只用会话临时表，不给业务表增加 CHECK 或改变其他字段。现有业务库是否已应用 V14，仍须以其 Flyway 历史和列定义为准。

| 失败约束名 | 数据问题 |
|---|---|
| `chk_cart_header_single_merchant` | 同一用户的购物车包含多个商家 |
| `chk_cart_header_user_exists` | 购物车引用不存在的用户 |
| `chk_cart_header_positive_merchant` | 购物车商家 ID 小于等于 0 |
| `chk_product_category_not_null` | 商品分类 ID 为 NULL |
| `chk_product_category_exists` | 商品引用不存在的分类 |
| `chk_user_login_name_as_cs_unique` | User 登录名在 `as_cs` 下发生唯一键合并冲突 |
| `chk_merchant_login_name_as_cs_unique` | Merchant 登录名在 `as_cs` 下发生唯一键合并冲突 |
| `chk_rider_login_name_as_cs_unique` | Rider 登录名在 `as_cs` 下发生唯一键合并冲突 |

可在目标数据库用以下只读 SQL 定位问题：

```sql
SELECT user_id, COUNT(DISTINCT merchant_id) AS merchant_count
FROM cart GROUP BY user_id HAVING COUNT(DISTINCT merchant_id) > 1;

SELECT c.id, c.user_id
FROM cart c LEFT JOIN `user` u ON u.id = c.user_id
WHERE u.id IS NULL;

SELECT id, user_id, merchant_id FROM cart WHERE merchant_id <= 0;

SELECT id FROM product WHERE category_id IS NULL;

SELECT p.id, p.category_id
FROM product p LEFT JOIN category c ON c.id = p.category_id
WHERE p.category_id IS NOT NULL AND c.id IS NULL;
```

出现预检错误时，先明确并处理对应的业务数据，再核对失败迁移已执行的步骤和历史记录，按 Flyway 失败迁移恢复流程重试。MySQL DDL 可能分步提交，不能假定整次迁移已回滚。不要修改已成功版本，也不要使用 `repair` 更新成功版本的校验和来掩盖实际结构或数据差异。

## 同步镜像

从项目根目录同步部署镜像：

```powershell
Get-ChildItem -LiteralPath 'src/main/resources/db/migration' -Filter '*.sql' -File |
    ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path 'deploy/migrations' $_.Name) -Force
    }
```

同步后应核对两个目录的 SQL 文件名集合和内容。`deploy/schema.sql` 负责空库初始化，已有数据库的升级由 Flyway 新版本完成。
