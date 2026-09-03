package io.semanticmap.platform.operations;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class JsonLimits {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer boundedJson() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory()
                .setStreamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(100)
                        .maxStringLength(262144)
                        .maxNumberLength(1000)
                        .build()));
    }
}
