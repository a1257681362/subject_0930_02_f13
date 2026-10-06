package com.evops.aqua.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;

/**
 * 检验项目阈值：某版本下按「品种 + 用途」组合逐指标配置。
 * 闭区间 [minValue, maxValue]，端点可空表示单边约束（如只设上限的药残指标）。
 */
@TableName("t_pi_indicator_rule")
public class PiIndicatorRule extends BaseEntity {

    private Long versionId;
    private String species;
    private String usageCode;
    private String indicatorCode;
    private String indicatorName;
    private String unit;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    private Boolean requiredFlag;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getSpecies() { return species; }
    public void setSpecies(String species) { this.species = species; }
    public String getUsageCode() { return usageCode; }
    public void setUsageCode(String usageCode) { this.usageCode = usageCode; }
    public String getIndicatorCode() { return indicatorCode; }
    public void setIndicatorCode(String indicatorCode) { this.indicatorCode = indicatorCode; }
    public String getIndicatorName() { return indicatorName; }
    public void setIndicatorName(String indicatorName) { this.indicatorName = indicatorName; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getMinValue() { return minValue; }
    public void setMinValue(BigDecimal minValue) { this.minValue = minValue; }
    public BigDecimal getMaxValue() { return maxValue; }
    public void setMaxValue(BigDecimal maxValue) { this.maxValue = maxValue; }
    public Boolean getRequiredFlag() { return requiredFlag; }
    public void setRequiredFlag(Boolean requiredFlag) { this.requiredFlag = requiredFlag; }
}
