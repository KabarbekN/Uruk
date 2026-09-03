package io.semanticmap.platform.operations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RequestBodyLimitFilter extends OncePerRequestFilter {
    static final int MAX_BYTES = 2 * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.setStatus(413);
            response.setContentType("application/problem+json");
            response.getWriter()
                    .write(
                            "{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,\"detail\":\"Request exceeds 2 MiB\",\"errorCode\":\"REQUEST_SIZE_LIMIT\"}");
            return;
        }
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    private ServletInputStream input;

                    @Override
                    public ServletInputStream getInputStream() throws IOException {
                        if (input == null) input = new LimitedStream(super.getInputStream());
                        return input;
                    }
                },
                response);
    }

    static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream source;
        private long count;

        LimitedStream(ServletInputStream source) {
            this.source = source;
        }

        private void add(int amount) throws IOException {
            if (amount > 0 && (count += amount) > MAX_BYTES) throw new IOException("REQUEST_SIZE_LIMIT");
        }

        @Override
        public int read() throws IOException {
            int value = source.read();
            if (value != -1) add(1);
            return value;
        }

        @Override
        public int read(byte[] data, int offset, int length) throws IOException {
            int read = source.read(data, offset, (int) Math.min(length, MAX_BYTES - count + 1));
            add(read);
            return read;
        }

        @Override
        public boolean isFinished() {
            return source.isFinished();
        }

        @Override
        public boolean isReady() {
            return source.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            source.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
