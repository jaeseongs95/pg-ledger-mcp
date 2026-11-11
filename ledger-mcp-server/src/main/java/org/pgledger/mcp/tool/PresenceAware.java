package org.pgledger.mcp.tool;

import java.util.Set;

/**
 * An argument that has to know which of its keys the caller wrote, because writing a key with no value means
 * "clear it" while leaving the key out means "leave it". The binder hands over the keys after binding.
 */
public interface PresenceAware<T> {

    T withMentioned(Set<String> keys);
}
