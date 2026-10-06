package com.evops.aqua.med.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.med.dto.MedBatchInRequest;
import com.evops.aqua.med.entity.MedBatch;
import com.evops.aqua.med.entity.MedChangeEvidence;
import com.evops.aqua.med.entity.MedDrug;
import com.evops.aqua.med.mapper.MedBatchMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 药品批号库存：入库幂等；扣减只发生在领用审核通过的同一事务内。
 */
@Service
public class MedBatchService {

    private final MedBatchMapper batchMapper;
    private final MedDrugService drugService;
    private final MedEvidenceService evidenceService;

    public MedBatchService(MedBatchMapper batchMapper, MedDrugService drugService,
                           MedEvidenceService evidenceService) {
        this.batchMapper = batchMapper;
        this.drugService = drugService;
        this.evidenceService = evidenceService;
    }

    @Transactional(rollbackFor = Exception.class)
    public MedBatch stockIn(MedBatchInRequest request) {
        MedBatch duplicated = batchMapper.selectOne(
                new QueryWrapper<MedBatch>().eq("request_id", request.getRequestId()).last("limit 1"));
        if (duplicated != null) {
            return duplicated;
        }
        MedDrug drug = drugService.getOrThrow(request.getDrugId());
        Long noUsed = batchMapper.selectCount(new QueryWrapper<MedBatch>()
                .eq("drug_id", request.getDrugId()).eq("batch_no", request.getBatchNo()));
        if (noUsed != null && noUsed > 0) {
            throw new IllegalArgumentException("该药品下批号已存在：" + request.getBatchNo());
        }
        MedBatch batch = new MedBatch();
        BeanUtils.copyProperties(request, batch);
        batch.setQuantityConsumed(BigDecimal.ZERO);
        batch.setStatus(MedBatch.STATUS_IN_STOCK);
        batchMapper.insert(batch);
        evidenceService.record(MedChangeEvidence.ENTITY_BATCH, batch.getId(),
                MedChangeEvidence.ACTION_BATCH_IN, request.getRequestId(),
                request.getOperator(), null, batch,
                "药品批号入库：" + drug.getDrugName() + "/" + batch.getBatchNo()
                        + "，数量" + batch.getQuantityIn());
        return batch;
    }

    /**
     * 扣减库存：仅在领用审核通过的事务内调用；可用量不足抛异常使整体回滚。
     */
    public void deduct(MedBatch batch, BigDecimal quantity, String operator, String requestId, String detail) {
        MedBatch before = copyOf(batch);
        if (batch.availableQuantity().compareTo(quantity) < 0) {
            throw new IllegalArgumentException("药品批号" + batch.getBatchNo() + "可用库存不足：可用"
                    + batch.availableQuantity() + "，需扣减" + quantity);
        }
        batch.setQuantityConsumed(batch.getQuantityConsumed().add(quantity));
        if (batch.availableQuantity().compareTo(BigDecimal.ZERO) == 0) {
            batch.setStatus(MedBatch.STATUS_EXHAUSTED);
        }
        batchMapper.updateById(batch);
        evidenceService.record(MedChangeEvidence.ENTITY_BATCH, batch.getId(),
                MedChangeEvidence.ACTION_STOCK_DEDUCT, requestId, operator, before, batch, detail);
    }

    public MedBatch getOrThrow(Long id) {
        MedBatch batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new IllegalArgumentException("药品批号不存在，id=" + id);
        }
        return batch;
    }

    public List<MedBatch> list(Long drugId) {
        QueryWrapper<MedBatch> wrapper = new QueryWrapper<>();
        wrapper.eq(drugId != null, "drug_id", drugId);
        wrapper.orderByAsc("id");
        return batchMapper.selectList(wrapper);
    }

    private MedBatch copyOf(MedBatch batch) {
        MedBatch copy = new MedBatch();
        BeanUtils.copyProperties(batch, copy);
        return copy;
    }
}
