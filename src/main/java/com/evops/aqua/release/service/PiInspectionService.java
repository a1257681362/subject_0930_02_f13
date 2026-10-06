package com.evops.aqua.release.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.entity.SeedlingBatch;
import com.evops.aqua.entity.InspectionRecord;
import com.evops.aqua.mapper.InspectionRecordMapper;
import com.evops.aqua.mapper.SeedlingBatchMapper;
import com.evops.aqua.release.dto.InspectionCreateRequest;
import com.evops.aqua.release.dto.LabSubmitRequest;
import com.evops.aqua.release.dto.ReleaseSignRequest;
import com.evops.aqua.release.dto.ReportIssueRequest;
import com.evops.aqua.release.dto.ReturnRequest;
import com.evops.aqua.release.entity.PiChangeEvidence;
import com.evops.aqua.release.entity.PiConclusionLog;
import com.evops.aqua.release.entity.PiIndicatorRule;
import com.evops.aqua.release.entity.PiInspection;
import com.evops.aqua.release.entity.PiResultItem;
import com.evops.aqua.release.entity.PiRound;
import com.evops.aqua.release.entity.PiRuleVersion;
import com.evops.aqua.release.entity.PiSample;
import com.evops.aqua.release.entity.PiSigner;
import com.evops.aqua.release.mapper.PiConclusionLogMapper;
import com.evops.aqua.release.mapper.PiInspectionMapper;
import com.evops.aqua.release.mapper.PiResultItemMapper;
import com.evops.aqua.release.mapper.PiRoundMapper;
import com.evops.aqua.release.mapper.PiSampleMapper;
import com.evops.aqua.release.mapper.PiSignerMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次出池检验与放行签名主流程。
 *
 * 建单（冻结品种/用途/阈值版本，登记样品）→ 实验室按样品逐项目录入初检结果，
 * 系统比对阈值生成 PASS/FAIL 放行建议 → 可退回补检（保留原结论、原因、操作时间）→
 * 复检只追加新轮次，绝不覆盖初检 → 建议 PASS 且授权人员电子签名后批次 RELEASED 可发货。
 * 检测报告与样品编号一一对应；所有动作写 t_pi_change_evidence，业务请求 requestId 幂等。
 */
@Service
public class PiInspectionService {

    private final PiInspectionMapper inspectionMapper;
    private final PiSampleMapper sampleMapper;
    private final PiRoundMapper roundMapper;
    private final PiResultItemMapper itemMapper;
    private final PiConclusionLogMapper conclusionLogMapper;
    private final PiSignerMapper signerMapper;
    private final SeedlingBatchMapper batchMapper;
    private final InspectionRecordMapper legacyInspectionMapper;
    private final PiRuleVersionService ruleVersionService;
    private final PiEvidenceService evidenceService;

    public PiInspectionService(PiInspectionMapper inspectionMapper,
                               PiSampleMapper sampleMapper,
                               PiRoundMapper roundMapper,
                               PiResultItemMapper itemMapper,
                               PiConclusionLogMapper conclusionLogMapper,
                               PiSignerMapper signerMapper,
                               SeedlingBatchMapper batchMapper,
                               InspectionRecordMapper legacyInspectionMapper,
                               PiRuleVersionService ruleVersionService,
                               PiEvidenceService evidenceService) {
        this.inspectionMapper = inspectionMapper;
        this.sampleMapper = sampleMapper;
        this.roundMapper = roundMapper;
        this.itemMapper = itemMapper;
        this.conclusionLogMapper = conclusionLogMapper;
        this.signerMapper = signerMapper;
        this.batchMapper = batchMapper;
        this.legacyInspectionMapper = legacyInspectionMapper;
        this.ruleVersionService = ruleVersionService;
        this.evidenceService = evidenceService;
    }

    // ------------------------------------------------------------
    // 建单
    // ------------------------------------------------------------

