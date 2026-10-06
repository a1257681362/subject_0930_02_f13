package com.evops.aqua.release.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.release.entity.PiChangeEvidence;
import com.evops.aqua.release.mapper.PiChangeEvidenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 出池检验变更证据：所有状态变化统一落 t_pi_change_evidence，只追加。
 */
@Service
public class PiEvidenceService {

    private final PiChangeEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;

    public PiEvidenceService(PiChangeEvidenceMapper evidenceMapper, ObjectMapper objectMapper) {
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
    }

    public void record(String entityType, Long entityId, String action, String requestId,
                       String operator, Object before, Object after, String detail) {
        PiChangeEvidence evidence = new PiChangeEvidence();
        evidence.setEntityType(entityType);
        evidence.setEntityId(entityId);
        evidence.setAction(action);
        evidence.setRequestId(requestId);
        evidence.setOperator(operator);
        evidence.setBeforeJson(toJson(before));
        evidence.setAfterJson(toJson(after));
        evidence.setDetail(detail);
        evidenceMapper.insert(evidence);
    }

    public List<PiChangeEvidence> list(String entityType, Long entityId, String requestId) {
        QueryWrapper<PiChangeEvidence> wrapper = new QueryWrapper<>();
        wrapper.eq(entityType != null && !entityType.trim().isEmpty(), "entity_type", entityType);
        wrapper.eq(entityId != null, "entity_id", entityId);
        wrapper.eq(requestId != null && !requestId.trim().isEmpty(), "request_id", requestId);
        wrapper.orderByAsc("id");
        return evidenceMapper.selectList(wrapper);
    }

    /** 按幂等请求号查找首条证据（用于重复请求回放首次结果）。 */
    public PiChangeEvidence findByRequestId(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return null;
        }
        List<PiChangeEvidence> rows = evidenceMapper.selectList(
                new QueryWrapper<PiChangeEvidence>().eq("request_id", requestId)
                        .orderByAsc("id").last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}
