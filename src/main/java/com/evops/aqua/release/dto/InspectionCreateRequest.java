package com.evops.aqua.release.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出池检验单创建请求：一批一单（在养批次），随单登记样品编号；
 * 建立时冻结品种、用途与当时生效的阈值版本。
 */
public class InspectionCreateRequest {

    @NotNull(message = "投苗批次不能为空")
    private Long batchId;

    @NotNull(message = "检验日期不能为空")
    private LocalDate inspectDate;

    @NotBlank(message = "用途编码不能为空")
    private String usageCode;

    @NotBlank(message = "操作人不能为空")
    private String operator;

    @NotBlank(message = "请求号不能为空")
    private String requestId;

    private String remark;

    @NotEmpty(message = "检验单至少登记一个样品")
    @Valid
    private List<SampleDto> samples;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public LocalDate getInspectDate() { return inspectDate; }
    public void setInspectDate(LocalDate inspectDate) { this.inspectDate = inspectDate; }
    public String getUsageCode() { return usageCode; }
    public void setUsageCode(String usageCode) { this.usageCode = usageCode; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public List<SampleDto> getSamples() { return samples; }
    public void setSamples(List<SampleDto> samples) { this.samples = samples; }

    public static class SampleDto {
        @NotBlank(message = "样品编号不能为空")
        private String sampleNo;
        private LocalDateTime sampleTime;
        private String sampler;
        private String remark;

        public String getSampleNo() { return sampleNo; }
        public void setSampleNo(String sampleNo) { this.sampleNo = sampleNo; }
        public LocalDateTime getSampleTime() { return sampleTime; }
        public void setSampleTime(LocalDateTime sampleTime) { this.sampleTime = sampleTime; }
        public String getSampler() { return sampler; }
        public void setSampler(String sampler) { this.sampler = sampler; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }
}
