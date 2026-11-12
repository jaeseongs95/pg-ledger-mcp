package org.pgledger.cli.deploy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.springframework.stereotype.Component;

/**
 * The one place where a {@link Platform} is turned into its {@link ServicePlatform}. The components are collected by type, so
 * a new platform is one enum constant and one component; one that has no component, or two components for one platform,
 * stops the start of the tool and names the platform (the way the command runner refuses a command without its component).
 */
@Component
final class ServicePlatforms {

    private final Map<Platform, ServicePlatform> platforms = new EnumMap<>(Platform.class);

    ServicePlatforms(List<ServicePlatform> available) {
        for (ServicePlatform platform : available) {
            if (platforms.putIfAbsent(platform.platform(), platform) != null) {
                throw new IllegalStateException("More than one ServicePlatform for " + platform.platform());
            }
        }
        for (Platform known : Platform.values()) {
            if (!platforms.containsKey(known)) {
                throw new IllegalStateException("No ServicePlatform for " + known);
            }
        }
    }

    ServicePlatform of(Platform platform) {
        return platforms.get(platform);
    }

    /** The platform that the value of {@code --platform} names, empty for any other text (the match is exact). */
    Optional<Platform> named(String option) {
        return platforms.keySet().stream().filter(platform -> Objects.equals(platform.option(), option)).findFirst();
    }
}
