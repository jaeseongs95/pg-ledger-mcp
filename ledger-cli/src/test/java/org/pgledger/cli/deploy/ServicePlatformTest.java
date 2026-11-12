package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.pgledger.cli.secret.ProcessUser;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * CLI-PRN-05 (plan 2.3): every {@code Platform} has exactly one {@code ServicePlatform} component, chosen by the enum in one
 * place ({@code ServicePlatforms}); a context that lacks one, or has two for one platform, does not start and names the
 * platform. The context is made with an {@code ApplicationContextRunner} over the real components.
 */
class ServicePlatformTest {

    /** A platform that does nothing: what a context has when somebody adds a second component for a platform. */
    private static final class Extra implements ServicePlatform {

        private final Platform platform;

        Extra(Platform platform) {
            this.platform = platform;
        }

        @Override
        public Platform platform() {
            return platform;
        }

        @Override
        public DeployLayout layout(String name, String home) {
            return DeployLayout.linux(name);
        }

        @Override
        public List<RenderedFile> serviceDefinitions(DeployParameters parameters) {
            return List.of();
        }

        @Override
        public List<Path> wrappers(ApplicationArguments arguments) {
            return List.of();
        }

        @Override
        public void prepare(DeployLayout layout, Path root, Path jars, Path driver, List<Path> wrappers) throws IOException {
        }

        @Override
        public void seal(DeployLayout layout, Path root) throws IOException {
        }
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withBean(ProcessUser.class, () -> fileSystem -> () -> "tester")
                .withUserConfiguration(ServicePlatforms.class, DeployRenderer.class, DeployFiles.class, HostFiles.class,
                        WindowsAccess.class, SystemHostAccounts.class);
    }

    @Test
    @DisplayName("CLI-PRN-05 with both platform components the context starts and every Platform finds its own, by the enum in one place; --platform names each by its option")
    void everyPlatformHasItsComponent() {
        runner().withUserConfiguration(LinuxPlatform.class, WindowsPlatform.class).run(context -> {
            assertThat(context).hasNotFailed();
            ServicePlatforms platforms = context.getBean(ServicePlatforms.class);
            for (Platform platform : Platform.values()) {
                assertThat(platforms.of(platform).platform()).isEqualTo(platform);
                assertThat(platforms.named(platform.option())).contains(platform);
            }
            assertThat(platforms.of(Platform.LINUX)).isInstanceOf(LinuxPlatform.class);
            assertThat(platforms.of(Platform.WINDOWS)).isInstanceOf(WindowsPlatform.class);
            assertThat(platforms.named("Linux")).as("the option is matched exactly").isEmpty();
            assertThat(platforms.named("macos")).isEmpty();
            assertThat(context.getBean(DeployRenderer.class)).isNotNull();
        });
    }

    @Test
    @DisplayName("CLI-PRN-05 a platform without a component stops the start and names the platform")
    void aMissingPlatformStopsTheStart() {
        runner().withUserConfiguration(LinuxPlatform.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause().hasMessage("No ServicePlatform for WINDOWS");
        });
    }

    @Test
    @DisplayName("CLI-PRN-05 two components for one platform stop the start and name the platform")
    void aDoublePlatformStopsTheStart() {
        runner().withUserConfiguration(LinuxPlatform.class, WindowsPlatform.class)
                .withBean("secondLinux", ServicePlatform.class, () -> new Extra(Platform.LINUX)).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                            .hasMessage("More than one ServicePlatform for LINUX");
                });
    }
}
