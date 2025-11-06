package com.incoresoft.dilijanCustomization.domain.attendance.dto;

public record CafeteriaPivotRow(String category, int breakfast, int lunch, int dinner) {
    public int total() { return breakfast + lunch + dinner; }
}
