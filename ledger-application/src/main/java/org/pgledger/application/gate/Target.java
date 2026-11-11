package org.pgledger.application.gate;

import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.ProjectId;

/** One project and the scope a write needs on it. */
public record Target(ProjectId project, Scope scope) {
}
