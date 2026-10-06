package com.evops.aqua.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 检验样品：检验单下样品编号唯一。
 * 检测报告与样品编号一一对应：出具报告时冻结 reportNo/reportTime/reportIssuedBy，
 * reportNo 全局唯一（uk_pi_sample_report），一个样品只能出具一次报告，重复出具幂等返回。
 */
@TableName("t_pi_sample")
public class PiSample extends BaseEntity {

    private Long inspectionId;
    private String sampleNo;
    private LocalDateTime sampleTime;
    private String sampler;
    private String reportNo;
    private LocalDateTime reportTime;
    private String reportIssuedBy;
    private String remark;

    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public String getSampleNo() { return sampleNo; }
    public void setSampleNo(String sampleNo) { this.sampleNo = sampleNo; }
    public LocalDateTime getSampleTime() { return sampleTime; }
    public void setSampleTime(LocalDateTime sampleTime) { this.sampleTime = sampleTime; }
    public String getSampler() { return sampler; }
    public void setSampler(String sampler) { this.sampler = sampler; }
    public String getReportNo() { return reportNo; }
    public void setReportNo(String reportNo) { this.reportNo = reportNo; }
    public LocalDateTime getReportTime() { return reportTime; }
    public void setReportTime(LocalDateTime reportTime) { this.reportTime = reportTime; }
    public String getReportIssuedBy() { return reportIssuedBy; }
    public void setReportIssuedBy(String reportIssuedBy) { this.reportIssuedBy = reportIssuedBy; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
