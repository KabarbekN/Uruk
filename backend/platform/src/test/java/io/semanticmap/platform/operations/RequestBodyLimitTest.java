package io.semanticmap.platform.operations;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodyLimitTest {
    @Test
    void rejectsKnownOversizedContentBeforeParsing() throws Exception {
        var request = new MockHttpServletRequest();
        request.setContent(new byte[RequestBodyLimitFilter.MAX_BYTES + 1]);
        var response = new MockHttpServletResponse();
        new RequestBodyLimitFilter().doFilter(request, response, (req, res) -> {
            throw new AssertionError("Oversized request reached controller");
        });
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void boundsUnknownLengthStreamingBody() throws Exception {
        var request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[RequestBodyLimitFilter.MAX_BYTES + 1]);
        assertThatThrownBy(() -> new RequestBodyLimitFilter()
                        .doFilter(request, new MockHttpServletResponse(), (req, res) -> req.getInputStream()
                                .readAllBytes()))
                .isInstanceOf(IOException.class)
                .hasMessage("REQUEST_SIZE_LIMIT");
    }
}
