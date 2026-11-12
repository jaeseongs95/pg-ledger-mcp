package org.pgledger.testing.database;

import java.util.Arrays;
import java.util.Map;
import org.pgledger.database.driver.LedgerDataSourceConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * Leaves the database of a deployment out of the component scan of a test: every scanned configuration that imports
 * {@link LedgerDataSourceConfiguration} (the DataSource of the registered driver, and with it the startup guard of the server
 * applications) is skipped, so that the test brings its own {@code DataSource} ({@link TestDataSourceConfiguration}: the
 * shared pool of the test JVM, in the schema of the context). This is the decision of the test, made from the test sources;
 * the product has no switch for it. Used as {@code @TypeExcludeFilters(TestDatabaseScanFilter.class)}, with
 * {@link TestDriverRegistration} so that the product's conditions still hold by their own path.
 */
public final class TestDatabaseScanFilter extends TypeExcludeFilter {

    private static final String IMPORT = Import.class.getName();
    private static final String DATA_SOURCE_CONFIGURATION = LedgerDataSourceConfiguration.class.getName();

    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
        Map<String, Object> imported = reader.getAnnotationMetadata().getAnnotationAttributes(IMPORT, true);
        return imported != null && imported.get("value") instanceof String[] classes
                && Arrays.asList(classes).contains(DATA_SOURCE_CONFIGURATION);
    }

    @Override
    public boolean equals(Object other) {
        return other != null && other.getClass() == getClass();
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
