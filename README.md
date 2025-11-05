# Multi-Protocol Database Gateway Engine

This is a proxy gateway engine that implements multiple database protocols. It uses Alibaba Druid for SQL parsing and is developed in Java 17.

## Overview

The Multi-Protocol Database Gateway Engine provides unified access to various database systems through different protocols. It acts as an intermediary layer that translates client requests from various protocols into native database queries and routes them to the appropriate backend databases.

## Key Features

- Full MySQL protocol implementation for client connections
- SQL parsing and translation using Alibaba Druid
- Database connection proxy based on user-provided authentication
- Support for multiple database backends (MySQL, PostgreSQL, Oracle, SQL Server)
- Security controls and monitoring capabilities
- Connection pooling and load balancing

## Documentation

- [README.md](README.md) - This file, containing quick start information
- [COMPREHENSIVE_DOCUMENTATION.md](COMPREHENSIVE_DOCUMENTATION.md) - Complete project documentation

## Quick Start

### Prerequisites

- Java 17 (configured at C:\Users\parker\.jdks\azul-17.0.14)
- Maven 3.6+ (configured at D:\devlops\apache-maven-3.9.9)
- Target database systems (MySQL, PostgreSQL, etc.)

### Building and Running

```bash
# Build the project
D:\devlops\apache-maven-3.9.9\bin\mvn clean package

# Run the application
java -jar target/muti-protocol-gateway-egine-1.0.0-SNAPSHOT.jar
```

Or use the provided batch script:
```bash
start-gateway.bat
```

## Usage

### Connecting to the Gateway

1. Configure your MySQL client to connect to localhost:3306
2. When prompted, provide the target database connection details:
   - Database URL (e.g., jdbc:mysql://target-db-server:3306/database)
   - Username
   - Password
3. The gateway will proxy your connection to the target database

### Example Usage

```bash
# From a MySQL client
mysql -h localhost -P 3306 -u anyuser -p

# The gateway will prompt for real database credentials
Connected to Multi-Protocol Database Gateway
Please provide database connection details:
Database URL (e.g., jdbc:mysql://target-db-server:3306/database): jdbc:mysql://localhost:3306/myapp
Username: app_user
Password: ********

# If credentials are valid
Connection to database established. You can now send SQL commands.
```

## MySQL Protocol Implementation

The gateway implements a substantial portion of the MySQL client-server protocol, including:

### Handshake Phase
- Server greeting packet with protocol version, server version, and connection ID
- Authentication packet parsing with capability flags and character set support
- Secure password scrambling using MySQL 4.1+ authentication method

### Command Phase
- SQL statement execution with result set handling
- Column definition packets for metadata transmission
- Row data packets for result transmission
- OK and Error packet responses
- EOF packet signaling

### Supported Features
- Text protocol result sets
- Basic data type mapping between MySQL and JDBC
- Connection state management
- Graceful connection termination

### Limitations
- Prepared statements are not yet implemented
- SSL/TLS encryption is not yet supported
- Advanced MySQL protocol features like compression are not implemented
- Only basic authentication is supported

## Project Structure

```
src/
├── main/
│   ├── java/
│   │   └── com.whosly.gateway/
│   │       ├── adapter/           # Protocol adapters for different database protocols
│   │       │   ├── mysql/         # MySQL protocol specific classes
│   │       │   │   ├── MySQLPacket.java       # MySQL packet handling
│   │       │   │   ├── MySQLHandshake.java    # MySQL handshake and authentication
│   │       │   │   └── MySQLResultSet.java    # MySQL result set handling
│   │       │   ├── MySqlProtocolAdapter.java  # MySQL protocol implementation
│   │       │   └── ProtocolAdapter.java       # Protocol adapter interface
│   │       ├── parser/            # SQL parsing functionality using Alibaba Druid
│   │       │   ├── DruidSqlParser.java        # Druid-based SQL parser
│   │       │   ├── SqlDialect.java            # SQL dialect enum
│   │       │   ├── SqlParseException.java     # SQL parsing exception
│   │       │   └── SqlParser.java             # SQL parser interface
│   │       ├── service/           # Database connection services
│   │       │   └── DatabaseConnectionService.java # Database connection management
│   │       ├── config/            # Configuration classes
│   │       │   └── GatewayConfig.java         # Spring configuration
│   │       ├── controller/        # REST controllers
│   │       │   └── GatewayController.java     # Gateway REST controller
│   │       ├── Application.java   # Main Spring Boot application class
│   │       ├── ParserDemo.java    # Simple demo class for testing SQL parser
│   │       └── CommandLineInterface.java # CLI for gateway control
│   └── resources/
│       └── application.yml        # Configuration file
└── test/
    └── java/
        └── com.whosly.gateway/
            ├── adapter/
            │   └── MySqlProtocolAdapterTest.java  # Unit tests for MySQL protocol adapter
            └── parser/
                └── DruidSqlParserTest.java  # Unit tests for SQL parser
```

## Testing

Unit tests can be run with:

```bash
D:\devlops\apache-maven-3.9.9\bin\mvn test
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For support, please open an issue in the repository.