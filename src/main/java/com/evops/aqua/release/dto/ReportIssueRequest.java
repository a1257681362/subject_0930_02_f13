package com.evops.aqua.release.dto;

import javax.validation.constraints.NotBlank;

/**
 * 检测报告出具请求：一个样品编号只能出具一次报告，报告号与样品编号一一对应。
 * 重复出具（重试）返回首次报告，不产生第二份报告。
 */
public class ReportIssueRequest {

    @NotBlank(message = "样品编号不能为空")
    private String sampleNo;

    @NotBlank(message = "报告编号不能为空")
    private String reportNo;

    @NotBlank(message = "报告出具人不能为空")
    private String operator;

    @NotBlank(message = "请求号不能为空")
    private String requestId;

    private String remark;

    public String getSampleNo() { return sampleNo; }
    public void setSampleNo(String sampleNo) { this.sampleNo = sampleNo; }
    public String getReportNo() { return reportNo; }
    public void setReportNo(String reportNo) { this.reportNo = reportNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
