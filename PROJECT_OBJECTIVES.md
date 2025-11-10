# 多协议数据库网关引擎项目目标

## 1. 项目概述

多协议数据库网关引擎是一个数据库协议代理系统，旨在为不同的数据库系统提供统一的访问接口。该项目基于SQL解析器原理，使用Java 17和Spring Boot框架开发，支持多种数据库协议（如MySQL、PostgreSQL、Oracle等）的客户端连接，并能将这些连接代理到相应的后端数据库。

## 2. 核心目标

### 2.1 协议兼容性目标
- 实现完整的MySQL协议，使标准MySQL客户端能够无缝连接到网关
- 提供对PostgreSQL、Oracle、SQL Server等其他主流数据库协议的支持
- 确保各协议适配器符合相应数据库厂商的协议规范

### 2.2 数据库连接代理目标
- 支持根据用户提供的认证信息动态建立到目标数据库的连接
- 实现客户端与后端数据库之间的透明数据传输
- 提供安全的凭据处理机制，确保敏感信息不被泄露

### 2.3 SQL解析与转换目标
- 利用阿里巴巴Druid SQL解析器对SQL语句进行语法分析和验证
- 支持跨数据库方言的SQL转换功能
- 提供SQL注入防护等安全特性

### 2.4 性能与可扩展性目标
- 实现高效的连接池管理，优化资源利用率
- 提供负载均衡能力，支持高并发场景
- 设计模块化架构，便于添加新的协议适配器和数据库支持

## 3. 功能目标

### 3.1 基础功能
- 应用启动时自动初始化默认协议适配器
- 客户端连接时收集目标数据库认证信息
- 建立并维护客户端与目标数据库之间的代理连接
- 执行并转发SQL命令，返回执行结果

### 3.2 高级功能
- 多协议支持：同时支持多种数据库协议
- 连接池管理：优化数据库连接资源利用
- 监控与日志：提供运行状态监控和详细的操作日志
- 安全控制：实现身份验证、授权和审计功能

### 3.3 管理功能
- RESTful API用于远程管理网关状态
- 命令行界面用于本地管理和诊断
- 配置文件驱动的灵活配置机制

## 4. 技术目标

### 4.1 开发技术栈
- 核心语言：Java 17
- 框架：Spring Boot 3.1.0
- SQL解析：Druid 1.2.16
- 构建工具：Apache Maven 3.6+
- 测试框架：JUnit 5、Mockito

### 4.2 架构设计原则
- 微内核架构：核心引擎轻量级，协议适配器可插拔
- 面向接口编程：各组件通过接口交互，降低耦合度
- 可测试性：模块化设计，便于单元测试和集成测试


## 配置

在 `src/main/resources/application.yml` 中配置PostgreSQL网关：

```yaml
gateway:
  # 数据库类型配置
  proxy-db-type: postgresql
  # 代理端口
  proxy-port: 5433

  # 目标数据库配置
  target:
    host: localhost
    port: 5432
    username: postgres
    password: Aa123456.
    database: dmp
```

## 启动网关

```bash
# 编译项目
mvn clean package

# 启动网关
java -jar target/muti-protocol-gateway-egine-1.0.0-SNAPSHOT.jar
```

或者使用Spring Boot Maven插件：

```bash
mvn spring-boot:run
```

## 测试连接

### 使用psql客户端

```bash
# 连接到网关（端口5433）
psql -h localhost -p 5433 -U postgres -d dmp

# 输入密码后，可以执行SQL查询
```

### 测试SQL查询

```sql
-- 查看所有表
\dt

-- 创建测试表
CREATE TABLE test_table (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100),
    age INTEGER
);

-- 插入数据
INSERT INTO test_table (name, age) VALUES ('Alice', 25);
INSERT INTO test_table (name, age) VALUES ('Bob', 30);

-- 查询数据
SELECT * FROM test_table;

-- 更新数据
UPDATE test_table SET age = 26 WHERE name = 'Alice';

-- 删除数据
DELETE FROM test_table WHERE name = 'Bob';

-- 删除表
DROP TABLE test_table;
```
