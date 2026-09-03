package io.semanticmap.platform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundariesTest {
    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(PlatformApplication.class).verify();
    }
}
