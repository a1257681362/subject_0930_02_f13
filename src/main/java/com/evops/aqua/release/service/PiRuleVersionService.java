package com.evops.aqua.release.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.release.dto.PiRuleVersionPublishRequest;
import com.evops.aqua.release.entity.PiChangeEvidence;
import com.evops.aqua.release.entity.PiIndicatorRule;
import com.evops.aqua.release.entity.PiRuleVersion;
import com.evops.aqua.release.mapper.PiIndicatorRuleMapper;
import com.evops.aqua.release.mapper.PiRuleVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 出池检验阈值版本：检验项目按「品种 + 用途」组合配置。
 * 新版本发布后旧版本自动停用；检验单建立时冻结版本 id，历史检验单与结果行永不回改。
 */
@Service
public class PiRuleVersionService {

    private final PiRuleVersionMapper versionMapper;
    private final PiIndicatorRuleMapper indicatorRuleMapper;
    private final PiEvidenceService evidenceService;

    public PiRuleVersionService(PiRuleVersionMapper versionMapper,
                                PiIndicatorRuleMapper indicatorRuleMapper,
                                PiEvidenceService evidenceService) {
        this.versionMapper = versionMapper;
        this.indicatorRuleMapper = indicatorRuleMapper;
        this.evidenceService = evidenceService;
    }

    /**
     * 发布阈值版本：写入版本 + 各品种用途组合下的指标阈值，旧 ACTIVE 版本置 SUPERSEDED。
     * requestId 幂等：重复请求（版本号已存在）返回首次版本，不重复写指标行。
     */
    @Transactional(rollbackFor = Exception.class)
    public PiRuleVersion publish(PiRuleVersionPublishRequest request) {
        PiRuleVersion existing = versionMapper.selectOne(
                new QueryWrapper<PiRuleVersion>().eq("version_no", request.getVersionNo()));
        if (existing != null) {
            return existing;
        }

        PiRuleVersion version = new PiRuleVersion();
        version.setVersionNo(request.getVersionNo());
        version.setStatus(PiRuleVersion.STATUS_ACTIVE);
        version.setRemark(request.getRemark());
        versionMapper.insert(version);

        Set<String> comboKeys = new HashSet<>();
        Set<String> indicatorKeys = new HashSet<>();
        for (PiRuleVersionPublishRequest.ComboDto combo : request.getCombos()) {
            String comboKey = combo.getSpecies().trim() + "|" + combo.getUsageCode().trim();
            if (!comboKeys.add(comboKey)) {
                throw new IllegalArgumentException(
                        "版本" + request.getVersionNo() + "中品种+用途组合重复：" + comboKey);
            }
            indicatorKeys.clear();
            for (PiRuleVersionPublishRequest.IndicatorDto ind : combo.getIndicators()) {
                if (ind.getMinValue() != null && ind.getMaxValue() != null
                        && ind.getMinValue().compareTo(ind.getMaxValue()) > 0) {
                    throw new IllegalArgumentException(
                            "组合[" + comboKey + "]指标" + ind.getIndicatorCode()
                                    + "的阈值下限不能大于上限");
                }
                if (!indicatorKeys.add(ind.getIndicatorCode().trim())) {
                    throw new IllegalArgumentException(
                            "组合[" + comboKey + "]下指标编码重复：" + ind.getIndicatorCode());
                }
                PiIndicatorRule rule = new PiIndicatorRule();
                rule.setVersionId(version.getId());
                rule.setSpecies(combo.getSpecies().trim());
                rule.setUsageCode(combo.getUsageCode().trim());
                rule.setIndicatorCode(ind.getIndicatorCode().trim());
                rule.setIndicatorName(ind.getIndicatorName().trim());
                rule.setUnit(ind.getUnit());
                rule.setMinValue(ind.getMinValue());
                rule.setMaxValue(ind.getMaxValue());
                rule.setRequiredFlag(ind.getRequiredFlag() == null
                        ? Boolean.TRUE : ind.getRequiredFlag());
                indicatorRuleMapper.insert(rule);
            }
        }

        // 旧生效版本停用：同一时刻仅一个 ACTIVE
        List<PiRuleVersion> actives = versionMapper.selectList(
                new QueryWrapper<PiRuleVersion>()
                        .eq("status", PiRuleVersion.STATUS_ACTIVE)
                        .ne("id", version.getId()));
        for (PiRuleVersion old : actives) {
            PiRuleVersion before = copyOf(old);
            old.setStatus(PiRuleVersion.STATUS_SUPERSEDED);
            versionMapper.updateById(old);
            evidenceService.record(PiChangeEvidence.ENTITY_RULE_VERSION, old.getId(),
                    PiChangeEvidence.ACTION_UPDATE, request.getRequestId(), request.getOperator(),
                    before, old, "发布新版本" + version.getVersionNo() + "，旧阈值版本停用");
        }

        evidenceService.record(PiChangeEvidence.ENTITY_RULE_VERSION, version.getId(),
                PiChangeEvidence.ACTION_CREATE, request.getRequestId(), request.getOperator(),
                null, version, "发布出池检验阈值版本" + version.getVersionNo()
                        + "，含" + comboKeys.size() + "个品种+用途组合");
        return version;
    }

    public PiRuleVersion getOrThrow(Long id) {
        PiRuleVersion version = versionMapper.selectById(id);
        if (version == null) {
            throw new IllegalArgumentException("检验阈值版本不存在，id=" + id);
        }
        return version;
    }

    /** 当前生效阈值版本；尚未发布任何版本时拒绝建单。 */
    public PiRuleVersion currentActive() {
        List<PiRuleVersion> actives = versionMapper.selectList(
                new QueryWrapper<PiRuleVersion>().eq("status", PiRuleVersion.STATUS_ACTIVE)
                        .orderByDesc("id").last("LIMIT 1"));
        if (actives.isEmpty()) {
            throw new IllegalArgumentException("尚无生效的出池检验阈值版本，请先按品种+用途发布");
        }
        return actives.get(0);
    }

    /** 取某版本下品种+用途组合的全部检验项目（含已停用版本——历史检验单复检仍按冻结版本取阈值）。 */
    public List<PiIndicatorRule> listComboIndicators(Long versionId, String species, String usageCode) {
        return indicatorRuleMapper.selectList(new QueryWrapper<PiIndicatorRule>()
                .eq("version_id", versionId)
                .eq("species", species)
                .eq("usage_code", usageCode)
                .orderByAsc("id"));
    }

    public List<PiRuleVersion> listVersions() {
        return versionMapper.selectList(
                new QueryWrapper<PiRuleVersion>().orderByDesc("id"));
    }

    private PiRuleVersion copyOf(PiRuleVersion source) {
        PiRuleVersion copy = new PiRuleVersion();
        copy.setId(source.getId());
        copy.setVersionNo(source.getVersionNo());
        copy.setStatus(source.getStatus());
        copy.setRemark(source.getRemark());
        return copy;
    }
}
