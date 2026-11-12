package org.pgledger.database.driver;

import java.util.List;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;

/**
 * The refusal of the embedded database H2 as the ledger database (user decision: 「dbms 설치가 전제라니까?」, 「h2는 초기 셋팅값이나
 * 뭐 이것저것 담아두는 용도로만 쓰고 dbms에 마이그레이션하는 목적으로만 써.」). The ledger is kept by a DBMS the operator
 * installs; H2 is only the file format of the seed bundle that the command line tool carries. A JDBC URL {@code jdbc:h2:...} or
 * a driver class of the H2 package is refused wherever a driver is chosen: when it is registered ({@link DriverRegistrar}, the
 * one check that the command line tool and the admin web share) and when the pool is made from a registration that was written
 * by hand ({@link DriverDataSources}).
 */
public final class EmbeddedDatabaseRefusal {

    /** The one reason: it names no file and no value, so it can be shown to a web client as it is. */
    public static final String REASON = "the ledger database must be a database system that the operator installs; the embedded "
            + "database H2 only holds the seed bundle of the command line tool and cannot be the ledger database";

    private static final String URL_PREFIX = "jdbc:h2:";
    private static final String CLASS_PREFIX = "org.h2.";

    private EmbeddedDatabaseRefusal() {
    }

    /**
     * @param jdbcUrl   the JDBC URL of the ledger database, or null when none is known yet
     * @param className the driver class, or null when none is known yet
     * @throws InfrastructureException DRIVER_INVALID with {@link #REASON} when either names H2
     */
    public static void require(String jdbcUrl, String className) {
        boolean urlIsH2 = jdbcUrl != null && jdbcUrl.strip().regionMatches(true, 0, URL_PREFIX, 0, URL_PREFIX.length());
        boolean classIsH2 = className != null && className.strip().startsWith(CLASS_PREFIX);
        if (urlIsH2 || classIsH2) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of(REASON));
        }
    }
}
