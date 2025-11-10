package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.postgresql.PostgreSQLHandshake;
import com.whosly.gateway.adapter.postgresql.PostgreSQLPacket;
import com.whosly.gateway.adapter.postgresql.PostgreSQLResultSet;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.parser.DruidSqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.sql.*;

/**
 * PostgreSQL protocol adapter implementation.
 * This adapter handles PostgreSQL client connections and proxies them to backend databases.
 */
public class PostgreSQLProtocolAdapter extends AbstractProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLProtocolAdapter.class);
    private static final String PROTOCOL_NAME = "PostgreSQL";
    private static final int DEFAULT_PORT = 5432;
    
    public PostgreSQLProtocolAdapter() {
        super(PROTOCOL_NAME, DEFAULT_PORT);
    }
    
    @Override
    protected SqlParser createSqlParser() {
        return new DruidSqlParser();
    }
    
    @Override
    protected void handleClientConnection(Socket clientSocket) {
        Connection backendConnection = null;
        try {
            log.info("Handling new PostgreSQL client connection from {}", clientSocket.getRemoteSocketAddress());
            
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            
            // 连接到后端数据库
            String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                targetHost,
                targetPort,
                targetDatabase);
            
            log.info("Connecting to backend database: {} with user: {}", dbUrl, targetUsername);
            backendConnection = databaseConnectionService.connectToDatabase(
                dbUrl, 
                targetUsername, 
                targetPassword
            );
            log.info("Successfully connected to backend database: {}", dbUrl);
            
            // 读取客户端启动消息
            PostgreSQLHandshake.AuthInfo authInfo = readStartupMessage(clientIn, clientOut);
            if (authInfo == null) {
                log.warn("Failed to read startup message from client");
                return;
            }
            
            // 检查是否是SSL请求
            if (authInfo.isSslRequest()) {
                log.debug("SSL request received, switching to SSL mode");
                // 发送拒绝SSL的响应
                clientOut.write('N');
                clientOut.flush();
                
                // 重新读取启动消息
                authInfo = readStartupMessage(clientIn, clientOut);
                if (authInfo == null || authInfo.isSslRequest()) {
                    log.warn("Failed to read startup message after SSL rejection");
                    return;
                }
            }
            
            // 直接发送认证成功消息，跳过密码验证
            log.debug("Sending AuthenticationOk packet to client");
            byte[] authOkPacket = PostgreSQLPacket.createAuthenticationOkPacket();
            clientOut.write(authOkPacket);
            clientOut.flush();
            
            // Send parameter status packets
            sendParameterStatusPackets(clientOut);
            
            // Send backend key data packet
            byte[] backendKeyDataPacket = PostgreSQLPacket.createBackendKeyDataPacket(12345, 67890);
            clientOut.write(backendKeyDataPacket);
            clientOut.flush();
            
            // Send ReadyForQuery packet
            byte[] readyForQueryPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
            clientOut.write(readyForQueryPacket);
            clientOut.flush();
            
            log.debug("Authentication packets sent to client");
            
            // Proxy communication between client and backend
            log.info("Starting proxy communication");
            proxyConnection(clientSocket, clientIn, clientOut, backendConnection);
            log.info("Proxy communication ended");
        } catch (Exception e) {
            log.error("Error handling PostgreSQL client connection from {}", clientSocket.getRemoteSocketAddress(), e);
            try {
                // 尝试发送错误包给客户端
                try {
                    byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("FATAL", "08006", "Connection Error: " + e.getMessage());
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
     * 读取客户端启动消息
     */
    private PostgreSQLHandshake.AuthInfo readStartupMessage(InputStream clientIn, OutputStream clientOut) throws IOException {
        // Read message length
        byte[] lengthBytes = new byte[4];
        int bytesRead = clientIn.read(lengthBytes);
        if (bytesRead < 4) {
            log.warn("Failed to read message length, only got {} bytes", bytesRead);
            return null;
        }
        
        int messageLength = ((lengthBytes[0] & 0xFF) << 24) |
                           ((lengthBytes[1] & 0xFF) << 16) |
                           ((lengthBytes[2] & 0xFF) << 8) |
                           (lengthBytes[3] & 0xFF);
        
        log.debug("Received startup message with length: {}", messageLength);
        
        // Check for SSL request (special case: length = 8, content = 0x04d2162f)
        if (messageLength == 8) {
            byte[] sslBytes = new byte[4];
            bytesRead = clientIn.read(sslBytes);
            log.debug("Checking for SSL request: {} bytes read", bytesRead);
            if (bytesRead == 4 && 
                sslBytes[0] == 0x04 && sslBytes[1] == 0xd2 && 
                sslBytes[2] == 0x16 && sslBytes[3] == 0x2f) {
                PostgreSQLHandshake.AuthInfo authInfo = new PostgreSQLHandshake.AuthInfo();
                authInfo.setSslRequest(true);
                return authInfo;
            }
            // If it's not SSL request, continue with normal processing
        }
        
        // Read the rest of the message
        byte[] messageBytes = new byte[messageLength - 4];
        bytesRead = clientIn.read(messageBytes);
        if (bytesRead < messageLength - 4) {
            return null;
        }
        
        // Parse startup message
        return PostgreSQLHandshake.parseStartupMessage(messageBytes);
    }
    
    /**
     * 验证密码（简化实现）
     */
    private boolean validatePassword(String username, String password) {
        // 在实际实现中，这里应该查询数据库验证用户名和密码
        // 为简化起见，我们直接验证配置中的用户名和密码
        return username != null && 
               username.equals(targetUsername) && 
               password != null && 
               password.equals(targetPassword);
    }
    
    /**
     * Send parameter status packets to client
     */
    private void sendParameterStatusPackets(OutputStream clientOut) throws IOException {
        // Send server version
        byte[] versionPacket = PostgreSQLPacket.createParameterStatusPacket("server_version", "13.0");
        clientOut.write(versionPacket);
        clientOut.flush();
        
        // Send server encoding
        byte[] encodingPacket = PostgreSQLPacket.createParameterStatusPacket("server_encoding", "UTF8");
        clientOut.write(encodingPacket);
        clientOut.flush();
        
        // Send client encoding
        byte[] clientEncodingPacket = PostgreSQLPacket.createParameterStatusPacket("client_encoding", "UTF8");
        clientOut.write(clientEncodingPacket);
        clientOut.flush();
        
        // Send DateStyle
        byte[] dateStylePacket = PostgreSQLPacket.createParameterStatusPacket("DateStyle", "ISO, MDY");
        clientOut.write(dateStylePacket);
        clientOut.flush();
        
        // Send TimeZone
        byte[] timeZonePacket = PostgreSQLPacket.createParameterStatusPacket("TimeZone", "UTC");
        clientOut.write(timeZonePacket);
        clientOut.flush();
        
        // Send integer_datetimes
        byte[] integerDatetimesPacket = PostgreSQLPacket.createParameterStatusPacket("integer_datetimes", "on");
        clientOut.write(integerDatetimesPacket);
        clientOut.flush();
    }

    /**
     * Proxy communication between client and backend database.
     */
    private void proxyConnection(Socket clientSocket, InputStream clientIn, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            while (!clientSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                // 先读取消息类型（1字节）
                int messageTypeByte = clientIn.read();
                if (messageTypeByte == -1) {
                    // 客户端断开连接
                    log.debug("Client disconnected");
                    return;
                }
                
                char messageType = (char) messageTypeByte;
                
                // 读取消息长度（4字节）
                byte[] lengthBytes = new byte[4];
                int bytesRead = clientIn.read(lengthBytes);
                if (bytesRead != 4) {
                    log.warn("Failed to read message length, expected 4 bytes but got {}", bytesRead);
                    return;
                }
                
                int messageLength = ((lengthBytes[0] & 0xFF) << 24) |
                                   ((lengthBytes[1] & 0xFF) << 16) |
                                   ((lengthBytes[2] & 0xFF) << 8) |
                                   (lengthBytes[3] & 0xFF);
                
                // 读取消息内容（长度不包括类型字节，但包括长度字段本身）
                byte[] messageBytes = new byte[messageLength - 4];
                if (messageLength > 4) {
                    int totalRead = 0;
                    while (totalRead < messageBytes.length) {
                        int read = clientIn.read(messageBytes, totalRead, messageBytes.length - totalRead);
                        if (read == -1) {
                            log.warn("Unexpected end of stream while reading message");
                            return;
                        }
                        totalRead += read;
                    }
                }
                
                log.debug("Received message type: '{}' (0x{}), length: {}", 
                    messageType, Integer.toHexString(messageTypeByte), messageLength);
                
                // 根据消息类型处理
                switch (messageType) {
                    case 'Q': // Simple query
                        handleSimpleQuery(messageBytes, clientOut, backendConnection);
                        break;
                        
                    case 'P': // Parse
                        handleParse(messageBytes, clientOut, backendConnection);
                        break;
                        
                    case 'B': // Bind
                        handleBind(messageBytes, clientOut, backendConnection);
                        break;
                        
                    case 'E': // Execute
                        handleExecute(messageBytes, clientOut, backendConnection);
                        break;
                        
                    case 'D': // Describe
                        handleDescribe(messageBytes, clientOut, backendConnection);
                        break;
                        
                    case 'C': // Close
                        handleClose(messageBytes, clientOut, backendConnection);
                        break;
                        
                    case 'S': // Sync
                        handleSync(clientOut);
                        break;
                        
                    case 'X': // Terminate
                        log.info("Received Terminate message, closing connection");
                        return;
                        
                    default:
                        // 对于其他消息，返回一个错误响应
                        log.debug("Received unknown message type: '{}' (0x{})", messageType, Integer.toHexString(messageTypeByte));
                        byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "0A000", 
                            "Unsupported message type: " + messageType);
                        clientOut.write(errorPacket);
                        clientOut.flush();
                        
                        // 发送ReadyForQuery
                        byte[] readyPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
                        clientOut.write(readyPacket);
                        clientOut.flush();
                        break;
                }
            }
        } catch (IOException e) {
            // 客户端断开连接是正常情况，不需要记录错误日志
            if (!clientSocket.isClosed()) {
                log.debug("Client connection closed: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("Error processing client message", e);
            // 发送错误包给客户端
            try {
                byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                    "Server Error: " + e.getMessage());
                clientOut.write(errorPacket);
                clientOut.flush();
                
                // 发送ReadyForQuery
                byte[] readyPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
                clientOut.write(readyPacket);
                clientOut.flush();
            } catch (IOException ioException) {
                log.debug("Failed to send error packet to client: {}", ioException.getMessage());
            }
        }
    }
    
    /**
     * 处理简单查询消息
     */
    private void handleSimpleQuery(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 提取SQL查询（以null结尾）
            int queryLength = messageBytes.length;
            if (queryLength > 0 && messageBytes[queryLength - 1] == 0) {
                queryLength--; // 去掉null终止符
            }
            String sqlQuery = new String(messageBytes, 0, queryLength, "UTF-8").trim();
            log.info("Received SQL query: {}", sqlQuery);
            
            // 执行SQL查询
            Statement stmt = backendConnection.createStatement();
            boolean hasResultSet = stmt.execute(sqlQuery);
            
            if (hasResultSet) {
                // 有结果集，发送结果
                ResultSet rs = stmt.getResultSet();
                sendResultSet(rs, clientOut);
                rs.close();
            } else {
                // 没有结果集（UPDATE/INSERT/DELETE等）
                int updateCount = stmt.getUpdateCount();
                String commandTag;
                
                // 根据SQL类型确定命令标签
                String upperQuery = sqlQuery.toUpperCase().trim();
                if (upperQuery.startsWith("INSERT")) {
                    commandTag = "INSERT 0 " + updateCount;
                } else if (upperQuery.startsWith("UPDATE")) {
                    commandTag = "UPDATE " + updateCount;
                } else if (upperQuery.startsWith("DELETE")) {
                    commandTag = "DELETE " + updateCount;
                } else if (upperQuery.startsWith("CREATE")) {
                    commandTag = "CREATE TABLE";
                } else if (upperQuery.startsWith("DROP")) {
                    commandTag = "DROP TABLE";
                } else if (upperQuery.startsWith("ALTER")) {
                    commandTag = "ALTER TABLE";
                } else {
                    commandTag = "SELECT " + updateCount;
                }
                
                byte[] commandCompletePacket = PostgreSQLPacket.createCommandCompletePacket(commandTag);
                clientOut.write(commandCompletePacket);
                clientOut.flush();
            }
            
            stmt.close();
            
            // 发送ReadyForQuery包
            byte[] readyForQueryPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
            clientOut.write(readyForQueryPacket);
            clientOut.flush();
            
        } catch (SQLException e) {
            log.error("SQL error executing query", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "42000", 
                "SQL Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
            
            // 发送ReadyForQuery包
            byte[] readyForQueryPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
            clientOut.write(readyForQueryPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Simple Query message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
            
            // 发送ReadyForQuery包
            byte[] readyForQueryPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
            clientOut.write(readyForQueryPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 发送结果集到客户端
     */
    private void sendResultSet(ResultSet rs, OutputStream clientOut) throws SQLException, IOException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        
        // 发送RowDescription消息
        byte[] rowDescPacket = PostgreSQLResultSet.createRowDescriptionPacket(metaData, columnCount);
        clientOut.write(rowDescPacket);
        clientOut.flush();
        
        // 发送DataRow消息
        int rowCount = 0;
        while (rs.next()) {
            byte[] dataRowPacket = PostgreSQLResultSet.createDataRowPacket(rs, columnCount);
            clientOut.write(dataRowPacket);
            clientOut.flush();
            rowCount++;
        }
        
        // 发送CommandComplete消息
        byte[] commandCompletePacket = PostgreSQLPacket.createCommandCompletePacket("SELECT " + rowCount);
        clientOut.write(commandCompletePacket);
        clientOut.flush();
    }
    
    /**
     * 处理Describe消息
     */
    private void handleDescribe(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 发送NoData响应
            byte[] noDataPacket = PostgreSQLPacket.createPacket('n');
            clientOut.write(noDataPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Describe message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理Close消息
     */
    private void handleClose(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 发送CloseComplete响应
            byte[] closeCompletePacket = PostgreSQLPacket.createPacket('3');
            clientOut.write(closeCompletePacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Close message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理Parse消息
     */
    private void handleParse(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该处理Parse消息
            // 为简化起见，我们直接返回Parse完成响应
            byte[] parseCompletePacket = PostgreSQLPacket.createPacket('1');
            clientOut.write(parseCompletePacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Parse message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理Bind消息
     */
    private void handleBind(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该处理Bind消息
            // 为简化起见，我们直接返回Bind完成响应
            byte[] bindCompletePacket = PostgreSQLPacket.createPacket('2');
            clientOut.write(bindCompletePacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Bind message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理Execute消息
     */
    private void handleExecute(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该处理Execute消息
            // 为简化起见，我们直接返回命令完成包
            byte[] commandCompletePacket = PostgreSQLPacket.createCommandCompletePacket("SELECT 0");
            clientOut.write(commandCompletePacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Execute message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理Sync消息
     */
    private void handleSync(OutputStream clientOut) throws IOException {
        try {
            // 发送ReadyForQuery包
            byte[] readyForQueryPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
            clientOut.write(readyForQueryPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Sync message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
}