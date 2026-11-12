package org.pgledger.cli.seed;

import java.util.List;
import java.util.Map;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.seed.SeedBundle;
import org.pgledger.seed.SeedCode;
import org.pgledger.seed.SeedUnavailableException;
import org.springframework.stereotype.Component;

/**
 * The seed bundle that is packed with this program ({@code ledger-seed.mv.db}, built from {@code src/seed}) as the commands see
 * it: the initial common codes and the defaults of an installation. It is read when a command first asks (a command that does
 * not need it never reads it), and a bundle that cannot be read is the refusal SEED_UNAVAILABLE. The bundle is H2 data and
 * nothing else: it is moved into the ledger database by {@code setup} and read for its defaults by the deployment commands.
 */
@Component
public class PackagedSeed {

    private final SeedBundle bundle;

    public PackagedSeed() {
        this(SeedBundle.packaged());
    }

    /** A seed that comes from another bundle (a test). */
    public PackagedSeed(SeedBundle bundle) {
        this.bundle = bundle;
    }

    /** The codes of the seed, in their sort order. */
    public List<SeedCode> codes() {
        try {
            return bundle.codes();
        } catch (SeedUnavailableException unavailable) {
            throw refused(unavailable);
        }
    }

    /** The digest of the codes of the seed. */
    public String digest() {
        try {
            return bundle.digest();
        } catch (SeedUnavailableException unavailable) {
            throw refused(unavailable);
        }
    }

    /** The defaults of the installation by name. */
    public Map<String, String> installDefaults() {
        try {
            return bundle.installDefaults();
        } catch (SeedUnavailableException unavailable) {
            throw refused(unavailable);
        }
    }

    private static FailureException refused(SeedUnavailableException unavailable) {
        return new FailureException(ErrorCode.SEED_UNAVAILABLE, unavailable, unavailable.getMessage());
    }
}
