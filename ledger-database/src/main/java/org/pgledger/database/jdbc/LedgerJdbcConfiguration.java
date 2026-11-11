package org.pgledger.database.jdbc;

import java.util.List;
import java.util.Optional;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.domain.context.ActorScope;
import org.pgledger.domain.value.ActorId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.relational.RelationalManagedTypes;
import org.springframework.data.relational.core.mapping.NamingStrategy;

/**
 * Spring Data JDBC for the ledger: unquoted lower-case identifiers (so every DBMS folds them its own way), the
 * converters of {@link LedgerConverters}, and auditing of {@code created_by} from the actor of the request.
 */
@Configuration(proxyBeanMethods = false)
@EnableJdbcRepositories(basePackageClasses = DefectRepository.class)
@EnableJdbcAuditing(auditorAwareRef = "ledgerAuditorAware")
@ComponentScan(basePackageClasses = DefectRepository.class)
public class LedgerJdbcConfiguration extends AbstractJdbcConfiguration {

    /**
     * Our DDL uses lower-case unquoted identifiers. Spring Data JDBC quotes identifiers by default, which breaks
     * that on a DBMS that folds to upper case.
     */
    @Override
    @Bean
    public JdbcMappingContext jdbcMappingContext(Optional<NamingStrategy> namingStrategy,
            JdbcCustomConversions customConversions, RelationalManagedTypes jdbcManagedTypes) {
        JdbcMappingContext context = super.jdbcMappingContext(namingStrategy, customConversions, jdbcManagedTypes);
        context.setForceQuote(false);
        return context;
    }

    @Override
    protected List<?> userConverters() {
        return LedgerConverters.all();
    }

    /** The actor bound by {@link ActorScope}; a write without an actor fails loudly, there is no default author. */
    @Bean
    AuditorAware<ActorId> ledgerAuditorAware() {
        return () -> {
            if (!ActorScope.CURRENT.isBound()) {
                throw new IllegalStateException("no actor is bound: writes run inside an ActorScope");
            }
            return Optional.of(ActorScope.CURRENT.get());
        };
    }
}
