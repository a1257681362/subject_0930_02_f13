package com.evops.aqua.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 检验轮次：INITIAL 初检 / RETEST 复检。
 * 复检只追加新轮次（originRoundId 指向被退回的原轮次），初检行永不修改、不覆盖。
 */
@TableName("t_pi_round")
public class PiRound extends BaseEntity {

    public static final String TYPE_INITIAL = "INITIAL";
    public static final String TYPE_RETEST = "RETEST";

    private Long inspectionId;
    private Integer roundNo;
    private String roundType;
    private Long originRoundId;
    private String labSubmitter;
    private LocalDateTime labSubmitTime;
    private String suggestion;

    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public Integer getRoundNo() { return roundNo; }
    public void setRoundNo(Integer roundNo) { this.roundNo = roundNo; }
    public String getRoundType() { return roundType; }
    public void setRoundType(String roundType) { this.roundType = roundType; }
    public Long getOriginRoundId() { return originRoundId; }
    public void setOriginRoundId(Long originRoundId) { this.originRoundId = originRoundId; }
    public String getLabSubmitter() { return labSubmitter; }
    public void setLabSubmitter(String labSubmitter) { this.labSubmitter = labSubmitter; }
    public LocalDateTime getLabSubmitTime() { return labSubmitTime; }
    public void setLabSubmitTime(LocalDateTime labSubmitTime) { this.labSubmitTime = labSubmitTime; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
}
