package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 领用明细：一行一种药品一个批号。
 * 剂量与休药期在建单时冻结快照；休药结束日在提交时计算（用药日 + 休药期天数）。
 */
@TableName("t_med_requisition_item")
public class MedRequisitionItem extends BaseEntity {

    private Long requisitionId;
    private Long drugId;
    private Long medBatchId;
    private BigDecimal dosagePerM3;
    private BigDecimal estimatedQty;
    private Integer withdrawalDays;
    private LocalDate withdrawalEndDate;

    public Long getRequisitionId() { return requisitionId; }
    public void setRequisitionId(Long requisitionId) { this.requisitionId = requisitionId; }
    public Long getDrugId() { return drugId; }
    public void setDrugId(Long drugId) { this.drugId = drugId; }
    public Long getMedBatchId() { return medBatchId; }
    public void setMedBatchId(Long medBatchId) { this.medBatchId = medBatchId; }
    public BigDecimal getDosagePerM3() { return dosagePerM3; }
    public void setDosagePerM3(BigDecimal dosagePerM3) { this.dosagePerM3 = dosagePerM3; }
    public BigDecimal getEstimatedQty() { return estimatedQty; }
    public void setEstimatedQty(BigDecimal estimatedQty) { this.estimatedQty = estimatedQty; }
    public Integer getWithdrawalDays() { return withdrawalDays; }
    public void setWithdrawalDays(Integer withdrawalDays) { this.withdrawalDays = withdrawalDays; }
    public LocalDate getWithdrawalEndDate() { return withdrawalEndDate; }
    public void setWithdrawalEndDate(LocalDate withdrawalEndDate) { this.withdrawalEndDate = withdrawalEndDate; }
}
