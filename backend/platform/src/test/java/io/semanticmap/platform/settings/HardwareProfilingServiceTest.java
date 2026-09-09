package io.semanticmap.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HardwareProfilingServiceTest {
    private HardwareProfilingService service;

    @BeforeEach
    void setup() {
        var aiConfig =
                new AiConfiguration("http://localhost:11434", "qwen2.5-coder:1.5b", "", "localhost,127.0.0.1", 100);
        service = new HardwareProfilingService(new ObjectMapper(), "http://localhost:11434", aiConfig);
    }

    @Test
    void detectsHostHardwareSpecs() {
        var profile = service.getProfile();

        assertThat(profile.osName()).isNotBlank();
        assertThat(profile.cpuCores()).isGreaterThan(0);
        assertThat(profile.catalog()).isNotEmpty();
        assertThat(profile.recommendedModelId()).isNotBlank();
        assertThat(profile.recommendationReason()).isNotBlank();
        assertThat(profile.activeModel()).isEqualTo("qwen2.5-coder:1.5b");
        assertThat(profile.isLocalActive()).isTrue();
    }

    @Test
    void catalogContainsRecommendedModelMatchingHardware() {
        var profile = service.getProfile();
        var catalog = profile.catalog();

        var recommended = catalog.stream()
                .filter(HardwareProfilingService.CatalogModel::isRecommended)
                .findFirst();
        assertThat(recommended).isPresent();
        assertThat(recommended.get().id()).isEqualTo(profile.recommendedModelId());
    }
}
