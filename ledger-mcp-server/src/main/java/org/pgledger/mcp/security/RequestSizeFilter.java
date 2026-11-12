package org.pgledger.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.pgledger.mcp.config.LedgerMcpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Refuses a request whose body is larger than the configured size with 413, before the handler reads it: by the
 * declared {@code Content-Length}, or, for a body that is sent in chunks and declares no length, by reading at most
 * the limit plus one byte. A body within the limit is handed on from memory (at most the limit of one request);
 * nothing beyond the limit is read.
 *
 * <p>Why this is a filter of ours (principle 2.1): the connector settings do not apply. The Tomcat 11 HTTP connector
 * documentation (https://tomcat.apache.org/tomcat-11.0-doc/config/http.html) says {@code maxPostSize} is the limit
 * of a form POST that the container parses and {@code maxSwallowSize} the number of bytes it swallows of an aborted
 * upload; neither limits a JSON body that the handler reads itself. Spring's {@code ContentCachingRequestWrapper}
 * caches but does not limit what the handler reads. The refusal is not written here (principle 3.8): the filter only
 * raises {@link RequestTooLargeException}, which goes to the {@link HandlerExceptionResolver} of the dispatcher, which
 * hands it to the one HTTP error advice that builds the problem detail.
 *
 * <p>The filter is a component; {@code McpWebConfiguration} registers it (URL pattern and order).
 */
@Component
public final class RequestSizeFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final HandlerExceptionResolver errors;

    public RequestSizeFilter(LedgerMcpProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.maxBytes = properties.maxRequestSize().toBytes();
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            refuse(request, response);
            return;
        }
        if (declared < 0 && request.getHeader("Transfer-Encoding") != null) {
            byte[] body = request.getInputStream().readNBytes(Math.toIntExact(maxBytes + 1));
            if (body.length > maxBytes) {
                refuse(request, response);
                return;
            }
            chain.doFilter(new BufferedBodyRequest(request, body), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) {
        errors.resolveException(request, response, null, new RequestTooLargeException());
    }

    /** The request with the body that was read: the handler reads it again from memory. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {

                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return source.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("the body is read from memory, not asynchronously");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String name = getCharacterEncoding();
            Charset charset = name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