    /**
     * 建出池检验单：一批一单（同批存在未完结单拒绝），随单登记样品，
     * 冻结品种、用途与当前生效阈值版本。requestId 重复返回首次检验单。
     */
    @Transactional(rollbackFor = Exception.class)
    public PiInspection create(InspectionCreateRequest request) {
        PiInspection replay = replayIfPresent(request.getRequestId());
        if (replay != null) {
            return replay;
        }
        SeedlingBatch batch = batchMapper.selectById(request.getBatchId());
        if (batch == null) {
            throw new IllegalArgumentException("投苗批次不存在，id=" + request.getBatchId());
        }
        if (!SeedlingBatch.STATUS_STOCKED.equals(batch.getStatus())) {
            throw new IllegalArgumentException("批次" + batch.getBatchNo()
                    + "不是在养状态，不能建立出池检验单");
        }
        if (batch.getSpecies() == null || batch.getSpecies().trim().isEmpty()) {
            throw new IllegalArgumentException("批次" + batch.getBatchNo() + "未登记品种，无法匹配检验项目组合");
        }
        PiRuleVersion version = ruleVersionService.currentActive();
        List<PiIndicatorRule> rules = ruleVersionService.listComboIndicators(
                version.getId(), batch.getSpecies(), request.getUsageCode());
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("阈值版本" + version.getVersionNo()
                    + "未配置品种[" + batch.getSpecies() + "]用途[" + request.getUsageCode()
                    + "]的检验项目，不能建单");
        }
        List<PiInspection> open = inspectionMapper.selectList(new QueryWrapper<PiInspection>()
                .eq("batch_id", batch.getId())
                .notIn("status", PiInspection.STATUS_RELEASED, PiInspection.STATUS_VOID));
        if (!open.isEmpty()) {
            throw new IllegalArgumentException("批次" + batch.getBatchNo()
                    + "已存在未完结的出池检验单" + open.get(0).getInspectionNo());
        }

        PiInspection inspection = new PiInspection();
        inspection.setInspectionNo(nextInspectionNo());
        inspection.setBatchId(batch.getId());
        inspection.setPondId(batch.getPondId());
        inspection.setSpecies(batch.getSpecies());
        inspection.setUsageCode(request.getUsageCode().trim());
        inspection.setRuleVersionId(version.getId());
        inspection.setInspectDate(request.getInspectDate());
        inspection.setStatus(PiInspection.STATUS_PENDING);
        inspection.setRequestId(request.getRequestId());
        inspection.setOperator(request.getOperator());
        inspection.setRemark(request.getRemark());
        inspectionMapper.insert(inspection);

        List<String> sampleNos = new ArrayList<>();
        for (InspectionCreateRequest.SampleDto dto : request.getSamples()) {
            PiSample sample = new PiSample();
            sample.setInspectionId(inspection.getId());
            sample.setSampleNo(dto.getSampleNo().trim());
            sample.setSampleTime(dto.getSampleTime());
            sample.setSampler(dto.getSampler());
            sample.setRemark(dto.getRemark());
            sampleMapper.insert(sample);
            sampleNos.add(sample.getSampleNo());
        }

