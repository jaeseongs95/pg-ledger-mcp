package org.pgledger.mcp.security;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one handler of HTTP errors of the server: the handler of Spring renders the standard exceptions (and the
 * {@code ErrorResponseException} of the size check) as problem details. Tool failures are not HTTP errors; they
 * are tool results (see {@code ToolErrorMapper}).
 */
@RestControllerAdvice
public class McpHttpExceptionAdvice extends ResponseEntityExceptionHandler {
}
