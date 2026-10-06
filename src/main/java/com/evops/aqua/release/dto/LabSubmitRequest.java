package com.evops.aqua.release.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 实验室结果录入请求（初检/复检同一入口，按检验单当前轮次落新结果行）。
 * 每个样品×指标一行；必检项目必须全部有值，系统比对阈值生成 PASS/FAIL 判定与放行建议。
 */
public class LabSubmitRequest {

    @NotBlank(message = "实验室录入人不能为空")
    private String operator;

    @NotBlank(message = "请求号不能为空")
    private String requestId;

    @NotEmpty(message = "至少录入一项检验结果")
    @Valid
    private List<ResultInput> items;

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public List<ResultInput> getItems() { return items; }
    public void setItems(List<ResultInput> items) { this.items = items; }

    public static class ResultInput {
        @NotBlank(message = "样品编号不能为空")
        private String sampleNo;

        @NotBlank(message = "指标编码不能为空")
        private String indicatorCode;

        @NotNull(message = "检验结果值不能为空（缺测请走退回补检流程）")
        private BigDecimal value;

        public String getSampleNo() { return sampleNo; }
        public void setSampleNo(String sampleNo) { this.sampleNo = sampleNo; }
        public String getIndicatorCode() { return indicatorCode; }
        public void setIndicatorCode(String indicatorCode) { this.indicatorCode = indicatorCode; }
        public BigDecimal getValue() { return value; }
        public void setValue(BigDecimal value) { this.value = value; }
    }
}