        evidenceService.record(PiChangeEvidence.ENTITY_INSPECTION, inspection.getId(),
                PiChangeEvidence.ACTION_CREATE, request.getRequestId(), request.getOperator(),
                null, inspection, "建立出池检验单" + inspection.getInspectionNo()
                        + "，品种=" + batch.getSpecies() + "，用途=" + request.getUsageCode()
                        + "，冻结阈值版本" + version.getVersionNo()
                        + "，登记样品" + sampleNos + "，检验项目" + rules.size() + "项");
        return inspection;
    }

    // ------------------------------------------------------------
    // 实验室录入：初检 / 复检（复检追加新轮次，不覆盖初检）
    // ------------------------------------------------------------

    /**
     * 实验室录入结果并生成放行建议：
     * PENDING 落 INITIAL 初检轮次；RETURNED 落 RETEST 复检轮次（originRoundId 指回原轮次）。
     * 每个样品的全部必检项目必须有值，否则一次性列出全部缺项拒绝；
     * 逐项按冻结阈值闭区间比对生成 PASS/FAIL；任一不合格则整单建议 FAIL。
     * requestId 重复回放首次结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitResults(Long inspectionId, LabSubmitRequest request) {
        PiInspection replay = replayIfPresent(request.getRequestId());
        if (replay != null) {
            return detailView(replay);
        }
        PiInspection inspection = getOrThrow(inspectionId);
        boolean retest = PiInspection.STATUS_RETURNED.equals(inspection.getStatus());
        if (!retest && !PiInspection.STATUS_PENDING.equals(inspection.getStatus())) {
            throw new IllegalArgumentException(
                    "当前检验单状态不能录入结果：" + inspection.getStatus()
                            + "（初检后不得改写，退回补检只能追加复检轮次）");
        }

        List<PiSample> samples = listSamples(inspection.getId());
        List<PiIndicatorRule> rules = ruleVersionService.listComboIndicators(
                inspection.getRuleVersionId(), inspection.getSpecies(), inspection.getUsageCode());
        Map<String, PiIndicatorRule> ruleByCode = new LinkedHashMap<>();
        for (PiIndicatorRule rule : rules) {
            ruleByCode.put(rule.getIndicatorCode(), rule);
        }
        Map<String, PiSample> sampleByNo = new LinkedHashMap<>();
        for (PiSample sample : samples) {
            sampleByNo.put(sample.getSampleNo(), sample);
        }

        // 解析入参：样品与指标必须属于本单，同轮同样品同指标不得重复
        Map<String, LabSubmitRequest.ResultInput> inputKey = new LinkedHashMap<>();
        for (LabSubmitRequest.ResultInput input : request.getItems()) {
            String sampleNo = input.getSampleNo().trim();
            String code = input.getIndicatorCode().trim();
            if (!sampleByNo.containsKey(sampleNo)) {
                throw new IllegalArgumentException("样品编号不属于本检验单：" + sampleNo);
            }
            if (!ruleByCode.containsKey(code)) {
                throw new IllegalArgumentException("检验项目不属于该品种+用途组合：" + code);
            }
            String key = sampleNo + "|" + code;
            if (inputKey.put(key, input) != null) {
                throw new IllegalArgumentException("同一样品同一指标重复录入：" + key);
            }
        }

        // 必检项目 × 样品 笛卡尔覆盖校验，缺项一次性列全
        List<String> missing = new ArrayList<>();
        for (PiSample sample : samples) {
            for (PiIndicatorRule rule : rules) {
                if (!Boolean.TRUE.equals(rule.getRequiredFlag())) {
                    continue;
                }
                if (!inputKey.containsKey(sample.getSampleNo() + "|" + rule.getIndicatorCode())) {
                    missing.add(sample.getSampleNo() + "/" + rule.getIndicatorName());
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("必检项目未录全，缺项：" + missing);
        }

        PiRound origin = latestRound(inspection.getId());
        PiRound round = new PiRound();
        round.setInspectionId(inspection.getId());
        round.setRoundNo(origin == null ? 1 : origin.getRoundNo() + 1);
        round.setRoundType(retest ? PiRound.TYPE_RETEST : PiRound.TYPE_INITIAL);
        round.setOriginRoundId(retest && origin != null ? origin.getId() : null);
        round.setLabSubmitter(request.getOperator());
        round.setLabSubmitTime(LocalDateTime.now());
        roundMapper.insert(round);

        boolean anyFail = false;
        List<PiResultItem> stored = new ArrayList<>();
        for (LabSubmitRequest.ResultInput input : request.getItems()) {
            PiIndicatorRule rule = ruleByCode.get(input.getIndicatorCode().trim());
            PiSample sample = sampleByNo.get(input.getSampleNo().trim());
            PiResultItem item = new PiResultItem();
            item.setRoundId(round.getId());
            item.setSampleId(sample.getId());
            item.setIndicatorCode(rule.getIndicatorCode());
            item.setIndicatorName(rule.getIndicatorName());
            item.setUnit(rule.getUnit());
            item.setLimitMin(rule.getMinValue());
            item.setLimitMax(rule.getMaxValue());
            item.setRequiredFlag(rule.getRequiredFlag());
            BigDecimal value = input.getValue();
            item.setResultValue(value);
            String judge = within(value, rule.getMinValue(), rule.getMaxValue())
                    ? PiResultItem.JUDGE_PASS : PiResultItem.JUDGE_FAIL;
            if (PiResultItem.JUDGE_FAIL.equals(judge)) {
                anyFail = true;
            }
            item.setJudge(judge);
            itemMapper.insert(item);
            stored.add(item);
        }

        String suggestion = anyFail ? PiInspection.SUGGEST_FAIL : PiInspection.SUGGEST_PASS;
        round.setSuggestion(suggestion);
        roundMapper.updateById(round);

        PiInspection before = copyOf(inspection);
        inspection.setStatus(PiInspection.STATUS_LAB_SUBMITTED);
        inspection.setSuggestion(suggestion);
        inspection.setSuggestionTime(LocalDateTime.now());
        inspectionMapper.updateById(inspection);

        appendConclusion(inspection.getId(), round.getId(),
                retest ? PiConclusionLog.ACTION_RETEST_SUBMIT : PiConclusionLog.ACTION_INITIAL_SUBMIT,
                suggestion, null, request.getOperator());

        evidenceService.record(PiChangeEvidence.ENTITY_ROUND, round.getId(),
                retest ? PiChangeEvidence.ACTION_RETEST : PiChangeEvidence.ACTION_SUBMIT,
                request.getRequestId(), request.getOperator(), before, inspection,
                (retest ? "复检（第" + round.getRoundNo() + "轮，追加不覆盖初检）" : "初检")
                        + "录入完成，系统比对阈值建议：" + suggestion
                        + (anyFail ? "，不合格项：" + failDescriptions(stored) : "，全部合格"));
        return detailView(inspection);
    }

    // ------------------------------------------------------------
    // 退回补检：保留原结论、原因、操作时间
    // ------------------------------------------------------------

    /**
     * 退回补检：仅实验室已录入（LAB_SUBMITTED）可退回。
     * 不删除任何结果行与结论；当次原结论（suggestion）、退回原因、操作人与时间
     * 逐行追加到 t_pi_conclusion_log（多次退回多次留痕，互不覆盖）；
     * 检验单头更新为最近一次退回事实，完整历史以结论日志为准。
     */
    @Transactional(rollbackFor = Exception.class)
    public PiInspection returnForRetest(Long inspectionId, ReturnRequest request) {
        PiInspection replay = replayIfPresent(request.getRequestId());
        if (replay != null) {
            return replay;
        }
        PiInspection inspection = getOrThrow(inspectionId);
        if (!PiInspection.STATUS_LAB_SUBMITTED.equals(inspection.getStatus())) {
            throw new IllegalArgumentException(
                    "只有实验室已录入的检验单才能退回补检，当前状态：" + inspection.getStatus());
        }
        PiInspection before = copyOf(inspection);
        String originalConclusion = inspection.getSuggestion();
        PiRound latest = latestRound(inspection.getId());

        inspection.setStatus(PiInspection.STATUS_RETURNED);
        inspection.setReturnReason(request.getReason().trim());
        inspection.setReturnBy(request.getOperator());
        inspection.setReturnTime(LocalDateTime.now());
        inspectionMapper.updateById(inspection);

        appendConclusion(inspection.getId(), latest == null ? null : latest.getId(),
                PiConclusionLog.ACTION_RETURN, originalConclusion,
                request.getReason().trim(), request.getOperator());

        evidenceService.record(PiChangeEvidence.ENTITY_INSPECTION, inspection.getId(),
                PiChangeEvidence.ACTION_RETURN, request.getRequestId(), request.getOperator(),
                before, inspection, "退回补检：保留原结论=" + originalConclusion
                        + "，退回原因=" + request.getReason().trim()
                        + "；初检/既有轮次结果不覆盖，等待复检追加");
        return inspection;
    }

    // ------------------------------------------------------------
    // 检测报告：与样品编号一一对应
    // ------------------------------------------------------------

    /**
     * 出具检测报告：一个样品只能出具一次报告（reportNo 全局唯一、样品内唯一）。
     * 重复请求幂等返回首次报告；报告出具时该样品必须已有检验结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public PiSample issueReport(Long inspectionId, ReportIssueRequest request) {
        PiInspection inspection = getOrThrow(inspectionId);
        PiSample sample = findSample(inspection.getId(), request.getSampleNo().trim());
        if (sample.getReportNo() != null) {
            if (sample.getReportNo().equals(request.getReportNo().trim())) {
                return sample; // 重试幂等：同样品同报告号
            }
            throw new IllegalArgumentException("样品" + sample.getSampleNo()
                    + "已出具报告" + sample.getReportNo() + "，检测报告与样品编号一一对应，不能重复出具");
        }
        Long sameReportNo = sampleMapper.selectCount(new QueryWrapper<PiSample>()
                .eq("report_no", request.getReportNo().trim()));
        if (sameReportNo != null && sameReportNo > 0) {
            throw new IllegalArgumentException("报告编号已被其他样品占用：" + request.getReportNo());
        }
        Long resultCount = itemMapper.selectCount(new QueryWrapper<PiResultItem>()
                .eq("sample_id", sample.getId()));
        if (resultCount == null || resultCount == 0) {
            throw new IllegalArgumentException("样品" + sample.getSampleNo() + "尚无检验结果，不能出具检测报告");
        }

        PiSample before = copyOfSample(sample);
        sample.setReportNo(request.getReportNo().trim());
        sample.setReportTime(LocalDateTime.now());
        sample.setReportIssuedBy(request.getOperator());
        sample.setRemark(request.getRemark());
        sampleMapper.updateById(sample);

        evidenceService.record(PiChangeEvidence.ENTITY_REPORT, sample.getId(),
                PiChangeEvidence.ACTION_REPORT, request.getRequestId(), request.getOperator(),
                before, sample, "样品" + sample.getSampleNo() + "出具检测报告"
                        + sample.getReportNo() + "，报告号与样品编号一一对应");
        return sample;
    }

    /** 按报告号反查：报告 ↔ 样品 ↔ 检验单一一对应。 */
    public Map<String, Object> getReport(String reportNo) {
        List<PiSample> samples = sampleMapper.selectList(
                new QueryWrapper<PiSample>().eq("report_no", reportNo));
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("检测报告不存在：" + reportNo);
        }
        PiSample sample = samples.get(0);
        PiInspection inspection = getOrThrow(sample.getInspectionId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reportNo", sample.getReportNo());
        view.put("reportTime", sample.getReportTime());
        view.put("reportIssuedBy", sample.getReportIssuedBy());
        view.put("sampleNo", sample.getSampleNo());
        view.put("inspectionNo", inspection.getInspectionNo());
        view.put("batchId", inspection.getBatchId());
        view.put("species", inspection.getSpecies());
        view.put("usageCode", inspection.getUsageCode());
        PiRound latest = latestRound(inspection.getId());
        if (latest != null) {
            final Long roundId = latest.getId();
            final Long sampleId = sample.getId();
            view.put("items", listItemsOfRound(roundId).stream()
                    .filter(it -> it.getSampleId().equals(sampleId))
                    .collect(java.util.stream.Collectors.toList()));
        } else {
            view.put("items", new ArrayList<>());
        }
        return view;
    }

    // ------------------------------------------------------------
    // 授权电子签名放行
    // ------------------------------------------------------------

    /**
     * 授权人员电子签名放行：
     * 1) 检验单必须实验室已录入且系统建议 PASS（FAIL 必须先退回补检、复检合格）；
     * 2) 签名人必须登记在册、启用，且签名口令正确——未授权/已禁用/口令错一律拒绝；
     * 3) 系统对检验单关键事实生成 HMAC-SHA256 电子签章落库；签名后批次才可进入发货。
     * requestId 重复回放已放行检验单。
     */
    @Transactional(rollbackFor = Exception.class)
    public PiInspection signRelease(Long inspectionId, ReleaseSignRequest request) {
        PiInspection replay = replayIfPresent(request.getRequestId());
        if (replay != null) {
            return replay;
        }
        PiInspection inspection = getOrThrow(inspectionId);
        if (PiInspection.STATUS_RELEASED.equals(inspection.getStatus())) {
            return inspection;
        }
        if (!PiInspection.STATUS_LAB_SUBMITTED.equals(inspection.getStatus())) {
            throw new IllegalArgumentException(
                    "只有实验室已录入、待放行的检验单才能签名，当前状态：" + inspection.getStatus());
        }
        // 先认证：签名人必须登记在册、启用，且口令正确
        PiSigner signer = signerMapper.selectOne(
                new QueryWrapper<PiSigner>().eq("signer_name", request.getSigner().trim()));
        if (signer == null || !Boolean.TRUE.equals(signer.getEnabled())) {
            throw new IllegalArgumentException("签名人未获授权或已被禁用：" + request.getSigner());
        }
        if (!signer.getSignSecret().equals(request.getSignSecret())) {
            throw new IllegalArgumentException("签名口令错误，电子签名被拒绝");
        }
        // 再裁决：系统建议 FAIL 不得放行，须退回补检并复检合格
        if (!PiInspection.SUGGEST_PASS.equals(inspection.getSuggestion())) {
            throw new IllegalArgumentException("系统建议不予放行（FAIL），不能签名放行；请退回补检并复检合格后再签");
        }

        PiRound latest = latestRound(inspection.getId());
        String payload = String.join("|",
                inspection.getInspectionNo(),
                String.valueOf(inspection.getBatchId()),
                inspection.getSpecies(),
                inspection.getUsageCode(),
                String.valueOf(inspection.getRuleVersionId()),
                String.valueOf(latest.getId()),
                inspection.getSuggestion());
        String signature = hmacSha256(signer.getSignSecret(), request.getRequestId() + "\n" + payload);

        PiInspection before = copyOf(inspection);
        inspection.setStatus(PiInspection.STATUS_RELEASED);
        inspection.setReleasedBy(signer.getSignerName());
        inspection.setReleasedTime(LocalDateTime.now());
        inspection.setReleaseRemark(request.getSignRemark().trim());
        inspection.setSignature(signature);
        inspectionMapper.updateById(inspection);

        appendConclusion(inspection.getId(), latest.getId(),
                PiConclusionLog.ACTION_RELEASE, inspection.getSuggestion(),
                request.getSignRemark().trim(), signer.getSignerName());

        evidenceService.record(PiChangeEvidence.ENTITY_INSPECTION, inspection.getId(),
                PiChangeEvidence.ACTION_RELEASE, request.getRequestId(), signer.getSignerName(),
                before, inspection, "授权电子签名放行，签名意见=" + request.getSignRemark().trim()
                        + "，签章=" + signature + "；批次可进入发货");
        return inspection;
    }

    // ------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------

    public PiInspection getOrThrow(Long id) {
        PiInspection inspection = inspectionMapper.selectById(id);
        if (inspection == null) {
            throw new IllegalArgumentException("出池检验单不存在，id=" + id);
        }
        return inspection;
    }

    public List<PiInspection> list(Long batchId, String status) {
        QueryWrapper<PiInspection> wrapper = new QueryWrapper<>();
        wrapper.eq(batchId != null, "batch_id", batchId);
        wrapper.eq(status != null && !status.trim().isEmpty(), "status", status);
        wrapper.orderByDesc("id");
        return inspectionMapper.selectList(wrapper);
    }

    /** 检验单详情：单头 + 样品（含报告号）+ 各轮次结果（初检/复检分行）+ 结论历史。 */
    public Map<String, Object> detail(Long inspectionId) {
        return detailView(getOrThrow(inspectionId));
    }

    /**
     * 发货/出池放行校验（供台账守卫只读调用）：
     * 从未建过检验单的批次不拦截（历史批次行为不变）；建了单但未签名放行一律拒绝。
     */
    public void requireReleasedForShipment(Long batchId, String batchNo) {
        List<PiInspection> all = inspectionMapper.selectList(new QueryWrapper<PiInspection>()
                .eq("batch_id", batchId).orderByDesc("id"));
        if (all.isEmpty()) {
            return;
        }
        PiInspection latest = all.get(0);
        if (!PiInspection.STATUS_RELEASED.equals(latest.getStatus())) {
            throw new IllegalArgumentException("批次" + batchNo + "出池检验未完成授权电子签名放行（检验单"
                    + latest.getInspectionNo() + "状态=" + latest.getStatus()
                    + "，建议=" + latest.getSuggestion() + "），禁止出池发货");
        }
    }

    /**
     * 海水养殖管理旧数据回放 + 新旧版本差异解释。
     * 旧模型：t_inspection_record 一条自由文本抽检（sampleCount/avgWeight/survivalRate/result），
     * 无检验项目、阈值、样品编号、报告、复检轮次与签名放行；
     * 新版本逐条列出差异并给出解释，旧记录原样保留、绝不回改，作为新版本上线前的回放基线。
     */
    public Map<String, Object> replayLegacy(Long batchId) {
        SeedlingBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("投苗批次不存在，id=" + batchId);
        }
        List<InspectionRecord> legacy = legacyInspectionMapper.selectList(
                new QueryWrapper<InspectionRecord>().eq("batch_id", batchId).orderByAsc("id"));
        List<PiInspection> fresh = inspectionMapper.selectList(new QueryWrapper<PiInspection>()
                .eq("batch_id", batchId).orderByAsc("id"));

        List<Map<String, String>> differences = new ArrayList<>();
        differences.add(diff("检验模型",
                "t_inspection_record 一条记录，result 为人工自由文本结论",
                "按品种+用途组合的阈值版本，逐检验项目结构化录入并冻结版本快照",
                "旧版抽检只描述长势与成活率，不承载出池放行；新版把检验项目与阈值版本化，结论可按同一口径复算"));
        differences.add(diff("阈值比对与放行建议",
                "无阈值、无系统判定，合格与否全凭 result 文本",
                "系统按闭区间[min,max]逐项比对，任一不合格整单建议 FAIL，全部合格建议 PASS",
                "新版放行建议由系统生成且不可人工篡改，避免旧版自由文本口径不一"));
        differences.add(diff("复检与初检",
                "复检只能再写一条抽检记录，与初检无关联，且旧行可被更新覆盖",
                "复检追加 RETEST 轮次（origin_round_id 指向原轮次），初检行与初检结论永不覆盖",
                "满足“复检不能覆盖初检”，每轮结果、判定、提交人、提交时间独立可查"));
        differences.add(diff("退回补检",
                "无退回流程，无原结论/原因/操作时间留痕",
                "退回必须填原因，原结论+原因+操作人+时间逐行写入结论历史，只追加",
                "满足“退回补检保留原结论、原因和操作时间”，多次退回多次留痕互不覆盖"));
        differences.add(diff("样品与检测报告",
                "只有 sample_count 一个抽样数量，无样品编号、无检测报告",
                "样品逐号登记，一个样品只能出具一份报告（报告号全局唯一），报告↔样品一一对应",
                "满足“检测报告和样品编号一一对应”，可按报告号反查样品、批次与逐项结果"));
        differences.add(diff("放行与电子签名",
                "出池不校验抽检结论，无授权、无签名",
                "仅授权登记且启用的签名人凭口令 HMAC 电子签名后，批次才允许出池进入发货",
                "满足“只有授权人员电子签名后批次才可进入发货”，未放行批次在台账出池入口直接拦截"));
        differences.add(diff("留证与幂等",
                "仅台账四审计字段，无业务变更证据链",
                "建单/初检/退回/复检/报告/签名全程写 t_pi_change_evidence，requestId 幂等回放",
                "所有动作含操作者、前后 JSON 快照与幂等请求号，可按对象或请求号追溯"));

        Map<String, Object> view = new LinkedHashMap<>();
        Map<String, Object> batchView = new LinkedHashMap<>();
        batchView.put("batchId", batch.getId());
        batchView.put("batchNo", batch.getBatchNo());
        batchView.put("species", batch.getSpecies());
        batchView.put("status", batch.getStatus());
        view.put("batch", batchView);
        view.put("legacyVersion", "aqua-1 台账抽检 t_inspection_record（自由文本，无阈值/报告/签名）");
        view.put("currentVersion", "aqua-13 批次出池检验与放行签名");
        view.put("legacyRecordCount", legacy.size());
        view.put("legacyRecords", legacy);
        view.put("newInspectionCount", fresh.size());
        List<Map<String, Object>> freshSummary = new ArrayList<>();
        for (PiInspection pi : fresh) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("inspectionNo", pi.getInspectionNo());
            s.put("status", pi.getStatus());
            s.put("suggestion", pi.getSuggestion());
            s.put("releasedBy", pi.getReleasedBy());
            s.put("releasedTime", pi.getReleasedTime());
            s.put("returnReason", pi.getReturnReason());
            freshSummary.add(s);
        }
        view.put("newInspections", freshSummary);
        view.put("differencesExplained", differences);
        boolean explained = differences.stream().allMatch(
                d -> d.get("explanation") != null && !d.get("explanation").trim().isEmpty());
        view.put("allDifferencesExplained", explained);
        view.put("replayConclusion", explained
                ? "旧数据可解释：旧版抽检记录原样保留不回改，差异均由 aqua-13 新流程承接，新版本可提交"
                : "存在无法解释的差异，新版本不可提交");
        return view;
    }

    private Map<String, String> diff(String aspect, String legacy, String current, String explanation) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("aspect", aspect);
        d.put("legacy", legacy);
        d.put("current", current);
        d.put("explanation", explanation);
        return d;
    }

    // ------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------

    private Map<String, Object> detailView(PiInspection inspection) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("inspection", inspection);
        view.put("samples", listSamples(inspection.getId()));
        List<PiRound> rounds = roundMapper.selectList(new QueryWrapper<PiRound>()
                .eq("inspection_id", inspection.getId()).orderByAsc("round_no"));
        List<Map<String, Object>> roundViews = new ArrayList<>();
        for (PiRound round : rounds) {
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("round", round);
            rv.put("items", listItemsOfRound(round.getId()));
            roundViews.add(rv);
        }
        view.put("rounds", roundViews);
        view.put("conclusionLogs", conclusionLogMapper.selectList(new QueryWrapper<PiConclusionLog>()
                .eq("inspection_id", inspection.getId()).orderByAsc("id")));
        return view;
    }

    private PiInspection replayIfPresent(String requestId) {
        PiChangeEvidence first = evidenceService.findByRequestId(requestId);
        if (first == null || !PiChangeEvidence.ENTITY_INSPECTION.equals(first.getEntityType())
                && !PiChangeEvidence.ENTITY_ROUND.equals(first.getEntityType())) {
            return null;
        }
        if (PiChangeEvidence.ENTITY_INSPECTION.equals(first.getEntityType())) {
            return inspectionMapper.selectById(first.getEntityId());
        }
        PiRound round = roundMapper.selectById(first.getEntityId());
        return round == null ? null : inspectionMapper.selectById(round.getInspectionId());
    }

    private void appendConclusion(Long inspectionId, Long roundId, String action,
                                  String conclusion, String reason, String operator) {
        PiConclusionLog log = new PiConclusionLog();
        log.setInspectionId(inspectionId);
        log.setRoundId(roundId);
        log.setAction(action);
        log.setConclusion(conclusion);
        log.setReason(reason);
        log.setOperator(operator);
        log.setOperateTime(LocalDateTime.now());
        conclusionLogMapper.insert(log);
    }

    private List<PiSample> listSamples(Long inspectionId) {
        return sampleMapper.selectList(
                new QueryWrapper<PiSample>().eq("inspection_id", inspectionId).orderByAsc("id"));
    }

    private PiSample findSample(Long inspectionId, String sampleNo) {
        List<PiSample> samples = sampleMapper.selectList(new QueryWrapper<PiSample>()
                .eq("inspection_id", inspectionId).eq("sample_no", sampleNo));
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("检验单下无该样品编号：" + sampleNo);
        }
        return samples.get(0);
    }

    private PiRound latestRound(Long inspectionId) {
        List<PiRound> rounds = roundMapper.selectList(new QueryWrapper<PiRound>()
                .eq("inspection_id", inspectionId).orderByDesc("round_no").last("LIMIT 1"));
        return rounds.isEmpty() ? null : rounds.get(0);
    }

    private List<PiResultItem> listItemsOfRound(Long roundId) {
        return itemMapper.selectList(
                new QueryWrapper<PiResultItem>().eq("round_id", roundId).orderByAsc("id"));
    }

    private boolean within(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (min != null && value.compareTo(min) < 0) {
            return false;
        }
        return max == null || value.compareTo(max) <= 0;
    }

    private List<String> failDescriptions(List<PiResultItem> items) {
        List<String> fails = new ArrayList<>();
        for (PiResultItem item : items) {
            if (PiResultItem.JUDGE_FAIL.equals(item.getJudge())) {
                fails.add(item.getIndicatorName() + "=" + item.getResultValue()
                        + (item.getUnit() == null ? "" : item.getUnit())
                        + "，阈值[" + item.getLimitMin() + "," + item.getLimitMax() + "]");
            }
        }
        return fails;
    }

    private String nextInspectionNo() {
        Long count = inspectionMapper.selectCount(new QueryWrapper<>());
        return String.format("PI-%06d", (count == null ? 0 : count) + 1);
    }

    private PiInspection copyOf(PiInspection source) {
        PiInspection copy = new PiInspection();
        copy.setId(source.getId());
        copy.setInspectionNo(source.getInspectionNo());
        copy.setBatchId(source.getBatchId());
        copy.setPondId(source.getPondId());
        copy.setSpecies(source.getSpecies());
        copy.setUsageCode(source.getUsageCode());
        copy.setRuleVersionId(source.getRuleVersionId());
        copy.setInspectDate(source.getInspectDate());
        copy.setStatus(source.getStatus());
        copy.setReturnReason(source.getReturnReason());
        copy.setReturnBy(source.getReturnBy());
        copy.setReturnTime(source.getReturnTime());
        copy.setSuggestion(source.getSuggestion());
        copy.setSuggestionTime(source.getSuggestionTime());
        copy.setReleasedBy(source.getReleasedBy());
        copy.setReleasedTime(source.getReleasedTime());
        copy.setReleaseRemark(source.getReleaseRemark());
        copy.setSignature(source.getSignature());
        return copy;
    }

    private PiSample copyOfSample(PiSample source) {
        PiSample copy = new PiSample();
        copy.setId(source.getId());
        copy.setInspectionId(source.getInspectionId());
        copy.setSampleNo(source.getSampleNo());
        copy.setSampleTime(source.getSampleTime());
        copy.setSampler(source.getSampler());
        copy.setReportNo(source.getReportNo());
        copy.setReportTime(source.getReportTime());
        copy.setReportIssuedBy(source.getReportIssuedBy());
        copy.setRemark(source.getRemark());
        return copy;
    }

    private String hmacSha256(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("电子签章生成失败", ex);
        }
    }
}
