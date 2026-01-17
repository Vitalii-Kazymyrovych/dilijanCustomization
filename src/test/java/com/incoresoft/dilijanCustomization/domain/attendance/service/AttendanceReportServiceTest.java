package com.incoresoft.dilijanCustomization.domain.attendance.service;

import com.incoresoft.dilijanCustomization.config.CafeteriaProps;
import com.incoresoft.dilijanCustomization.domain.attendance.dto.CafeteriaPivotRow;
import com.incoresoft.dilijanCustomization.domain.shared.dto.*;
import com.incoresoft.dilijanCustomization.domain.shared.service.ReportService;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttendanceReportServiceTest {

    @Test
    void buildsReportWithExcludedListsAndUniqueCounts() throws Exception {
        CafeteriaProps props = new CafeteriaProps();
        props.setTimezone("UTC");
        props.setAnalyticsIds(List.of(10L));
        props.setExcludedListNames(Set.of("contractor"));
        File outDir = Files.createTempDirectory("cafe-out").toFile();
        props.setOutputDir(outDir.getAbsolutePath());

        FaceApiRepository repo = mock(FaceApiRepository.class);
        ReportService reportService = mock(ReportService.class);

        FaceListsResponse listsResponse = new FaceListsResponse();
        FaceListDto list1 = new FaceListDto();
        list1.setId(2L);
        list1.setName("Zulu");
        FaceListDto list2 = new FaceListDto();
        list2.setId(1L);
        list2.setName("Alpha");
        FaceListDto list3 = new FaceListDto();
        list3.setId(3L);
        list3.setName("Contractor");
        listsResponse.setData(List.of(list1, list2, list3));
        when(repo.getFaceLists(200)).thenReturn(listsResponse);

        DetectionDto det1 = new DetectionDto();
        det1.setListItem(new ListItemDto());
        det1.getListItem().setId(100L);
        det1.getListItem().setListId(1L);
        DetectionDto det2 = new DetectionDto();
        det2.setListItem(new ListItemDto());
        det2.getListItem().setId(101L);
        det2.getListItem().setListId(2L);
        DetectionDto det3 = new DetectionDto();
        det3.setId(999L);
        when(repo.getAllDetectionsInWindow(isNull(), anyList(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(det1, det2, det3));

        File generated = new File(outDir, "alpha.xlsx");
        when(reportService.exportCafeteriaPivot(any(), anyString(), anyList(), any())).thenReturn(generated);

        AttendanceReportService service = new AttendanceReportService(props, repo, reportService);
        File result = service.buildSingleDayReport(LocalDate.of(2024, 12, 1));

        assertThat(result).isEqualTo(generated);

        ArgumentCaptor<List<CafeteriaPivotRow>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(reportService).exportCafeteriaPivot(eq(LocalDate.of(2024, 12, 1)), eq("Cafeteria"),
                rowsCaptor.capture(), any(File.class));

        List<CafeteriaPivotRow> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(3);
        CafeteriaPivotRow row = rows.get(0);
        assertThat(row.category()).isEqualTo("Alpha");
        assertThat(row.breakfast()).isEqualTo(1);
        assertThat(row.lunch()).isEqualTo(1);
        assertThat(row.dinner()).isEqualTo(1);
        assertThat(row.total()).isEqualTo(3);
        CafeteriaPivotRow row2 = rows.get(1);
        assertThat(row2.category()).isEqualTo("Zulu");
        assertThat(row2.breakfast()).isEqualTo(1);
        assertThat(row2.lunch()).isEqualTo(1);
        assertThat(row2.dinner()).isEqualTo(1);
        assertThat(row2.total()).isEqualTo(3);
        CafeteriaPivotRow offListRow = rows.get(2);
        assertThat(offListRow.category()).isEqualTo("Off the list");
        assertThat(offListRow.breakfast()).isEqualTo(1);
        assertThat(offListRow.lunch()).isEqualTo(1);
        assertThat(offListRow.dinner()).isEqualTo(1);
        assertThat(offListRow.total()).isEqualTo(3);
    }

    @Test
    void buildsReportWithObservedListsWhenFaceListsUnavailable() throws Exception {
        CafeteriaProps props = new CafeteriaProps();
        props.setTimezone("UTC");
        props.setAnalyticsIds(List.of(10L));
        props.setExcludedListNames(Set.of());
        File outDir = Files.createTempDirectory("cafe-out").toFile();
        props.setOutputDir(outDir.getAbsolutePath());

        FaceApiRepository repo = mock(FaceApiRepository.class);
        ReportService reportService = mock(ReportService.class);

        when(repo.getFaceLists(200)).thenReturn(null);

        DetectionDto det1 = new DetectionDto();
        det1.setListItem(new ListItemDto());
        det1.getListItem().setId(100L);
        det1.getListItem().setListId(1L);
        DetectionDto det2 = new DetectionDto();
        det2.setListItem(new ListItemDto());
        det2.getListItem().setId(101L);
        det2.getListItem().setListId(2L);
        when(repo.getAllDetectionsInWindow(isNull(), anyList(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(det1, det2));

        File generated = new File(outDir, "alpha.xlsx");
        when(reportService.exportCafeteriaPivot(any(), anyString(), anyList(), any())).thenReturn(generated);

        AttendanceReportService service = new AttendanceReportService(props, repo, reportService);
        File result = service.buildSingleDayReport(LocalDate.of(2024, 12, 1));

        assertThat(result).isEqualTo(generated);

        ArgumentCaptor<List<CafeteriaPivotRow>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(reportService).exportCafeteriaPivot(eq(LocalDate.of(2024, 12, 1)), eq("Cafeteria"),
                rowsCaptor.capture(), any(File.class));

        List<CafeteriaPivotRow> rows = rowsCaptor.getValue();
        assertThat(rows).extracting(CafeteriaPivotRow::category)
                .containsExactly("list_1", "list_2", "Off the list");
    }
}
