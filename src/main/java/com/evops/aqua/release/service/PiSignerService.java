package com.evops.aqua.release.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.release.dto.SignerRegisterRequest;
import com.evops.aqua.release.entity.PiChangeEvidence;
import com.evops.aqua.release.entity.PiSigner;
import com.evops.aqua.release.mapper.PiSignerMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 授权电子签名人登记与启停。只有登记在册且启用的人员才能对检验单电子签名放行。
 */
@Service
public class PiSignerService {

    private final PiSignerMapper signerMapper;
    private final PiEvidenceService evidenceService;

    public PiSignerService(PiSignerMapper signerMapper, PiEvidenceService evidenceService) {
        this.signerMapper = signerMapper;
        this.evidenceService = evidenceService;
    }

    /** 登记授权签名人，requestId 幂等；姓名已存在时回放首次记录。 */
    @Transactional(rollbackFor = Exception.class)
    public PiSigner register(SignerRegisterRequest request) {
        List<PiSigner> byRequest = signerMapper.selectList(
                new QueryWrapper<PiSigner>().eq("request_id", request.getRequestId()));
        if (!byRequest.isEmpty()) {
            return byRequest.get(0);
        }
        PiSigner existing = signerMapper.selectOne(
                new QueryWrapper<PiSigner>().eq("signer_name", request.getSignerName().trim()));
        if (existing != null) {
            return existing;
        }
        PiSigner signer = new PiSigner();
        signer.setSignerName(request.getSignerName().trim());
        signer.setSignSecret(request.getSignSecret());
        signer.setEnabled(Boolean.TRUE);
        signer.setRemark(request.getRemark());
        signer.setRequestId(request.getRequestId());
        signerMapper.insert(signer);
        evidenceService.record(PiChangeEvidence.ENTITY_SIGNER, signer.getId(),
                PiChangeEvidence.ACTION_CREATE, request.getRequestId(), signer.getSignerName(),
                null, signer, "登记授权电子签名人：" + signer.getSignerName());
        return signer;
    }

    /** 禁用授权签名人：禁用后立即不能再签名；历史签名保留。 */
    @Transactional(rollbackFor = Exception.class)
    public PiSigner toggle(Long id, boolean enabled, String operator) {
        PiSigner signer = signerMapper.selectById(id);
        if (signer == null) {
            throw new IllegalArgumentException("签名人不存在，id=" + id);
        }
        PiSigner before = copyOf(signer);
        signer.setEnabled(enabled);
        signerMapper.updateById(signer);
        evidenceService.record(PiChangeEvidence.ENTITY_SIGNER, signer.getId(),
                PiChangeEvidence.ACTION_UPDATE, null, operator, before, signer,
                (enabled ? "启用" : "禁用") + "电子签名授权：" + signer.getSignerName());
        return signer;
    }

    public List<PiSigner> list() {
        return signerMapper.selectList(new QueryWrapper<PiSigner>().orderByAsc("id"));
    }

    private PiSigner copyOf(PiSigner source) {
        PiSigner copy = new PiSigner();
        copy.setId(source.getId());
        copy.setSignerName(source.getSignerName());
        copy.setSignSecret(source.getSignSecret());
        copy.setEnabled(source.getEnabled());
        copy.setRemark(source.getRemark());
        return copy;
    }
}
