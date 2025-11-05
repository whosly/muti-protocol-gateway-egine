package com.whosly.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * Main application class for the Multi-Protocol Database Gateway Engine.
 * 
 * This gateway engine provides unified access to various database systems
 * through different protocols, using Alibaba Druid for SQL parsing.
 */
@SpringBootApplication
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    @Autowired
    private com.whosly.gateway.adapter.ProtocolAdapter mySqlProtocolAdapter;
    
    @Value("${gateway.protocols.mysql.enabled:true}")
    private boolean mysqlEnabled;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        log.info("Multi-Protocol Database Gateway Engine started successfully");
    }
    
    @EventListener
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // Start the MySQL protocol adapter when the application context is refreshed
        if (mysqlEnabled && !mySqlProtocolAdapter.isRunning()) {
            log.info("Starting MySQL protocol adapter...");
            mySqlProtocolAdapter.start();
        } else if (!mysqlEnabled) {
            log.info("MySQL protocol adapter is disabled in configuration");
        }
    }
}