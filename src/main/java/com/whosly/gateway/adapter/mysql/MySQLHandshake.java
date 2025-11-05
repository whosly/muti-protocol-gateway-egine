package com.whosly.gateway.adapter.mysql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * MySQL握手和认证处理类
 */
public class MySQLHandshake {
    private static final String SERVER_VERSION = "5.7.25";
    private static final int PROTOCOL_VERSION = 10;
    private static final int CONNECTION_ID = 1;
    private static final byte[] AUTH_PLUGIN_DATA_PART1 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    private static final byte[] AUTH_PLUGIN_DATA_PART2 = new byte[]{9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
    private static final String AUTH_PLUGIN_NAME = "mysql_native_password";
    
    // MySQL能力标志
    private static final int CLIENT_LONG_PASSWORD = 0x0001;
    private static final int CLIENT_FOUND_ROWS = 0x0002;
    private static final int CLIENT_LONG_FLAG = 0x0004;
    private static final int CLIENT_CONNECT_WITH_DB = 0x0008;
    private static final int CLIENT_NO_SCHEMA = 0x0010;
    private static final int CLIENT_COMPRESS = 0x0020;
    private static final int CLIENT_ODBC = 0x0040;
    private static final int CLIENT_LOCAL_FILES = 0x0080;
    private static final int CLIENT_IGNORE_SPACE = 0x0100;
    private static final int CLIENT_PROTOCOL_41 = 0x0200;
    private static final int CLIENT_INTERACTIVE = 0x0400;
    private static final int CLIENT_SSL = 0x0800;
    private static final int CLIENT_IGNORE_SIGPIPE = 0x1000;
    private static final int CLIENT_TRANSACTIONS = 0x2000;
    private static final int CLIENT_RESERVED = 0x4000;
    private static final int CLIENT_SECURE_CONNECTION = 0x8000;
    
    private static final int SERVER_STATUS_AUTOCOMMIT = 0x0002;
    
    /**
     * 创建服务器握手初始化包
     * 
     * @return 握手初始化包的载荷
     */
    public static byte[] createHandshakePacket() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        try {
            // 协议版本
            baos.write(PROTOCOL_VERSION);
            
            // 服务器版本
            baos.write(SERVER_VERSION.getBytes());
            baos.write(0); // 字符串结束符
            
            // 连接ID
            writeInt4(baos, CONNECTION_ID);
            
            // 认证插件数据第一部分
            baos.write(AUTH_PLUGIN_DATA_PART1);
            
            // 填充字节
            baos.write(0);
            
            // 能力标志低16位
            int capabilityFlags = CLIENT_LONG_PASSWORD | CLIENT_FOUND_ROWS | CLIENT_LONG_FLAG |
                                 CLIENT_CONNECT_WITH_DB | CLIENT_NO_SCHEMA | CLIENT_COMPRESS |
                                 CLIENT_ODBC | CLIENT_LOCAL_FILES | CLIENT_IGNORE_SPACE |
                                 CLIENT_PROTOCOL_41 | CLIENT_INTERACTIVE | CLIENT_SSL |
                                 CLIENT_IGNORE_SIGPIPE | CLIENT_TRANSACTIONS | CLIENT_RESERVED |
                                 CLIENT_SECURE_CONNECTION;
            writeInt2(baos, capabilityFlags & 0xFFFF);
            
            // 字符集
            baos.write(0x21); // utf8_general_ci
            
            // 服务器状态
            writeInt2(baos, SERVER_STATUS_AUTOCOMMIT);
            
            // 能力标志高16位
            writeInt2(baos, (capabilityFlags >> 16) & 0xFFFF);
            
            // 认证插件数据长度
            baos.write(21); // AUTH_PLUGIN_DATA_PART1.length + AUTH_PLUGIN_DATA_PART2.length + 1
            
            // 保留的10个字节
            for (int i = 0; i < 10; i++) {
                baos.write(0);
            }
            
            // 认证插件数据第二部分
            baos.write(AUTH_PLUGIN_DATA_PART2);
            baos.write(0); // 字符串结束符
            
            // 认证插件名称
            baos.write(AUTH_PLUGIN_NAME.getBytes());
            baos.write(0); // 字符串结束符
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to create handshake packet", e);
        }
        
        return baos.toByteArray();
    }
    
    /**
     * 解析客户端认证包
     * 
     * @param payload 认证包载荷
     * @return 认证信息
     */
    public static AuthInfo parseAuthPacket(byte[] payload) {
        // 这是一个简化的实现，实际的解析需要处理更多字段
        AuthInfo authInfo = new AuthInfo();
        
        try {
            int pos = 0;
            
            // 能力标志低16位 (4 bytes)
            int clientCapabilities = readInt4(payload, pos);
            pos += 4;
            
            // 最大包大小 (4 bytes)
            int maxPacketSize = readInt4(payload, pos);
            pos += 4;
            
            // 字符集 (1 byte)
            int charset = payload[pos] & 0xFF;
            pos += 1;
            
            // 跳过23个保留字节
            pos += 23;
            
            // 用户名 (以null结尾)
            int usernameStart = pos;
            while (pos < payload.length && payload[pos] != 0) {
                pos++;
            }
            String username = new String(payload, usernameStart, pos - usernameStart);
            pos++; // 跳过null终止符
            
            // 密码长度编码
            if (pos < payload.length) {
                long passwordLength = readLengthEncodedInteger(payload, pos);
                pos += getLengthEncodedIntegerSize(passwordLength);
                
                // 跳过密码字段
                pos += (int) passwordLength;
            }
            
            // 数据库名称 (以null结尾)
            if (pos < payload.length) {
                int dbnameStart = pos;
                while (pos < payload.length && payload[pos] != 0) {
                    pos++;
                }
                String database = new String(payload, dbnameStart, pos - dbnameStart);
                authInfo.setDatabase(database);
            }
            
            authInfo.setUsername(username);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse auth packet", e);
        }
        
        return authInfo;
    }
    
