# 数据库迁移脚本镜像

`src/main/resources/db/migration` 是 Flyway 迁移的权威来源。应用启动时仅扫描该 classpath 目录，`deploy/migrations` 保存同名、同内容的 SQL 镜像，便于部署审阅。

本目录同步权威目录的所有 `B`、`V` SQL。`B20261008__initial_schema.sql` 用于已创建的空数据库，在应用启动时累计建表，再执行 `V20261009` 及以后的迁移；已有 Flyway 迁移历史的数据库会忽略该基线，继续升级。[Flyway 官方累计基线说明](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/baseline-migrations)介绍了 `B` 脚本的选择和执行规则。

新增变更时，在权威目录创建更高版本的 `V<版本>__<描述>.sql`，再同步到本目录。同类迁移的版本不得重复；累计基线可以与相同版本的 `V` 脚本并存。超时取消退避使用 `V20261008__timeout_cancel_backoff.sql`。

`flyway_schema_history` 已记录成功的版本必须保持文件名和内容不变。Flyway 会校验其校验和；不要通过修改旧脚本或直接修改迁移历史来修复结构差异，应新增版本迁移。不要手工重复执行已有成功记录的 SQL。

## 新迁移的数据处理

`V20261009` 只为缺失的历史配送奖励快照回填 `5.00` 元，该值来自当前 `DeliveryFeeCalculator` 固定奖励规则。已有奖励及时间字段保持原值；新任务继续由应用计算奖励。

`V20261010`、`V20261011` 在修改目标业务数据或约束前，使用临时表的命名 `CHECK` 约束进行预检。约束只用于本次会话，不会添加到业务表。

| 失败约束名 | 数据问题 |
|---|---|
| `chk_cart_header_single_merchant` | 同一用户的购物车包含多个商家 |
| `chk_cart_header_user_exists` | 购物车引用不存在的用户 |
| `chk_cart_header_positive_merchant` | 购物车商家 ID 小于等于 0 |
| `chk_product_category_not_null` | 商品分类 ID 为 NULL |
| `chk_product_category_exists` | 商品引用不存在的分类 |

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
