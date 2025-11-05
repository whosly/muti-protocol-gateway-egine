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
            // Get input/output streams for client
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            
            // Perform MySQL handshake
            performHandshake(clientIn, clientOut);
            
            // Authenticate with backend database
            MySQLHandshake.AuthInfo authInfo = authenticateClient(clientIn, clientOut);
            if (authInfo != null) {
                // For now, we'll prompt for database connection details
                // In a real implementation, we would use the authInfo to connect to a configured database
                String[] dbConnectionInfo = getDatabaseConnectionInfo(clientIn, clientOut);
                if (dbConnectionInfo != null) {
                    String dbUrl = dbConnectionInfo[0];
                    String username = dbConnectionInfo[1];
                    String password = dbConnectionInfo[2];
                    
                    // Connect to the backend database
                    backendConnection = databaseConnectionService.connectToDatabase(dbUrl, username, password);
                    log.info("Successfully connected to backend database: {}", dbUrl);
                    
                    // Send OK packet to client
                    byte[] okPacket = MySQLHandshake.createOkPacket(2);
                    clientOut.write(okPacket);
                    clientOut.flush();
                    
                    // Proxy communication between client and backend
                    proxyConnection(clientSocket, clientIn, clientOut, backendConnection);
                }
            }
        } catch (Exception e) {
            log.error("Error handling MySQL client connection", e);
            try {
                clientSocket.close();
            } catch (IOException ioException) {
                log.error("Error closing client socket", ioException);
            }
        } finally {
            if (backendConnection != null) {
                try {
                    backendConnection.close();
                } catch (SQLException e) {
                    log.error("Error closing backend connection", e);
                }
            }
        }
    }
    
    /**
     * Perform the initial MySQL protocol handshake.
     */
    private void performHandshake(InputStream clientIn, OutputStream clientOut) throws IOException {
        // Create and send server greeting packet
        byte[] handshakeData = MySQLHandshake.createHandshakePacket();
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
     * Get database connection information from client.
     * 
     * @return array with [dbUrl, username, password] or null if failed
     */
    private String[] getDatabaseConnectionInfo(InputStream clientIn, OutputStream clientOut) throws IOException {
        // For simplicity, we'll prompt the client for database connection details
        // In a real implementation, this would use the authentication information
        PrintWriter writer = new PrintWriter(clientOut, true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn));
        
        writer.println("Connected to Multi-Protocol Database Gateway");
        writer.println("Please provide database connection details:");
        writer.println("Database URL (e.g., jdbc:mysql://localhost:3306/mydb): ");
        
        String dbUrl = reader.readLine();
        if (dbUrl == null) return null;
        
        writer.println("Username: ");
        String username = reader.readLine();
        if (username == null) return null;
        
        writer.println("Password: ");
        String password = reader.readLine();
        if (password == null) return null;
        
        return new String[]{dbUrl, username, password};
    }
    
    /**
     * Proxy communication between client and backend database.
     */
    private void proxyConnection(Socket clientSocket, InputStream clientIn, OutputStream clientOut, Connection backendConnection) throws IOException {
        String inputLine;
        BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn));
        
        while ((inputLine = reader.readLine()) != null) {
            if ("quit".equalsIgnoreCase(inputLine)) {
                break;
            }
            
            try {
                // Execute SQL command on backend database
                Statement stmt = backendConnection.createStatement();
                boolean hasResultSet = stmt.execute(inputLine);
                
                if (hasResultSet) {
                    ResultSet rs = stmt.getResultSet();
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    
                    // Send column count packet
                    byte[] columnCountPacket = MySQLResultSet.createColumnCountPacket(metaData, columnCount, 1);
                    clientOut.write(columnCountPacket);
                    clientOut.flush();
                    
                    // Send column definition packets
                    for (int i = 1; i <= columnCount; i++) {
                        byte[] columnDefPacket = MySQLResultSet.createColumnDefinitionPacket(metaData, i, i);
                        clientOut.write(columnDefPacket);
                        clientOut.flush();
                    }
                    
                    // Send EOF packet after column definitions
                    byte[] eofPacket1 = MySQLResultSet.createEofPacket(columnCount + 1);
                    clientOut.write(eofPacket1);
                    clientOut.flush();
                    
                    // Send row data packets
                    int sequenceId = columnCount + 2;
                    while (rs.next()) {
                        byte[] rowDataPacket = MySQLResultSet.createRowDataPacket(rs, metaData, columnCount, sequenceId++);
                        clientOut.write(rowDataPacket);
                        clientOut.flush();
                    }
                    
                    // Send final EOF packet
                    byte[] eofPacket2 = MySQLResultSet.createEofPacket(sequenceId);
                    clientOut.write(eofPacket2);
                    clientOut.flush();
                    
                    rs.close();
                } else {
                    int updateCount = stmt.getUpdateCount();
                    // Send OK packet for update statements
                    byte[] okPacket = MySQLHandshake.createOkPacket(1);
                    clientOut.write(okPacket);
                    clientOut.flush();
                }
                
                stmt.close();
            } catch (SQLException e) {
                // Send error packet
                byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "SQL Error: " + e.getMessage(), 1);
                clientOut.write(errorPacket);
                clientOut.flush();
            }
        }
    }
}