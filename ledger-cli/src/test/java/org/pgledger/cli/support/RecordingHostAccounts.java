package org.pgledger.cli.support;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.pgledger.cli.deploy.HostAccounts;

/**
 * Test double for the accounts of the host: a name that is given maps to the principal the test chose (only an administrator may
 * give a file to another account, so a test hands out its own account), every other name is looked up in the file system as the
 * product does. It records the names that were asked for.
 */
public final class RecordingHostAccounts implements HostAccounts {

    private final Map<String, UserPrincipal> users;
    private final Map<String, GroupPrincipal> groups;
    private final List<String> askedUsers = new ArrayList<>();
    private final List<String> askedGroups = new ArrayList<>();

    public RecordingHostAccounts(Map<String, UserPrincipal> users, Map<String, GroupPrincipal> groups) {
        this.users = users;
        this.groups = groups;
    }

    @Override
    public UserPrincipal user(FileSystem fileSystem, String name) throws IOException {
        askedUsers.add(name);
        UserPrincipal chosen = users.get(name);
        return chosen != null ? chosen : fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(name);
    }

    @Override
    public GroupPrincipal group(FileSystem fileSystem, String name) throws IOException {
        askedGroups.add(name);
        GroupPrincipal chosen = groups.get(name);
        return chosen != null ? chosen : fileSystem.getUserPrincipalLookupService().lookupPrincipalByGroupName(name);
    }

    public List<String> askedUsers() {
        return List.copyOf(askedUsers);
    }

    public List<String> askedGroups() {
        return List.copyOf(askedGroups);
    }
}