    /**
     * 创建认证结果包（成功）
     * 
     * @return 认证成功包
     */
    public static byte[] createOkPacket(int sequenceId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        try {
            // 字段数 (0表示OK包)
            baos.write(0);
            
            // 受影响的行数
            baos.write(0);
            
            // 最后插入的ID
            baos.write(0);
            
            // 服务器状态
            writeInt2(baos, SERVER_STATUS_AUTOCOMMIT);
            
            // 警告计数
            writeInt2(baos, 0);
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to create OK packet", e);
        }
        
        return MySQLPacket.createPacket(baos.toByteArray(), sequenceId);
    }
    
    /**
     * 创建错误包
     * 
     * @param errorCode 错误码
     * @param sqlState SQL状态
     * @param errorMessage 错误消息
     * @param sequenceId 序列号
     * @return 错误包
     */
    public static byte[] createErrorPacket(int errorCode, String sqlState, String errorMessage, int sequenceId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        try {
            // 字段数 (0xFF表示错误包)
            baos.write(0xFF);
            
            // 错误码
            writeInt2(baos, errorCode);
            
            // SQL状态标记
            baos.write('#');
            
            // SQL状态 (5字节)
            byte[] sqlStateBytes = sqlState.getBytes();
            baos.write(sqlStateBytes, 0, Math.min(5, sqlStateBytes.length));
            // 如果不足5字节，用空格填充
            for (int i = sqlStateBytes.length; i < 5; i++) {
                baos.write(' ');
            }
            
            // 错误消息
            baos.write(errorMessage.getBytes());
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to create error packet", e);
        }
        
        return MySQLPacket.createPacket(baos.toByteArray(), sequenceId);
    }
    
    /**
     * 计算密码哈希
     * 
     * @param password 密码
     * @param seed 种子
     * @return 密码哈希
     */
    public static byte[] scramble411(String password, byte[] seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            
            // stage1_hash = SHA1(password)
            byte[] passwordHash = md.digest(password.getBytes());
            
            // token = SHA1(seed + SHA1(stage1_hash))
            md.reset();
            md.update(seed);
            byte[] stage2Hash = md.digest(passwordHash);
            
            // XOR(passwordHash, token)
            byte[] result = new byte[passwordHash.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) (passwordHash[i] ^ stage2Hash[i]);
            }
            
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }
    }
    
    // 辅助方法
    
    private static void writeInt2(ByteArrayOutputStream baos, int value) throws IOException {
        baos.write(value & 0xFF);
        baos.write((value >> 8) & 0xFF);
    }
    
    private static void writeInt4(ByteArrayOutputStream baos, int value) throws IOException {
        baos.write(value & 0xFF);
        baos.write((value >> 8) & 0xFF);
        baos.write((value >> 16) & 0xFF);
        baos.write((value >> 24) & 0xFF);
    }
    
    private static int readInt4(byte[] data, int offset) {
        return (data[offset] & 0xFF) |
               ((data[offset + 1] & 0xFF) << 8) |
               ((data[offset + 2] & 0xFF) << 16) |
               ((data[offset + 3] & 0xFF) << 24);
    }
    
    private static long readLengthEncodedInteger(byte[] data, int offset) {
        int firstByte = data[offset] & 0xFF;
        if (firstByte < 251) {
            return firstByte;
        } else if (firstByte == 0xFC) {
            return (data[offset + 1] & 0xFF) | ((data[offset + 2] & 0xFF) << 8);
        } else if (firstByte == 0xFD) {
            return (data[offset + 1] & 0xFF) | 
                   ((data[offset + 2] & 0xFF) << 8) | 
                   ((data[offset + 3] & 0xFF) << 16);
        } else if (firstByte == 0xFE) {
            return ((long) (data[offset + 1] & 0xFF)) |
                   (((long) (data[offset + 2] & 0xFF)) << 8) |
                   (((long) (data[offset + 3] & 0xFF)) << 16) |
                   (((long) (data[offset + 4] & 0xFF)) << 24) |
                   (((long) (data[offset + 5] & 0xFF)) << 32) |
                   (((long) (data[offset + 6] & 0xFF)) << 40) |
                   (((long) (data[offset + 7] & 0xFF)) << 48) |
                   (((long) (data[offset + 8] & 0xFF)) << 56);
        }
        return 0;
    }
    
    private static int getLengthEncodedIntegerSize(long value) {
        if (value < 251) {
            return 1;
        } else if (value < 65536) {
            return 3;
        } else if (value < 16777216) {
            return 4;
        } else {
            return 9;
        }
    }
    
    /**
     * 认证信息类
     */
    public static class AuthInfo {
        private String username;
        private String database;
        
        public String getUsername() {
            return username;
        }
        
        public void setUsername(String username) {
            this.username = username;
        }
        
        public String getDatabase() {
            return database;
        }
        
        public void setDatabase(String database) {
            this.database = database;
        }
    }
}