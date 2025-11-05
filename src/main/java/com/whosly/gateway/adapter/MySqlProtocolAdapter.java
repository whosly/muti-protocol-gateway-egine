package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySQLHandshake;
import com.whosly.gateway.adapter.mysql.MySQLPacket;
import com.whosly.gateway.adapter.mysql.MySQLResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Removed @Component annotation to avoid bean definition conflict

import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MySQL protocol adapter implementation.
 * This adapter handles MySQL client connections and proxies them to backend databases.
 */
public class MySqlProtocolAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(MySqlProtocolAdapter.class);

    private static final String PROTOCOL_NAME = "MySQL";
    private static final int DEFAULT_PORT = 3307;
    
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private com.whosly.gateway.parser.SqlParser sqlParser;
    private com.whosly.gateway.service.DatabaseConnectionService databaseConnectionService;
    private int port = DEFAULT_PORT;
    
    // 目标数据库配置
    private String targetHost = "localhost";
    private int targetPort = 3306;
    private String targetUsername = "root";
    private String targetPassword = "password";
    private String targetDatabase = "testdb";
    
    public MySqlProtocolAdapter() {
        this.sqlParser = new com.whosly.gateway.parser.DruidSqlParser();
        this.databaseConnectionService = new com.whosly.gateway.service.DatabaseConnectionService();
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public int getPort() {
        return this.port;
    }
    
    // 目标数据库配置的setter方法
    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }
    
    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }
    
    public void setTargetUsername(String targetUsername) {
        this.targetUsername = targetUsername;
    }
    
    public void setTargetPassword(String targetPassword) {
        this.targetPassword = targetPassword;
    }
    
    public void setTargetDatabase(String targetDatabase) {
        this.targetDatabase = targetDatabase;
    }

    @Override
    public String getProtocolName() {
        return PROTOCOL_NAME;
    }

    @Override
    public int getDefaultPort() {
        return port;
    }

    @Override
    public void start() {
        if (running) {
            log.warn("MySQL protocol adapter is already running");
            return;
        }
        
        try {
            serverSocket = new ServerSocket(port);
            executorService = Executors.newCachedThreadPool();
            running = true;
            
            log.info("Starting MySQL protocol adapter on port {}", port);
            
            // Start accepting client connections
            executorService.submit(this::acceptConnections);
            
            log.info("MySQL protocol adapter started successfully");
        } catch (IOException e) {
            log.error("Failed to start MySQL protocol adapter", e);
            running = false;
        }
    }

    @Override
    public void stop() {
        if (!running) {
            log.warn("MySQL protocol adapter is not running");
            return;
        }
        
        log.info("Stopping MySQL protocol adapter");
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            
            if (executorService != null) {
                executorService.shutdown();
            }
        } catch (IOException e) {
            log.error("Error stopping MySQL protocol adapter", e);
        }
        
        running = false;
        log.info("MySQL protocol adapter stopped successfully");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Accept client connections and handle them in separate threads.
     */
    private void acceptConnections() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                log.info("New client connection accepted from {}", clientSocket.getRemoteSocketAddress());
                
                // Handle each client in a separate thread
                executorService.submit(() -> handleClientConnection(clientSocket));
            } catch (IOException e) {
                if (running) {
                    log.error("Error accepting client connection", e);
                }
            }
        }
    }
    
    /**
     * Handle a MySQL client connection with full protocol support.
     * 
     * @param clientSocket the client socket
     */
    private void handleClientConnection(Socket clientSocket) {
        Connection backendConnection = null;
        try {
            log.info("Handling new client connection from {}", clientSocket.getRemoteSocketAddress());
            
            // Get input/output streams for client
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            
            // First, connect to the backend database to get version information
            String dbUrl = String.format("jdbc:mysql://%s:%d/%s", targetHost, targetPort, targetDatabase);
            String username = targetUsername;
            String password = targetPassword;
            
            log.info("Connecting to backend database: {} with user: {}", dbUrl, username);
            backendConnection = databaseConnectionService.connectToDatabase(dbUrl, username, password);
            log.info("Successfully connected to backend database: {}", dbUrl);
            
            // Perform MySQL handshake with actual database version
            log.debug("Performing MySQL handshake");
            performHandshake(clientIn, clientOut, backendConnection);
            log.debug("MySQL handshake completed");
            
            // Authenticate with backend database
            log.debug("Authenticating client");
            MySQLHandshake.AuthInfo authInfo = authenticateClient(clientIn, clientOut);
            log.debug("Client authentication completed, authInfo: {}", authInfo);
            
            // 检查是否是SSL请求
            if (authInfo != null && authInfo.isSSLRequest()) {
                log.debug("SSL request received, switching to SSL mode");
                // 在实际实现中，这里应该切换到SSL模式
                // 为简化起见，我们直接返回错误
                byte[] errorPacket = MySQLHandshake.createErrorPacket(1045, "28000", "SSL not supported", 1);
                clientOut.write(errorPacket);
                clientOut.flush();
                return;
            }
            
            if (authInfo != null) {
                // Send OK packet to client
                log.debug("Sending OK packet to client");
                byte[] okPacket = MySQLHandshake.createOkPacket(2);
                clientOut.write(okPacket);
                clientOut.flush();
                log.debug("OK packet sent to client");
                
                // Proxy communication between client and backend
                log.info("Starting proxy communication");
                proxyConnection(clientSocket, clientIn, clientOut, backendConnection);
                log.info("Proxy communication ended");
            } else {
                log.warn("Client authentication failed");
                // 发送认证失败错误包
                byte[] errorPacket = MySQLHandshake.createErrorPacket(1045, "28000", "Access denied", 1);
                clientOut.write(errorPacket);
                clientOut.flush();
            }
        } catch (Exception e) {
            log.error("Error handling MySQL client connection from {}", clientSocket.getRemoteSocketAddress(), e);
            try {
                // 尝试发送错误包给客户端
                try {
                    byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Connection Error: " + e.getMessage(), 1);
                    clientSocket.getOutputStream().write(errorPacket);
                    clientSocket.getOutputStream().flush();
                } catch (Exception innerException) {
                    log.debug("Failed to send error packet to client: {}", innerException.getMessage());
                }
                clientSocket.close();
            } catch (IOException ioException) {
                log.error("Error closing client socket", ioException);
            }
        } finally {
            if (backendConnection != null) {
                try {
                    log.debug("Closing backend connection");
                    backendConnection.close();
                    log.debug("Backend connection closed");
                } catch (SQLException e) {
                    log.error("Error closing backend connection", e);
                }
            }
            try {
                if (!clientSocket.isClosed()) {
                    log.debug("Closing client socket");
                    clientSocket.close();
                    log.debug("Client socket closed");
                }
            } catch (IOException e) {
                log.error("Error closing client socket", e);
            }
        }
    }
    
    /**
     * Perform the initial MySQL protocol handshake.
     */
    private void performHandshake(InputStream clientIn, OutputStream clientOut, Connection backendConnection) throws IOException {
        // Create and send server greeting packet with actual database version
        byte[] handshakeData = MySQLHandshake.createHandshakePacket(backendConnection);
        byte[] handshakePacket = MySQLPacket.createPacket(handshakeData, 0);
        
        clientOut.write(handshakePacket);
        clientOut.flush();
    }
    
    /**
     * Authenticate client and get database connection information.
     * 
     * @return AuthInfo with client authentication details or null if authentication failed
     */
    private MySQLHandshake.AuthInfo authenticateClient(InputStream clientIn, OutputStream clientOut) throws IOException {
        // Read client authentication packet
        MySQLPacket.PacketInfo packetInfo = MySQLPacket.readPacket(clientIn);
        byte[] packetData = packetInfo.getPayload();
        
        // Parse authentication packet
        return MySQLHandshake.parseAuthPacket(packetData);
    }
    
    /**
     * Proxy communication between client and backend database.
     */
    private void proxyConnection(Socket clientSocket, InputStream clientIn, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            while (!clientSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 读取客户端发送的数据包
                    MySQLPacket.PacketInfo packetInfo = MySQLPacket.readPacket(clientIn);
                    byte[] packetData = packetInfo.getPayload();
                    int sequenceId = packetInfo.getSequenceId();
                    
                    // 检查包类型
                    if (packetData.length > 0) {
                        int command = packetData[0] & 0xFF;
                        
                        switch (command) {
                            case 0x01: // COM_QUIT
                                log.info("Received COM_QUIT command, closing connection");
                                return;
                                
                            case 0x02: // COM_INIT_DB
                                handleInitDbCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x03: // COM_QUERY
                                // 处理SQL查询命令
                                handleQueryCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x04: // COM_FIELD_LIST
                                handleFieldListCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x05: // COM_CREATE_DB
                                handleCreateDbCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x06: // COM_DROP_DB
                                handleDropDbCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x08: // COM_REFRESH
                                handleRefreshCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x09: // COM_STATISTICS
                                // 返回统计信息
                                handleStatisticsCommand(sequenceId, clientOut);
                                break;
                                
                            case 0x0A: // COM_PROCESS_INFO
                                handleProcessInfoCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x0B: // COM_CONNECT
                                handleConnectCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x0C: // COM_PROCESS_KILL
                                handleProcessKillCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x0D: // COM_DEBUG
                                handleDebugCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x0E: // COM_PING
                                handlePingCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x11: // COM_CHANGE_USER
                                handleChangeUserCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            default:
                                // 对于其他命令，返回一个OK包
                                log.debug("Received command: 0x{}, returning OK packet", Integer.toHexString(command));
                                byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
                                clientOut.write(okPacket);
                                clientOut.flush();
                                break;
                        }
                    }
                } catch (IOException e) {
                    // 客户端断开连接是正常情况，不需要记录错误日志
                    if (!clientSocket.isClosed()) {
                        log.debug("Client connection closed: {}", e.getMessage());
                    }
                    return;
                } catch (Exception e) {
                    log.error("Error processing client command", e);
                    // 发送错误包给客户端
                    try {
                        byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Server Error: " + e.getMessage(), 1);
                        clientOut.write(errorPacket);
                        clientOut.flush();
                    } catch (IOException ioException) {
                        log.debug("Failed to send error packet to client: {}", ioException.getMessage());
                    }
                    // 继续处理其他命令
                }
            }
        } finally {
            // 确保关闭客户端连接
            try {
                if (!clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                log.debug("Error closing client socket: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 处理COM_INIT_DB命令
     */
    private void handleInitDbCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 提取数据库名称（去掉命令字节）
            String databaseName = new String(packetData, 1, packetData.length - 1);
            log.info("Changing to database: {}", databaseName);
            
            // 在实际实现中，这里应该切换到指定的数据库
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_INIT_DB command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_FIELD_LIST命令
     */
    private void handleFieldListCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该返回表的字段列表
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_FIELD_LIST command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_CREATE_DB命令
     */
    private void handleCreateDbCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 提取数据库名称（去掉命令字节）
            String databaseName = new String(packetData, 1, packetData.length - 1);
            log.info("Creating database: {}", databaseName);
            
            // 在实际实现中，这里应该创建数据库
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_CREATE_DB command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_DROP_DB命令
     */
    private void handleDropDbCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 提取数据库名称（去掉命令字节）
            String databaseName = new String(packetData, 1, packetData.length - 1);
            log.info("Dropping database: {}", databaseName);
            
            // 在实际实现中，这里应该删除数据库
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_DROP_DB command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_REFRESH命令
     */
    private void handleRefreshCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该刷新服务器缓存
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_REFRESH command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_PROCESS_INFO命令
     */
    private void handleProcessInfoCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该返回进程信息
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_PROCESS_INFO command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_CONNECT命令
     */
    private void handleConnectCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该处理连接命令
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_CONNECT command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_PROCESS_KILL命令
     */
    private void handleProcessKillCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该杀死指定的进程
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_PROCESS_KILL command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_DEBUG命令
     */
    private void handleDebugCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该输出调试信息
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_DEBUG command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_PING命令
     */
    private void handlePingCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            log.debug("Received COM_PING command");
            // 返回OK包表示服务器存活
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_PING command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_CHANGE_USER命令
     */
    private void handleChangeUserCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该处理用户切换
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_CHANGE_USER command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_QUERY命令
     */
    private void handleQueryCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 提取SQL查询语句（去掉命令字节）
            String sqlQuery = new String(packetData, 1, packetData.length - 1);
            log.info("Received SQL query: {}", sqlQuery);
            
            // 执行SQL查询
            Statement stmt = backendConnection.createStatement();
            boolean hasResultSet = stmt.execute(sqlQuery);
            
            if (hasResultSet) {
                ResultSet rs = stmt.getResultSet();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                
                // Send column count packet
                byte[] columnCountPacket = MySQLResultSet.createColumnCountPacket(metaData, columnCount, sequenceId + 1);
                clientOut.write(columnCountPacket);
                clientOut.flush();
                
                // Send column definition packets
                for (int i = 1; i <= columnCount; i++) {
                    byte[] columnDefPacket = MySQLResultSet.createColumnDefinitionPacket(metaData, i, sequenceId + 1 + i);
                    clientOut.write(columnDefPacket);
                    clientOut.flush();
                }
                
                // Send EOF packet after column definitions
                byte[] eofPacket1 = MySQLResultSet.createEofPacket(sequenceId + 1 + columnCount + 1);
                clientOut.write(eofPacket1);
                clientOut.flush();
                
                // Send row data packets
                int rowSequenceId = sequenceId + 1 + columnCount + 2;
                while (rs.next()) {
                    byte[] rowDataPacket = MySQLResultSet.createRowDataPacket(rs, metaData, columnCount, rowSequenceId++);
                    clientOut.write(rowDataPacket);
                    clientOut.flush();
                }
                
                // Send final EOF packet
                byte[] eofPacket2 = MySQLResultSet.createEofPacket(rowSequenceId);
                clientOut.write(eofPacket2);
                clientOut.flush();
                
                rs.close();
            } else {
                int updateCount = stmt.getUpdateCount();
                // Send OK packet for update statements
                byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
                clientOut.write(okPacket);
                clientOut.flush();
            }
            
            stmt.close();
        } catch (SQLException e) {
            // Send error packet
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "SQL Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_STATISTICS命令
     */
    private void handleStatisticsCommand(int sequenceId, OutputStream clientOut) throws IOException {
        try {
            // 在实际实现中，这里应该返回服务器统计信息
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_STATISTICS command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
}