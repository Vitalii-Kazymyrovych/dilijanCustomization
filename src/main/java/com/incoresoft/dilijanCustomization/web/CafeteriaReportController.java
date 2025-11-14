package com.incoresoft.dilijanCustomization.web;

import com.incoresoft.dilijanCustomization.domain.attendance.service.AttendanceReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/cafeteria")
@RequiredArgsConstructor
public class CafeteriaReportController {
    private final AttendanceReportService service;

    /**
     * Example:
     * POST http://localhost:8080/cafeteria/build?date=2025-11-02&timezone=Europe/Kyiv&listIds=2,5,7
     * - timezone optional (falls back to props)
     * - listIds optional (if absent => all lists except excluded)
     */
    @PostMapping("/build")
    public String build(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "timezone", required = false) String tz,
            @RequestParam(name = "listIds", required = false) String listIds
    ) throws Exception {
        List<Long> only = null;
        if (listIds != null && !listIds.isBlank()) {
            only = Arrays.stream(listIds.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::valueOf)
                    .collect(Collectors.toList());
        }
        File f = service.buildSingleDayReport(date, tz, only);
        return "OK: " + f.getAbsolutePath();
    }
}
