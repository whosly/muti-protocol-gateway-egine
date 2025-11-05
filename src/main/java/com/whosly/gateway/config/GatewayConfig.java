package com.whosly.gateway.config;

import com.whosly.gateway.adapter.MySqlProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Value("${gateway.protocols.mysql.port:3306}")
    private int mysqlPort;

    @Bean
    public SqlParser sqlParser() {
        return new DruidSqlParser();
    }
    
    @Bean
    public ProtocolAdapter mySqlProtocolAdapter() {
        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        adapter.setPort(mysqlPort);
        return adapter;
    }
}