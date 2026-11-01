# 三端 GEO 演示测试数据

本文件对应的数据脚本是 [`deploy/demo-geo-test-data.sql`](../deploy/demo-geo-test-data.sql)，仅用于本地开发/测试环境。

## 1. 数据范围

仓库原有的 `deploy/demo-data.sql` 是较早的演示数据（密码和上海坐标），本次未修改它。本文件是独立的 GEO 测试 seed，请勿在验证前再用旧脚本覆盖本次账号与商家坐标。

脚本只维护以下基础数据，可重复执行：

- 三个演示账号：`demo_user` / `demo_merchant` / `demo_rider`
- 统一明文密码：`12345678`（数据库内保存项目真实使用的 BCrypt hash）
- 重邮演示餐厅、两个分类、三个在售商品
- 演示用户的 `cart_header`

脚本明确**不会**：

- 插入、修改或删除 `orders` / `delivery_task`
- 直接写 Redis GEO
- 删除历史任务或普通业务数据

订单和配送任务应通过真实接口产生，以便验证库存扣减、支付、商家接单、出餐建任务、坐标快照和 GEO 写入。

## 2. 当前环境与坐标

| 项目 | 值 |
|---|---|
| Docker MySQL | `127.0.0.1:3307`，数据库 `takeout` |
| 本机 MySQL | `127.0.0.1:3306`，数据库 `takeout` |
| Docker Redis | `127.0.0.1:6379` |
| 商家名称 | `重邮演示餐厅` |
| 商家地址 | 重庆市南岸区崇文路2号 重庆邮电大学（本地演示地址） |
| 商家经度 | `106.6078000` |
| 商家纬度 | `29.5319000` |
| 默认查询半径 | `5 km` |

坐标位于重庆邮电大学，符合 `GeoCoordinates` 的经度 `[-180, 180]`、纬度 `[-85.05112878, 85.05112878]` 校验范围。

## 3. 执行脚本

### 3.1 当前机器（使用 Docker 内的 mysql 客户端）

```powershell
docker cp deploy/demo-geo-test-data.sql tokeout-mysql-1:/tmp/demo-geo-test-data.sql

# Docker MySQL（3307）
docker exec tokeout-mysql-1 sh -c 'mysql --default-character-set=utf8mb4 -uroot -proot < /tmp/demo-geo-test-data.sql'

# 本机 MySQL（3306）
docker exec tokeout-mysql-1 sh -c 'mysql --default-character-set=utf8mb4 -h host.docker.internal -P 3306 -uroot -proot < /tmp/demo-geo-test-data.sql'
```

### 3.2 安装了本机 mysql 客户端时

```powershell
mysql -h 127.0.0.1 -P 3307 -u root -proot --default-character-set=utf8mb4 < deploy/demo-geo-test-data.sql
mysql -h 127.0.0.1 -P 3306 -u root -proot --default-character-set=utf8mb4 < deploy/demo-geo-test-data.sql
```

执行前先确认端口对应的数据库：

```sql
SELECT DATABASE();
SELECT @@port;
```

## 4. 手工验证顺序

当前 `8080` 应用连接的是 Docker MySQL（3307）。测试期间只启动一个应用实例，避免两个数据库实例共用同一个 Redis 数据库导致任务 GEO 对账互相清理。

1. 用户端登录 `demo_user`，选择演示商品加入购物车。
2. 创建订单并调用 `PATCH /order/{id}/pay`。
3. 商家端登录 `demo_merchant`，调用 `PATCH /merchant/orders/{id}/accept`，再调用 `PATCH /merchant/orders/{id}/ready`。
4. 检查新任务：`delivery_task.pickup_longitude/pickup_latitude` 应为商家坐标快照；Redis 的 `delivery:available:geo` 应包含该任务 ID。
5. 骑手端打开 `http://127.0.0.1:8080/rider-web/index.html`，登录 `demo_rider`，允许浏览器真实定位，查询 5 km 内可接任务。
6. 预期新任务排在旧任务 `id = 1` 前；旧任务没有取货坐标，不在 GEO 成员中，只作为 MySQL 侧对照样本。
7. 点击抢单，验证任务状态变为配送中且 Redis GEO 成员被删除。

浏览器定位必须在 `127.0.0.1` 或 HTTPS 页面使用；手机通过局域网 HTTP 访问时浏览器可能禁止定位。

## 5. Redis 与历史任务注意事项

当前实测 Redis 没有登录失败限流 key，也没有骑手位置 key。若手工试错导致演示账号被限流，只清理对应测试 key，不要修改认证逻辑：

```powershell
docker exec tokeout-redis-1 redis-cli DEL `
  security:login:fail:user:demo_user `
  security:login:fail:merchant:demo_merchant `
  security:login:fail:rider:demo_rider
```

Docker 数据库保留了历史 `delivery_task.id = 1`：

- `pickup_longitude = NULL`
- `pickup_latitude = NULL`
- `status = 0`（WAIT_ASSIGN）

本脚本不修改该行。本机 `takeout` 库在本次操作前没有订单/配送任务，因此没有可保留的同 ID 历史行。

## 6. 已知边界

- 已通过真实 HTTP 接口验证三端登录、商家营业状态、坐标、商品库存和一次 Docker 库的下单到出餐建任务链路。
- 已通过接口上报骑手位置并验证附近任务顺序；没有执行真实浏览器的物理定位，不能把接口验证描述成浏览器定位验收。
- Docker 库当前预置了一张由真实接口产生的 `READY` 订单和对应的 `WAIT_ASSIGN` 任务，方便直接验证骑手端。若要手工走完整“下单→商家接单→出餐”，请再创建一张新订单。
