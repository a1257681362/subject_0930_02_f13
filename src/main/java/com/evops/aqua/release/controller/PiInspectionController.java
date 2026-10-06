package com.evops.aqua.release.controller;

import com.evops.aqua.release.dto.InspectionCreateRequest;
import com.evops.aqua.release.dto.LabSubmitRequest;
import com.evops.aqua.release.dto.ReleaseSignRequest;
import com.evops.aqua.release.dto.ReportIssueRequest;
import com.evops.aqua.release.dto.ReturnRequest;
import com.evops.aqua.release.dto.SignerRegisterRequest;
import com.evops.aqua.release.entity.PiChangeEvidence;
import com.evops.aqua.release.entity.PiInspection;
import com.evops.aqua.release.entity.PiSample;
import com.evops.aqua.release.entity.PiSigner;
import com.evops.aqua.release.service.PiEvidenceService;
import com.evops.aqua.release.service.PiInspectionService;
import com.evops.aqua.release.service.PiSignerService;
import com.evops.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 批次出池检验与放行签名入口：
 * 建单 → 实验室录入（系统比对阈值给放行建议）→ 退回补检（留原结论/原因/时间）→
 * 复检追加 → 样品出报告（一一对应）→ 授权电子签名放行（批次可发货）。
 */
@RestController
@RequestMapping("/api/aqua/release")
public class PiInspectionController {

    private final PiInspectionService inspectionService;
    private final PiSignerService signerService;
    private final PiEvidenceService evidenceService;

    public PiInspectionController(PiInspectionService inspectionService,
                                  PiSignerService signerService,
                                  PiEvidenceService evidenceService) {
        this.inspectionService = inspectionService;
        this.signerService = signerService;
        this.evidenceService = evidenceService;
    }

    // ---------------- 授权签名人 ----------------

    @PostMapping("/signers")
    public ApiResponse<PiSigner> registerSigner(
            @Validated @RequestBody SignerRegisterRequest request) {
        return ApiResponse.ok(signerService.register(request));
    }

    @GetMapping("/signers")
    public ApiResponse<List<PiSigner>> listSigners() {
        return ApiResponse.ok(signerService.list());
    }

    @PostMapping("/signers/{id}/toggle")
    public ApiResponse<PiSigner> toggleSigner(@PathVariable Long id,
                                              @RequestParam boolean enabled,
                                              @RequestParam String operator) {
        return ApiResponse.ok(signerService.toggle(id, enabled, operator));
    }

    // ---------------- 检验单主流程 ----------------

    @PostMapping("/inspections")
    public ApiResponse<PiInspection> create(
            @Validated @RequestBody InspectionCreateRequest request) {
        return ApiResponse.ok(inspectionService.create(request));
    }

    @GetMapping("/inspections")
    public ApiResponse<List<PiInspection>> list(@RequestParam(required = false) Long batchId,
                                                @RequestParam(required = false) String status) {
        return ApiResponse.ok(inspectionService.list(batchId, status));
    }

    @GetMapping("/inspections/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(inspectionService.detail(id));
    }

    /** 实验室录入初检/复检结果：系统比对阈值生成放行建议。 */
    @PostMapping("/inspections/{id}/lab-submit")
    public ApiResponse<Map<String, Object>> labSubmit(
            @PathVariable Long id, @Validated @RequestBody LabSubmitRequest request) {
        return ApiResponse.ok(inspectionService.submitResults(id, request));
    }

    /** 退回补检：保留原结论、原因与操作时间，初检结果不覆盖。 */
    @PostMapping("/inspections/{id}/return")
    public ApiResponse<PiInspection> returnForRetest(
            @PathVariable Long id, @Validated @RequestBody ReturnRequest request) {
        return ApiResponse.ok(inspectionService.returnForRetest(id, request));
    }

    /** 出具检测报告：报告号与样品编号一一对应。 */
    @PostMapping("/inspections/{id}/reports")
    public ApiResponse<PiSample> issueReport(
            @PathVariable Long id, @Validated @RequestBody ReportIssueRequest request) {
        return ApiResponse.ok(inspectionService.issueReport(id, request));
    }

    /** 按报告号反查（报告 ↔ 样品一一对应校验入口）。 */
    @GetMapping("/reports/{reportNo}")
    public ApiResponse<Map<String, Object>> report(@PathVariable String reportNo) {
        return ApiResponse.ok(inspectionService.getReport(reportNo));
    }

    /** 授权人员电子签名放行：签名后批次才可进入发货。 */
    @PostMapping("/inspections/{id}/release")
    public ApiResponse<PiInspection> release(
            @PathVariable Long id, @Validated @RequestBody ReleaseSignRequest request) {
        return ApiResponse.ok(inspectionService.signRelease(id, request));
    }

    /**
     * 海水养殖管理旧数据回放与新旧版本差异解释（提交新版本前的核对入口）。
     */
    @GetMapping("/batches/{batchId}/legacy-replay")
    public ApiResponse<Map<String, Object>> legacyReplay(@PathVariable Long batchId) {
        return ApiResponse.ok(inspectionService.replayLegacy(batchId));
    }

    // ---------------- 证据 ----------------
    @GetMapping("/evidence")
    public ApiResponse<List<PiChangeEvidence>> evidence(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) String requestId) {
        return ApiResponse.ok(evidenceService.list(entityType, entityId, requestId));
    }
}
