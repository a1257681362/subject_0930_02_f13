package com.evops.aqua.release.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 检验阈值版本发布请求：检验项目按「品种 + 用途」组合配置。
 * 同一品种+用途下指标编码不可重复；新版本发布后旧版本自动停用。
 */
public class PiRuleVersionPublishRequest {

    @NotBlank(message = "版本号不能为空")
    private String versionNo;

    private String remark;

    @NotBlank(message = "操作人不能为空")
    private String operator;

    @NotBlank(message = "请求号不能为空")
    private String requestId;

    @NotEmpty(message = "至少配置一个品种+用途组合")
    @Valid
    private List<ComboDto> combos;

    public String getVersionNo() { return versionNo; }
    public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public List<ComboDto> getCombos() { return combos; }
    public void setCombos(List<ComboDto> combos) { this.combos = combos; }

    /** 品种 + 用途组合下的检验项目。 */
    public static class ComboDto {
        @NotBlank(message = "品种不能为空")
        private String species;

        @NotBlank(message = "用途编码不能为空")
        private String usageCode;

        private String usageName;

        @NotEmpty(message = "每个组合至少配置一个检验项目")
        @Valid
        private List<IndicatorDto> indicators;

        public String getSpecies() { return species; }
        public void setSpecies(String species) { this.species = species; }
        public String getUsageCode() { return usageCode; }
        public void setUsageCode(String usageCode) { this.usageCode = usageCode; }
        public String getUsageName() { return usageName; }
        public void setUsageName(String usageName) { this.usageName = usageName; }
        public List<IndicatorDto> getIndicators() { return indicators; }
        public void setIndicators(List<IndicatorDto> indicators) { this.indicators = indicators; }
    }

    /** 单个检验项目的阈值闭区间 [minValue, maxValue]，端点可空表示单边约束。 */
    public static class IndicatorDto {
        @NotBlank(message = "指标编码不能为空")
        private String indicatorCode;

        @NotBlank(message = "指标名称不能为空")
        private String indicatorName;

        private String unit;
        private BigDecimal minValue;
        private BigDecimal maxValue;
        private Boolean requiredFlag;

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
}
