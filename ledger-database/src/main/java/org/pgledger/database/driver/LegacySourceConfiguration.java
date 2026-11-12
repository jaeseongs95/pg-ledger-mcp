package org.pgledger.database.driver;

import org.pgledger.database.repository.LegacySourceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The read side of the legacy import, from the settings {@code ledger.import.source.*}: it exists only when
 * {@code ledger.import.source.url} is set, so an application without those settings never opens a second connection, and
 * a command that needs the legacy ledger finds no {@link LegacySourceRepository} and says so. It needs the registered
 * driver of {@link LedgerDataSourceConfiguration}; the pool closes before the driver's class loader. The pool and the
 * repository are components that this configuration imports (and so only exists with the settings): the repository is
 * left out of the scan of the repository package for that reason.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ledger.import.source", name = "url")
@EnableConfigurationProperties(LegacySourceProperties.class)
@Import({LegacySourcePool.class, LegacySourceRepository.class})
public class LegacySourceConfiguration {
}
