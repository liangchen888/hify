package com.hify.knowledge.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PostgreSQL / pgvector 独立数据源配置。
 * <p>
 * 属性前缀使用 "pgvector.datasource"（不在 "spring." 下），
 * 使 Spring Boot DataSourceAutoConfiguration 完全感知不到此数据源，
 * MyBatis-Plus 主数据源（MySQL）由 Spring Boot 自动配置正常管理。
 * 只暴露 pgJdbcTemplate，供 ChunkRepository 使用。
 */
@Configuration
public class PgVectorDataSourceConfig {

    @Bean("pgDataSource")
    @ConfigurationProperties(prefix = "pgvector.datasource")
    public DataSource pgDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean("pgJdbcTemplate")
    public JdbcTemplate pgJdbcTemplate(@Qualifier("pgDataSource") DataSource pgDataSource) {
        return new JdbcTemplate(pgDataSource);
    }
}
