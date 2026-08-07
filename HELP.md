# 开发快速入口

项目当前使用 Java 17、Spring Boot 4.0.6、MyBatis-Plus、MySQL 8 和 Spring Data Redis。项目概览、并发方案与完整测试要求已统一维护在 [README.md](README.md)。

## 文档导航

- [README.md](README.md)：项目结构、已解决竞态、订单最终方案、Redis 实验边界和测试命令；
- [IdempotencyDesign.md](IdempotencyDesign.md)：逐类接口的幂等判断与当前结论；
- [数据库结构.md](数据库结构.md)：九个实体与 MySQL 测试 DDL 交叉核对后的字段、索引和外键；
- [数据库设计检查报告.md](数据库设计检查报告.md)：当前仍需处理的数据库结构问题。
- [状态流转表.md](状态流转表.md)：用户、商家、骑手、订单与配送任务的当前闭环和状态契约。

## 常用命令

```powershell
# Windows：完整测试
.\mvnw.cmd test

# Wrapper 在当前 PowerShell 环境无法启动时
mvn test

# 跳过 Redis 专用测试，运行其余全部测试
mvn test "-Dtest=!RedisBasicIntegrationTest,!RedisServiceIntegrationTest"

# Windows：启动应用
.\mvnw.cmd spring-boot:run
```

非 Redis 全量测试需要本机 MySQL；测试会重建 `takeout_integration_test` 中的九张表。只有执行两个 Redis 专用测试时才需要本机 Redis。执行前请阅读 README 中的测试数据库重建提示。

## 官方资料

- [Apache Maven](https://maven.apache.org/guides/index.html)
- [Spring Boot Maven Plugin 4.0.6](https://docs.spring.io/spring-boot/4.0.6/maven-plugin)
- [Spring Web MVC](https://docs.spring.io/spring-boot/4.0.6/reference/web/servlet.html)
- [Spring Data Redis](https://docs.spring.io/spring-data/redis/reference/)
- [MyBatis-Plus](https://baomidou.com/)
