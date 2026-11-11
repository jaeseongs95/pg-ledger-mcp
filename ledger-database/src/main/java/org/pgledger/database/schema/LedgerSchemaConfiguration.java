package org.pgledger.database.schema;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * The schema services as beans: the migrator (for the operator's CLI), the verifier and the UTC check (for the
 * applications). Nothing runs at startup here; see {@link LedgerSchemaGuardConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchemaProperties.class)
public class LedgerSchemaConfiguration {

    @Bean
    SchemaMigrator schemaMigrator(DataSource dataSource, ResourcePatternResolver resolver,
            SchemaProperties properties, Clock clock) {
        return new SchemaMigrator(dataSource, resolver, properties, clock);
    }

    @Bean
    SchemaVerifier schemaVerifier(DataSource dataSource, ResourcePatternResolver resolver,
            SchemaProperties properties) {
        return new SchemaVerifier(dataSource, resolver, properties);
    }

    @Bean
    UtcSessionCheck utcSessionCheck(DataSource dataSource, Clock clock, SchemaProperties properties) {
        return new UtcSessionCheck(dataSource, clock, properties.name());
    }
}
