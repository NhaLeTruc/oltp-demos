package com.oltp.demo.config;

import javax.sql.DataSource;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * jOOQ configuration for type-safe SQL query building.
 *
 * Provides DSLContext bean configured with:
 * - PostgreSQL dialect
 * - Spring-managed transactions
 * - Connection pooling via DataSource
 *
 * Used for performance demonstrations showing jOOQ's type-safe
 * query construction vs JPA.
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@Configuration
public class JooqConfig {

    /**
     * Creates jOOQ DSLContext bean for SQL query construction.
     *
     * @param dataSource HikariCP connection pool
     * @return Configured DSLContext
     */
    @Bean
    public DSLContext dslContext(DataSource dataSource) {
        // Wrap DataSource to participate in Spring transactions
        TransactionAwareDataSourceProxy proxy = new TransactionAwareDataSourceProxy(dataSource);

        // Configure jOOQ
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setConnectionProvider(new DataSourceConnectionProvider(proxy));

        return new DefaultDSLContext(configuration);
    }
}
