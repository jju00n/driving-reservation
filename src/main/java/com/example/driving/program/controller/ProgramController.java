package com.example.driving.program.controller;

import com.example.driving.common.response.ApiResponse;
import com.example.driving.program.dto.ProgramDetailResponse;
import com.example.driving.program.dto.ProgramSummaryResponse;
import com.example.driving.program.service.ProgramService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Program", description = "프로그램 API")
@RestController
@RequestMapping("/programs")
@RequiredArgsConstructor
public class ProgramController {

    private final ProgramService programService;

    @Operation(summary = "프로그램 리스트")
    @GetMapping
    public ApiResponse<List<ProgramSummaryResponse>> getPrograms() {
        return ApiResponse.ok(programService.getPrograms());
    }

    @Operation(summary = "프로그램 상세")
    @GetMapping("/{programIdx}")
    public ApiResponse<ProgramDetailResponse> getProgram(@PathVariable Long programIdx) {
        return ApiResponse.ok(programService.getProgram(programIdx));
    }
}
