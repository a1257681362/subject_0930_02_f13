package com.evops.aqua.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 出池检验单：一个养殖批次一张（t_pi_inspection.batch_id 唯一）。
 * 建立时冻结品种、用途与阈值版本；状态机见 STATUS_*。
 * 退回补检：returnReason/returnBy/returnTime 记录首次退回事实，复检后再退回覆盖更新前
 * 每次退回都会在 t_pi_conclusion_log 追加不可变留痕，原结论与原因可追溯。
 * 授权电子签名（releasedBy/releasedTime/signature）后批次才可发货。
 */
@TableName("t_pi_inspection")
public class PiInspection extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";              // 待检（已建单，未录入初检结果）
    public static final String STATUS_LAB_SUBMITTED = "LAB_SUBMITTED";  // 实验室已录入初检/复检结果
    public static final String STATUS_RETURNED = "RETURNED";            // 退回补检
    public static final String STATUS_RELEASED = "RELEASED";            // 授权电子签名放行
    public static final String STATUS_VOID = "VOID";                    // 作废（批次废弃，保留留痕）

    /** 系统比对阈值生成的放行建议。 */
    public static final String SUGGEST_PASS = "PASS";   // 建议放行
    public static final String SUGGEST_FAIL = "FAIL";   // 建议不予放行

    private String inspectionNo;
    private Long batchId;
    private Long pondId;
    private String species;
    private String usageCode;
    private Long ruleVersionId;
    private java.time.LocalDate inspectDate;
    private String status;
    private String returnReason;
    private String returnBy;
    private LocalDateTime returnTime;
    private String suggestion;
    private LocalDateTime suggestionTime;
    private String releasedBy;
    private LocalDateTime releasedTime;
    private String releaseRemark;
    private String signature;
    private String requestId;
    private String operator;
    private String remark;

    public String getInspectionNo() { return inspectionNo; }
    public void setInspectionNo(String inspectionNo) { this.inspectionNo = inspectionNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getPondId() { return pondId; }
    public void setPondId(Long pondId) { this.pondId = pondId; }
    public String getSpecies() { return species; }
    public void setSpecies(String species) { this.species = species; }
    public String getUsageCode() { return usageCode; }
    public void setUsageCode(String usageCode) { this.usageCode = usageCode; }
    public Long getRuleVersionId() { return ruleVersionId; }
    public void setRuleVersionId(Long ruleVersionId) { this.ruleVersionId = ruleVersionId; }
    public java.time.LocalDate getInspectDate() { return inspectDate; }
    public void setInspectDate(java.time.LocalDate inspectDate) { this.inspectDate = inspectDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReturnReason() { return returnReason; }
    public void setReturnReason(String returnReason) { this.returnReason = returnReason; }
    public String getReturnBy() { return returnBy; }
    public void setReturnBy(String returnBy) { this.returnBy = returnBy; }
    public LocalDateTime getReturnTime() { return returnTime; }
    public void setReturnTime(LocalDateTime returnTime) { this.returnTime = returnTime; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    public LocalDateTime getSuggestionTime() { return suggestionTime; }
    public void setSuggestionTime(LocalDateTime suggestionTime) { this.suggestionTime = suggestionTime; }
    public String getReleasedBy() { return releasedBy; }
    public void setReleasedBy(String releasedBy) { this.releasedBy = releasedBy; }
    public LocalDateTime getReleasedTime() { return releasedTime; }
    public void setReleasedTime(LocalDateTime releasedTime) { this.releasedTime = releasedTime; }
    public String getReleaseRemark() { return releaseRemark; }
    public void setReleaseRemark(String releaseRemark) { this.releaseRemark = releaseRemark; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
