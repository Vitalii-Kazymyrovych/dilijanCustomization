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
        list1.setId(1L);
        list1.setName("Alpha");
        FaceListDto list2 = new FaceListDto();
        list2.setId(2L);
        list2.setName("Contractor");
        listsResponse.setData(List.of(list1, list2));
        when(repo.getFaceLists(200)).thenReturn(listsResponse);

        DetectionDto det1 = new DetectionDto();
        det1.setListItem(new ListItemDto());
        det1.getListItem().setId(100L);
        DetectionDto det2 = new DetectionDto();
        det2.setListItem(new ListItemDto());
        det2.getListItem().setId(101L);
        when(repo.getAllDetectionsInWindow(eq(1L), anyList(), anyLong(), anyLong(), anyInt()))
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
        assertThat(rows).hasSize(1);
        CafeteriaPivotRow row = rows.get(0);
        assertThat(row.category()).isEqualTo("Alpha");
        assertThat(row.breakfast()).isEqualTo(2);
        assertThat(row.lunch()).isEqualTo(2);
        assertThat(row.dinner()).isEqualTo(2);
        assertThat(row.total()).isEqualTo(6);
    }
}
