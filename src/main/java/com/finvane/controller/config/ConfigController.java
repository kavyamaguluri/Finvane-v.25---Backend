package com.finvane.controller.config;

import com.finvane.service.config.DynamicConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final DynamicConfigService dynamicConfigService;

    public ConfigController(DynamicConfigService dynamicConfigService) {
        this.dynamicConfigService = dynamicConfigService;
    }

    @GetMapping("/menus")
    public ResponseEntity<?> getMenus() {
        return ResponseEntity.ok(Map.of("ok", true, "items", dynamicConfigService.getActiveMenuItems()));
    }

    @GetMapping("/states")
    public ResponseEntity<?> getStates() {
        return ResponseEntity.ok(Map.of("ok", true, "states", dynamicConfigService.getActiveStates()));
    }

    @GetMapping("/lookups")
    public ResponseEntity<?> getLookups(@RequestParam(name = "category") String category) {
        return ResponseEntity.ok(Map.of("ok", true, "category", category, "lookups", dynamicConfigService.getLookupsByCategory(category)));
    }

    @GetMapping("/gst-rates")
    public ResponseEntity<?> getGstRates() {
        return ResponseEntity.ok(Map.of("ok", true, "rates", dynamicConfigService.getActiveGstRates()));
    }

    @GetMapping("/tds-sections")
    public ResponseEntity<?> getTdsSections() {
        return ResponseEntity.ok(Map.of("ok", true, "sections", dynamicConfigService.getActiveTdsSections()));
    }

    @GetMapping("/services")
    public ResponseEntity<?> getServices() {
        return ResponseEntity.ok(Map.of("ok", true, "services", dynamicConfigService.getServiceDetails()));
    }
}

