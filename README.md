# Tokeout

基于 Java 17、Spring Boot、MyBatis-Plus、MySQL 和 Redis 的外卖练习项目，实现用户下单、商家接单/拒单/出餐、骑手配送的最小业务闭环。仓库包含后端及最小同源骑手 Web 工作台；支付和退款为本地模拟。

项目文档统一放在 [`docs/`](docs/README.md)，本文件保留仓库入口。

| 从这里开始 | 内容 |
| --- | --- |
| [完整项目说明](docs/project-guide.md) | 技术栈、模块、环境变量、启动、测试与数据库初始化 |
| [骑手 Web 真实定位](docs/rider-web.md) | 同源页面、浏览器定位、位置上报、任务列表与手机 HTTPS 限制 |
| [架构与业务流程](docs/architecture/README.md) | 模块依赖、订单创建、履约、缓存、购物车与实现边界 |
| [Redis 接入与故障边界](docs/project-guide.md#8-redis-的接入范围与故障边界) | 商品缓存、登录与下单限流、骑手位置、任务 GEO 与降级策略 |
| [数据库结构](docs/数据库结构.md) / [迁移说明](docs/database-migrations.md) | 表、索引、版本化升级与部署 SQL 镜像 |
| [状态流转](docs/状态流转表.md) / [幂等设计](docs/IdempotencyDesign.md) | 订单、配送、取消来源与重复请求处理 |
| [当前风险复核](docs/当前项目风险与架构复核_2026-10-11.md) | 尚未修复风险、已接受边界与历史验证记录 |
| [本次文档核查](docs/documentation-review-2026-10-28.md) | GEO 接入、全量对账、测试证据与仓库文档链接核验 |

本地启动需要 JDK 17、MySQL 8，以及 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`JWT_SECRET`；具体命令见[启动说明](docs/project-guide.md#6-启动方式)。数据库核心交易约束不依赖 Redis，缓存、限流和 GEO 行为的验证需要 Redis。

文档以当前工作区源码、SQL 和配置为准，包含未提交改动。测试用例存在与本次实际运行通过分别记录。
