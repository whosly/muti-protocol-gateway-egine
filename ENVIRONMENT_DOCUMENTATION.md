# 多协议数据库网关引擎环境文档

## 1. 开发环境

### 1.1 操作系统
- 主要开发环境：Windows 10/11 64位
- 兼容环境：Linux (Ubuntu 20.04+)、macOS (10.15+)

### 1.2 Java环境
- JDK版本：Java 17 (Azul Zulu或OpenJDK)
- 推荐JDK：Azul Zulu JDK 17.0.14
- JVM参数：
  ```
  -Xms512m
  -Xmx2g
  -XX:+UseG1GC
  ```

### 1.3 构建工具
- Apache Maven 3.6或更高版本
- 推荐版本：Apache Maven 3.9.9
- Maven设置：
  ```xml
  <settings>
    <mirrors>
      <mirror>
        <id>aliyunmaven</id>
        <mirrorOf>*</mirrorOf>
        <name>阿里云公共仓库</name>
        <url>https://maven.aliyun.com/repository/public</url>
      </mirror>
    </mirrors>
  </settings>
  ```

### 1.4 集成开发环境(IDE)
- 推荐IDE：IntelliJ IDEA 2023.1+ 或 Eclipse 2022.12+
- 必需插件：
  - Lombok plugin
  - Spring Boot plugin
  - Maven plugin

## 2. 运行环境

### 2.1 服务器要求
- 操作系统：Windows Server 2019+/Linux (CentOS 7+/Ubuntu 20.04+)
- 内存：最低4GB，推荐8GB以上
- 存储空间：至少10GB可用磁盘空间
- CPU：双核处理器，推荐四核以上

### 2.2 Java运行时环境
- JRE版本：Java 17
- 推荐：Azul Zulu JRE 17.0.14或OpenJDK 17
- 启动参数示例：
  ```bash
  java -Xms1g -Xmx4g -XX:+UseG1GC -jar muti-protocol-gateway-egine-1.0.0-SNAPSHOT.jar
  ```

### 2.3 网络环境
- 端口要求：
  - 8080：HTTP管理接口
  - 3306：MySQL协议监听端口
  - 5432：PostgreSQL协议监听端口（预留）
  - 1521：Oracle协议监听端口（预留）
- 带宽：建议100Mbps网络连接
- 防火墙：需要开放相应端口

## 3. 依赖环境

### 3.1 第三方库依赖
| 组件 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.1.0 | 应用框架 |
| Alibaba Druid | 1.2.16 | SQL解析器 |
| MySQL Connector | 8.0.33 | MySQL数据库连接 |
| Lombok | 1.18.30 | 减少样板代码 |
| Guava | 32.1.1-jre | 工具类库 |

### 3.2 测试依赖
| 组件 | 版本 | 用途 |
|------|------|------|
| JUnit | 5.9.3 | 单元测试框架 |
| Mockito | 5.3.1 | Mock框架 |
| AssertJ | 3.24.2 | 断言库 |

## 4. 数据库环境

### 4.1 支持的数据库
- MySQL 5.7及以上版本
- PostgreSQL 12及以上版本
- Oracle 12c及以上版本
- Microsoft SQL Server 2019及以上版本

### 4.2 数据库连接要求
- 数据库服务器必须可通过网络访问
- 需要具有相应权限的数据库用户账号
- 数据库防火墙需允许来自网关的连接

### 4.3 连接池配置
```yaml
gateway:
  connection:
    pool:
      initial-size: 5      # 初始连接数
      max-size: 20         # 最大连接数
      min-idle: 2          # 最小空闲连接数
```

## 5. 配置环境

### 5.1 应用配置文件
- 主配置文件：`src/main/resources/application.yml`
- 环境特定配置：
  - 开发环境：`application-dev.yml`
  - 测试环境：`application-test.yml`
  - 生产环境：`application-prod.yml`

### 5.2 关键配置项
```yaml
server:
  port: 8080                           # HTTP服务端口

spring:
  application:
    name: multi-protocol-gateway-engine # 应用名称

gateway:
  protocols:
    mysql:
      enabled: true                    # 是否启用MySQL协议
      port: 3306                       # MySQL协议端口
    postgresql:
      enabled: true                    # 是否启用PostgreSQL协议
      port: 5432                       # PostgreSQL协议端口
    oracle:
      enabled: false                   # 是否启用Oracle协议
      port: 1521                       # Oracle协议端口
  
  connection:
    pool:
      initial-size: 5                  # 连接池初始大小
      max-size: 20                     # 连接池最大大小
      min-idle: 2                      # 连接池最小空闲连接数

  security:
    authentication:
      enabled: true                    # 是否启用身份验证
```

## 6. 部署环境

### 6.1 容器化部署（Docker）
Dockerfile示例：
```dockerfile
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY target/muti-protocol-gateway-egine-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8080 3306
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 6.2 容器编排（Docker Compose）
```yaml
version: '3.8'
services:
  gateway:
    build: .
    ports:
      - "8080:8080"
      - "3306:3306"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
    volumes:
      - ./logs:/app/logs
```

### 6.3 Kubernetes部署
Deployment配置示例：
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: database-gateway
spec:
  replicas: 2
  selector:
    matchLabels:
      app: database-gateway
  template:
    metadata:
      labels:
        app: database-gateway
    spec:
      containers:
      - name: gateway
        image: database-gateway:latest
        ports:
        - containerPort: 8080
        - containerPort: 3306
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: prod
```

## 7. 监控与日志环境

### 7.1 日志配置
- 日志级别：INFO（生产环境）、DEBUG（开发环境）
- 日志输出格式：
  ```
  %d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n
  ```
- 日志存储：默认输出到控制台，生产环境建议配置文件输出

### 7.2 监控端点
- 健康检查：`http://localhost:8080/actuator/health`
- 性能指标：`http://localhost:8080/actuator/metrics`
- 应用信息：`http://localhost:8080/actuator/info`

## 8. 安全环境

### 8.1 网络安全
- 使用防火墙限制对网关端口的访问
- 建议在生产环境中启用TLS/SSL加密
- 定期更新第三方依赖库以修复安全漏洞

### 8.2 认证安全
- 密码在传输过程中不记录到日志
- 支持基于角色的访问控制（RBAC）
- 提供审计日志记录所有关键操作

### 8.3 数据安全
- 敏感信息（如密码）在内存中加密存储
- 支持数据传输加密（TLS/SSL）
- 提供SQL注入防护机制

## 9. 性能调优环境

### 9.1 JVM调优参数
```bash
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+UnlockExperimentalVMOptions
-XX:+UseStringDeduplication
```

### 9.2 连接池调优
根据实际负载调整连接池参数：
- 高并发场景：增加max-size值
- 内存受限环境：减少initial-size值
- 长连接场景：适当增加min-idle值

## 10. 故障排除环境

### 10.1 常见问题排查
- 端口冲突：检查端口占用情况
- 数据库连接失败：验证网络连通性和认证信息
- 内存不足：调整JVM堆内存参数

### 10.2 日志级别调整
临时提高日志级别以获取更多调试信息：
```yaml
logging:
  level:
    com.whosly.gateway: DEBUG
```