package org.pgledger.cli.support;

import java.nio.file.FileSystems;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/** Evaluates {@link RequiresPosixPermissions} and {@link RequiresNonPosixFileSystem} from the file system capability. */
final class PosixCapabilityCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        var element = context.getElement();
        if (element.isEmpty()) {
            return ConditionEvaluationResult.enabled("no annotated element");
        }
        if (AnnotationSupport.isAnnotated(element.get(), RequiresPosixPermissions.class) && !posix) {
            return ConditionEvaluationResult.disabled("the default file system has no POSIX permissions");
        }
        boolean acl = FileSystems.getDefault().supportedFileAttributeViews().contains("acl");
        if (AnnotationSupport.isAnnotated(element.get(), RequiresOwnerOnlyStorage.class) && !posix && !acl) {
            return ConditionEvaluationResult.disabled("the default file system has neither POSIX permissions nor ACLs");
        }
        if (AnnotationSupport.isAnnotated(element.get(), RequiresNonPosixFileSystem.class) && posix) {
            return ConditionEvaluationResult.disabled("the default file system supports POSIX permissions");
        }
        return ConditionEvaluationResult.enabled("file system capability matches");
    }
}
