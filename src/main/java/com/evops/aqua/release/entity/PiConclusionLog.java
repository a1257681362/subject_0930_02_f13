package com.evops.aqua.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 结论历史：只追加。每次初检/复检结论生成、退回补检都逐行留痕，
 * 保留原结论（conclusion）、原因（reason）、操作人与操作时间，复检不覆盖初检。
 */
@TableName("t_pi_conclusion_log")
public class PiConclusionLog extends BaseEntity {

    public static final String ACTION_INITIAL_SUBMIT = "INITIAL_SUBMIT"; // 初检结论
    public static final String ACTION_RETURN = "RETURN";                 // 退回补检
    public static final String ACTION_RETEST_SUBMIT = "RETEST_SUBMIT";   // 复检结论
    public static final String ACTION_RELEASE = "RELEASE";               // 签名放行

    private Long inspectionId;
    private Long roundId;
    private String action;
    private String conclusion;
    private String reason;
    private String operator;
    private LocalDateTime operateTime;

    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public Long getRoundId() { return roundId; }
    public void setRoundId(Long roundId) { this.roundId = roundId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getOperateTime() { return operateTime; }
    public void setOperateTime(LocalDateTime operateTime) { this.operateTime = operateTime; }
}
