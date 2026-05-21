package com.example.driving.program;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.dto.ProgramDetailResponse;
import com.example.driving.program.dto.ProgramSummaryResponse;
import com.example.driving.program.enums.ProgramStatus;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.program.repository.VehicleRepository;
import com.example.driving.program.service.ProgramService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ProgramServiceTest {

    @InjectMocks
    private ProgramService programService;

    @Mock
    private ProgramRepository programRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private ScheduleRepository scheduleRepository;

    @Test
    @DisplayName("프로그램 리스트 조회 성공")
    void getPrograms_success() {
        List<ProgramSummaryResponse> summaries = List.of(
                new ProgramSummaryResponse(1L, "서킷 체험", "BMW", "M4", 60, 200000L),
                new ProgramSummaryResponse(2L, "드리프트", "BMW", "M2", 90, 300000L)
        );
        given(programRepository.findActiveSummaries()).willReturn(summaries);

        List<ProgramSummaryResponse> result = programService.getPrograms();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("서킷 체험");
    }

    @Test
    @DisplayName("프로그램 상세 조회 성공 - OPEN 일정 포함")
    void getProgram_success() {
        Program program = Program.builder()
                .programIdx(1L)
                .vehicleIdx(10L)
                .name("서킷 체험")
                .duration(60)
                .amount(200000L)
                .status(ProgramStatus.ACTIVE)
                .build();
        Vehicle vehicle = Vehicle.builder()
                .vehicleIdx(10L)
                .name("BMW")
                .model("M4")
                .build();
        Schedule schedule = Schedule.builder()
                .scheduleIdx(100L)
                .programIdx(1L)
                .startAt(LocalDateTime.of(2026, 6, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 6, 1, 11, 0))
                .capacity(5)
                .remaining(3)
                .status(ScheduleStatus.OPEN)
                .build();

        given(programRepository.findById(1L)).willReturn(Optional.of(program));
        given(vehicleRepository.findById(10L)).willReturn(Optional.of(vehicle));
        given(scheduleRepository.findByProgramIdxAndStatusOrderByStartAt(1L, ScheduleStatus.OPEN))
                .willReturn(List.of(schedule));

        ProgramDetailResponse result = programService.getProgram(1L);

        assertThat(result.name()).isEqualTo("서킷 체험");
        assertThat(result.vehicleName()).isEqualTo("BMW");
        assertThat(result.vehicleModel()).isEqualTo("M4");
        assertThat(result.schedules()).hasSize(1);
        assertThat(result.schedules().get(0).remaining()).isEqualTo(3);
    }

    @Test
    @DisplayName("존재하지 않는 프로그램 조회 시 예외")
    void getProgram_notFound() {
        given(programRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> programService.getProgram(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("프로그램을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("비활성 프로그램 조회 시 예외")
    void getProgram_inactive() {
        Program program = Program.builder()
                .programIdx(1L)
                .vehicleIdx(10L)
                .name("종료된 프로그램")
                .duration(60)
                .amount(200000L)
                .status(ProgramStatus.INACTIVE)
                .build();
        given(programRepository.findById(1L)).willReturn(Optional.of(program));

        assertThatThrownBy(() -> programService.getProgram(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("종료된 프로그램입니다.");
    }
}
