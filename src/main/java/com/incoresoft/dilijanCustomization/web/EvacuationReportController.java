package com.incoresoft.dilijanCustomization.web;

import com.incoresoft.dilijanCustomization.domain.evacuation.service.EvacuationReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class EvacuationReportController {

    private final EvacuationReportService service;

    /**
     * GET /evacuation/report?listIds=2,5,7
     * Returns an XLSX file with 1 sheet per list.
     */
    @GetMapping("/evacuation/report")
    public ResponseEntity<?> generate(@RequestParam("listIds") String listIds) {
        if (!StringUtils.hasText(listIds)) {
            return ResponseEntity.badRequest().body("Query param 'listIds' is required");
        }
        try {
            List<Long> ids = Arrays.stream(listIds.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(Long::valueOf)
                    .toList();

            File file = service.buildEvacuationReport(ids);
            String name = "evacuation_" + LocalDate.now() + ".xlsx";
            String cd = "attachment; filename=\"" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "\"";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, cd)
                    .header(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(new FileSystemResource(file));
        } catch (Exception ex) {
            log.error("Evacuation report error: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body("Failed to build report: " + ex.getMessage());
        }
    }
}
