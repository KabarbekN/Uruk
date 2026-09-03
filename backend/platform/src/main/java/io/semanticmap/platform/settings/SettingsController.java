package io.semanticmap.platform.settings;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public Map<String, Object> get() {
        return settings.get();
    }

    @PutMapping
    public Map<String, Object> update(@Valid @RequestBody SettingsService.Update request) {
        return settings.update(request);
    }
}
