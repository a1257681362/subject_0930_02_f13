package com.evops.aqua.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;

/**
 * 检验结果项：按「轮次 × 样品 × 指标」一行。
 * 阈值（limitMin/limitMax/requiredFlag）在建单时自版本快照冻结；
 * resultValue/judge 在实验室录入时写入，judge 由系统比对阈值生成，一经提交不改写
 * （复检在新一轮落新行，绝不覆盖初检原值）。
 */
@TableName("t_pi_result_item")
public class PiResultItem extends BaseEntity {

    public static final String JUDGE_PASS = "PASS";
    public static final String JUDGE_FAIL = "FAIL";
    public static final String JUDGE_MISSING = "MISSING"; // 必检项未录入

    private Long roundId;
    private Long sampleId;
    private String indicatorCode;
    private String indicatorName;
    private String unit;
    private BigDecimal limitMin;
    private BigDecimal limitMax;
    private Boolean requiredFlag;
    private BigDecimal resultValue;
    private String judge;

    public Long getRoundId() { return roundId; }
    public void setRoundId(Long roundId) { this.roundId = roundId; }
    public Long getSampleId() { return sampleId; }
    public void setSampleId(Long sampleId) { this.sampleId = sampleId; }
    public String getIndicatorCode() { return indicatorCode; }
    public void setIndicatorCode(String indicatorCode) { this.indicatorCode = indicatorCode; }
    public String getIndicatorName() { return indicatorName; }
    public void setIndicatorName(String indicatorName) { this.indicatorName = indicatorName; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getLimitMin() { return limitMin; }
    public void setLimitMin(BigDecimal limitMin) { this.limitMin = limitMin; }
    public BigDecimal getLimitMax() { return limitMax; }
    public void setLimitMax(BigDecimal limitMax) { this.limitMax = limitMax; }
    public Boolean getRequiredFlag() { return requiredFlag; }
    public void setRequiredFlag(Boolean requiredFlag) { this.requiredFlag = requiredFlag; }
    public BigDecimal getResultValue() { return resultValue; }
    public void setResultValue(BigDecimal resultValue) { this.resultValue = resultValue; }
    public String getJudge() { return judge; }
    public void setJudge(String judge) { this.judge = judge; }
}
