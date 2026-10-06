package com.evops.aqua.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 批次出池检验与放行签名验收（aqua-13）。
 * 主线：先回放海水养殖管理旧版自由文本抽检 → 差异全部可解释 →
 * 按品种+用途发布阈值版本 → 建单登记样品（冻结版本）→ 实验室录入、系统比对阈值给放行建议
 * → 检测报告与样品一一对应 → 未签名放行禁止出池、FAIL 不得签名 →
 * 退回补检保留原结论/原因/操作时间 → 复检追加不覆盖初检 → 授权人员电子签名 → 批次出池进入发货；
 * 无检验单的历史批次行为不变；全程留证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReleaseInspectionAcceptanceTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;

    private final List<String> report = new ArrayList<>();
    private String sessionCookie;

    private Long pond1;
    private Long pond2;
    private Long batch1;
    private Long batch2;
    private Long ruleVersion1;
    private Long inspection1;
    private Long signerQian;

    @Test
    void acceptanceReleaseInspection() {
        checkpoint("CP0", "登录认证", () -> {
            Map<String, Object> body = new HashMap<>();
            body.put("username", "bootstrap");
            body.put("password", "bootstrap");
            ResponseEntity<String> resp = call(HttpMethod.POST, "/api/auth/login", body);
            assertSuccess(json(resp), "登录");
            List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
            check(cookies != null && !cookies.isEmpty(), "登录成功但未下发会话Cookie");
            sessionCookie = cookies.get(0).split(";")[0];
        });

        checkpoint("CP1", "建池投苗，并先登记旧版自由文本抽检（旧数据回放基线）", () -> {
            pond1 = createPond("PI-P1", "出池检验一号池");
            pond2 = createPond("PI-P2", "历史兼容二号池");
            batch1 = stockBatch("PI-C01", pond1);
            batch2 = stockBatch("PI-C02", pond2);

            Map<String, Object> legacy = new HashMap<>();
            legacy.put("pondId", pond1);
            legacy.put("batchId", batch1);
            legacy.put("inspectDate", "2026-10-01");
            legacy.put("sampleCount", 3);
            legacy.put("avgWeightG", new BigDecimal("352.50"));
            legacy.put("survivalRate", new BigDecimal("96.20"));
            legacy.put("result", "目测合格，可以出池");
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/records/inspection", legacy));
            assertSuccess(resp, "登记旧版抽检记录");
        });

        checkpoint("CP2", "旧数据回放：旧版抽检原样回放，新旧差异逐条且全部可解释", () -> {
            JsonNode replay = json(call(HttpMethod.GET,
                    "/api/aqua/release/batches/" + batch1 + "/legacy-replay", null));
            assertSuccess(replay, "旧数据回放");
            check(replay.path("data").path("legacyRecordCount").asInt() == 1,
                    "旧版抽检记录应回放1条，实际=" + replay.path("data").path("legacyRecordCount").asInt());
            JsonNode firstLegacy = replay.path("data").path("legacyRecords").get(0);
            check("目测合格，可以出池".equals(firstLegacy.path("result").asText()),
                    "旧记录自由文本结论应原样保留");
            check(replay.path("data").path("allDifferencesExplained").asBoolean(false),
                    "所有差异必须给出解释后才允许提交新版本");
            check(replay.path("data").path("differencesExplained").size() == 7,
                    "应列出7项新旧差异");
            replay.path("data").path("differencesExplained").forEach(d -> {
                check(!d.path("aspect").asText().isEmpty()
                                && !d.path("legacy").asText().isEmpty()
                                && !d.path("current").asText().isEmpty()
                                && !d.path("explanation").asText().isEmpty(),
                        "差异的维度/旧版/新版/解释四要素缺一不可");
            });
        });

        checkpoint("CP3", "按品种+用途组合发布阈值版本；requestId幂等；旧版本自动停用", () -> {
            Map<String, Object> req = publishBody("PI-RULE-V1", "rule-v1", "品控经理", Arrays.asList(
                    combo("LARGE_YELLOW_CROAKER", "RAW_SALE", "鲜活销售", Arrays.asList(
                            indicator("CHLOR", "氯霉素", "μg/kg", null, "0.1"),
                            indicator("VBN", "挥发性盐基氮", "mg/100g", "0", "30"),
                            indicator("COLIFORM", "大肠菌群", "CFU/g", "0", "100"))),
                    combo("LARGE_YELLOW_CROAKER", "PROCESSED", "加工原料", Arrays.asList(
                            indicator("CHLOR", "氯霉素", "μg/kg", null, "0.1"),
                            indicator("VBN", "挥发性盐基氮", "mg/100g", "0", "40")))));
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/release/rule-versions", req));
            assertSuccess(resp, "发布阈值版本V1");
            ruleVersion1 = resp.path("data").path("id").asLong();
            check("ACTIVE".equals(resp.path("data").path("status").asText()), "新版本应为ACTIVE");

            JsonNode again = json(call(HttpMethod.POST, "/api/aqua/release/rule-versions", req));
            assertSuccess(again, "重复发布版本V1");
            check(again.path("data").path("id").asLong() == ruleVersion1,
                    "同版本号重复发布应返回首次版本（幂等）");

            Map<String, Object> v2 = publishBody("PI-RULE-V2", "rule-v2", "品控经理", Arrays.asList(
                    combo("LARGE_YELLOW_CROAKER", "RAW_SALE", "鲜活销售", Arrays.asList(
                            indicator("CHLOR", "氯霉素", "μg/kg", null, "0.05")))));
            JsonNode resp2 = json(call(HttpMethod.POST, "/api/aqua/release/rule-versions", v2));
            assertSuccess(resp2, "发布阈值版本V2");
            check("ACTIVE".equals(resp2.path("data").path("status").asText()), "V2应为ACTIVE");
            JsonNode list = json(call(HttpMethod.GET, "/api/aqua/release/rule-versions", null));
            JsonNode v1Now = null;
            for (JsonNode v : list.path("data")) {
                if (v.path("id").asLong() == ruleVersion1) {
                    v1Now = v;
                }
            }
            check(v1Now != null && "SUPERSEDED".equals(v1Now.path("status").asText()),
                    "新版本发布后旧版本应自动停用");
        });

        checkpoint("CP4", "登记授权电子签名人；未授权/禁用人员不能签名", () -> {
            Map<String, Object> qian = new HashMap<>();
            qian.put("signerName", "钱放行");
            qian.put("signSecret", "release-secret-01");
            qian.put("remark", "质量授权签字人");
            qian.put("requestId", "signer-qian");
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/release/signers", qian));
            assertSuccess(resp, "登记授权签名人钱放行");
            signerQian = resp.path("data").path("id").asLong();

            JsonNode again = json(call(HttpMethod.POST, "/api/aqua/release/signers", qian));
            assertSuccess(again, "重复登记签名人");
            check(again.path("data").path("id").asLong() == signerQian, "重复登记应幂等返回同一签名人");
        });

        checkpoint("CP5", "建出池检验单：冻结品种/用途/阈值版本与样品；同批重复建单拒绝；requestId幂等", () -> {
            // V2 的 RAW_SALE 只配了 CHLOR 一项，用 V1 组合校验建单冻结的是当前生效版本——
            // 此处改用用途 PROCESSED（V2 未配置该组合）验证无组合拒绝
            Map<String, Object> noCombo = createBody(batch1, "PROCESSED", "2026-10-05",
                    "create-nocombo", "质检员", samples("S1", "S2"));
            JsonNode denied = json(call(HttpMethod.POST, "/api/aqua/release/inspections", noCombo));
            check(!denied.path("success").asBoolean(true),
                    "生效版本V2未配置大黄鱼PROCESSED组合，建单应被拒绝");

            // V2 RAW_SALE 仅有 CHLOR 一个必检项；用它走主流程，阈值上限 0.05
            Map<String, Object> req = createBody(batch1, "RAW_SALE", "2026-10-05",
                    "pi-create-1", "质检员", samples("S1", "S2"));
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/release/inspections", req));
            assertSuccess(resp, "建立检验单");
            inspection1 = resp.path("data").path("id").asLong();
            check("PENDING".equals(resp.path("data").path("status").asText()), "新单应为PENDING");
            check(resp.path("data").path("ruleVersionId").asLong() != ruleVersion1,
                    "新单应冻结当时生效版本V2，而非V1");

            JsonNode again = json(call(HttpMethod.POST, "/api/aqua/release/inspections", req));
            assertSuccess(again, "同requestId重复建单");
            check(again.path("data").path("id").asLong() == inspection1,
                    "同requestId应回放首次检验单，不产生第二张单");

            Map<String, Object> dup = createBody(batch1, "RAW_SALE", "2026-10-05",
                    "pi-create-2", "质检员", samples("S3"));
            JsonNode denied2 = json(call(HttpMethod.POST, "/api/aqua/release/inspections", dup));
            check(!denied2.path("success").asBoolean(true), "同批未完结检验单存在时应拒绝再建");
        });

        checkpoint("CP6", "实验室录入初检：必检项缺项一次性拒绝；超标项系统判FAIL并生成不予放行建议", () -> {
            // 只录 S1，缺 S2 的必检项
            Map<String, Object> incomplete = labBody("lab-initial-incomplete", "实验员小李",
                    Arrays.asList(result("S1", "CHLOR", "0.20")));
            JsonNode denied = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/lab-submit", incomplete));
            check(!denied.path("success").asBoolean(true), "必检项未录全应拒绝");
            check(denied.path("message").asText().contains("S2"),
                    "拒绝信息应一次性列出缺项样品，实际=" + denied.path("message").asText());

            Map<String, Object> initial = labBody("lab-initial-1", "实验员小李", Arrays.asList(
                    result("S1", "CHLOR", "0.20"),   // 超过 V2 上限 0.05
                    result("S2", "CHLOR", "0.02")));  // 合格
            JsonNode resp = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/lab-submit", initial));
            assertSuccess(resp, "初检录入");
            check("LAB_SUBMITTED".equals(resp.path("data").path("inspection").path("status").asText()),
                    "录入后状态应为LAB_SUBMITTED");
            check("FAIL".equals(resp.path("data").path("inspection").path("suggestion").asText()),
                    "存在超标项应建议FAIL");
            JsonNode rounds = resp.path("data").path("rounds");
            check(rounds.size() == 1 && "INITIAL".equals(rounds.get(0).path("round").path("roundType").asText()),
                    "首录应生成INITIAL轮次");
            JsonNode items = rounds.get(0).path("items");
            String s1Judge = null;
            String s2Judge = null;
            for (JsonNode it : items) {
                if (it.path("indicatorCode").asText().equals("CHLOR")
                        && new BigDecimal("0.20").compareTo(it.path("resultValue").decimalValue()) == 0) {
                    s1Judge = it.path("judge").asText();
                }
                if (it.path("indicatorCode").asText().equals("CHLOR")
                        && new BigDecimal("0.02").compareTo(it.path("resultValue").decimalValue()) == 0) {
                    s2Judge = it.path("judge").asText();
                }
            }
            check("FAIL".equals(s1Judge) && "PASS".equals(s2Judge),
                    "系统应按冻结阈值逐项比对：S1=FAIL，S2=PASS，实际 S1=" + s1Judge + "，S2=" + s2Judge);
        });

        checkpoint("CP7", "检测报告与样品编号一一对应：一出一报告，重试幂等，换号/占用均拒绝", () -> {
            Map<String, Object> r1 = reportBody("S1", "RPT-S1-0001", "report-s1-1", "报告员");
            JsonNode ok = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/reports", r1));
            assertSuccess(ok, "S1出具报告");
            check("RPT-S1-0001".equals(ok.path("data").path("reportNo").asText()), "报告号应冻结到S1");

            JsonNode again = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/reports", r1));
            assertSuccess(again, "同样品同报告号重试");
            check("RPT-S1-0001".equals(again.path("data").path("reportNo").asText()),
                    "重试应幂等返回首次报告");

            Map<String, Object> otherNo = reportBody("S1", "RPT-S1-0002", "report-s1-2", "报告员");
            JsonNode denied = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/reports", otherNo));
            check(!denied.path("success").asBoolean(true), "同一样品不能出具第二份报告");

            Map<String, Object> occupied = reportBody("S2", "RPT-S1-0001", "report-s2-1", "报告员");
            JsonNode denied2 = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/reports", occupied));
            check(!denied2.path("success").asBoolean(true), "报告号被其他样品占用应拒绝");

            JsonNode view = json(call(HttpMethod.GET,
                    "/api/aqua/release/reports/RPT-S1-0001", null));
            assertSuccess(view, "按报告号反查");
            check("S1".equals(view.path("data").path("sampleNo").asText()),
                    "报告反查应唯一定位到样品S1");
        });

        checkpoint("CP8", "未签名放行禁止出池发货；系统建议FAIL时即使授权人也不能签名", () -> {
            Map<String, Object> harvest = new HashMap<>();
            harvest.put("harvestDate", "2026-10-06");
            harvest.put("quantity", 1000);
            JsonNode deniedHarvest = json(call(HttpMethod.POST,
                    "/api/aqua/batches/" + batch1 + "/harvest", harvest));
            check(!deniedHarvest.path("success").asBoolean(true),
                    "未完成电子签名放行的批次应禁止出池");

            Map<String, Object> signFail = signBody("钱放行", "release-secret-01", "同意放行", "sign-fail-1");
            JsonNode deniedSign = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/release", signFail));
            check(!deniedSign.path("success").asBoolean(true),
                    "建议FAIL时授权签名也必须被拒绝");
        });

        checkpoint("CP9", "退回补检：原因必填；原结论FAIL、退回原因与操作时间完整保留", () -> {
            Map<String, Object> noReason = new HashMap<>();
            noReason.put("reason", "   ");
            noReason.put("operator", "主管孙复核");
            noReason.put("requestId", "return-no-reason");
            JsonNode denied = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/return", noReason));
            check(!denied.path("success").asBoolean(true), "退回原因缺失应被参数校验拒绝");

            Map<String, Object> req = new HashMap<>();
            req.put("reason", "S1氯霉素初检超标，退回实验室重新取样复检");
            req.put("operator", "主管孙复核");
            req.put("requestId", "return-1");
            JsonNode resp = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/return", req));
            assertSuccess(resp, "退回补检");
            check("RETURNED".equals(resp.path("data").path("status").asText()), "退回后状态应为RETURNED");
            check(resp.path("data").path("returnReason").asText().contains("S1氯霉素"),
                    "检验单应保留退回原因");
            check(resp.path("data").path("returnTime").asText() != null
                            && !resp.path("data").path("returnTime").asText().isEmpty(),
                    "退回操作时间必须保留");

            JsonNode detail = json(call(HttpMethod.GET,
                    "/api/aqua/release/inspections/" + inspection1, null));
            JsonNode logs = detail.path("data").path("conclusionLogs");
            String initialConclusion = null;
            String returnConclusion = null;
            String returnReason = null;
            for (JsonNode log : logs) {
                if ("INITIAL_SUBMIT".equals(log.path("action").asText())) {
                    initialConclusion = log.path("conclusion").asText();
                }
                if ("RETURN".equals(log.path("action").asText())) {
                    returnConclusion = log.path("conclusion").asText();
                    returnReason = log.path("reason").asText();
                }
            }
            check("FAIL".equals(initialConclusion), "结论历史必须保留初检原结论FAIL");
            check("FAIL".equals(returnConclusion) && returnReason != null && returnReason.contains("S1氯霉素"),
                    "退回事件必须原样保留被退回时的结论FAIL与原因，实际结论=" + returnConclusion);
        });

        checkpoint("CP10", "复检只追加不覆盖初检：新轮次指回原轮次，初检超标值原样在，复检合格转PASS", () -> {
            Map<String, Object> retest = labBody("lab-retest-1", "实验员周复检", Arrays.asList(
                    result("S1", "CHLOR", "0.01"),
                    result("S2", "CHLOR", "0.01")));
            JsonNode resp = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/lab-submit", retest));
            assertSuccess(resp, "复检录入");
            check("PASS".equals(resp.path("data").path("inspection").path("suggestion").asText()),
                    "复检全部合格应建议PASS");
            JsonNode rounds = resp.path("data").path("rounds");
            check(rounds.size() == 2, "应有初检+复检两个轮次，实际=" + rounds.size());
            JsonNode r1 = rounds.get(0).path("round");
            JsonNode r2 = rounds.get(1).path("round");
            check("INITIAL".equals(r1.path("roundType").asText())
                            && "RETEST".equals(r2.path("roundType").asText())
                            && r2.path("roundNo").asInt() == 2
                            && r2.path("originRoundId").asLong() == r1.path("id").asLong(),
                    "复检应为第2轮且originRoundId指向初检轮次");

            // 初检行原样保留：S1 初检 0.20/FAIL 仍在第1轮
            BigDecimal initialS1 = null;
            String initialJudge = null;
            for (JsonNode it : rounds.get(0).path("items")) {
                if (new BigDecimal("0.20").compareTo(it.path("resultValue").decimalValue()) == 0) {
                    initialS1 = it.path("resultValue").decimalValue();
                    initialJudge = it.path("judge").asText();
                }
            }
            check(initialS1 != null && "FAIL".equals(initialJudge),
                    "初检原值0.20与FAIL判定必须保留，复检不得覆盖初检");

            // 同 requestId 重放：不产生第3轮
            JsonNode replay = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/lab-submit", retest));
            assertSuccess(replay, "复检请求重放");
            check(replay.path("data").path("rounds").size() == 2,
                    "同requestId重放不得再追加轮次");
        });

        checkpoint("CP11", "授权电子签名：未登记人/错口令/被禁用均拒绝；授权成功生成签章并放行，重复签名幂等", () -> {
            Map<String, Object> unknown = signBody("冒签人", "x", "同意", "sign-unknown");
            check(!json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/release", unknown))
                    .path("success").asBoolean(true), "未登记授权人员签名必须拒绝");

            Map<String, Object> wrongPwd = signBody("钱放行", "wrong-secret", "同意", "sign-wrong");
            check(!json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/release", wrongPwd))
                    .path("success").asBoolean(true), "签名口令错误必须拒绝");

            // 禁用授权人后签名被拒
            call(HttpMethod.POST, "/api/aqua/release/signers/" + signerQian
                    + "/toggle?enabled=false&operator=品控经理", null);
            Map<String, Object> disabledSign = signBody("钱放行", "release-secret-01", "同意", "sign-disabled");
            JsonNode deniedDisabled = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/release", disabledSign));
            check(!deniedDisabled.path("success").asBoolean(true), "已禁用授权人签名必须拒绝");
            call(HttpMethod.POST, "/api/aqua/release/signers/" + signerQian
                    + "/toggle?enabled=true&operator=品控经理", null);

            Map<String, Object> ok = signBody("钱放行", "release-secret-01",
                    "复检合格，同意放行出池", "sign-ok-1");
            JsonNode resp = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/release", ok));
            assertSuccess(resp, "授权电子签名放行");
            check("RELEASED".equals(resp.path("data").path("status").asText()), "签名后状态应为RELEASED");
            check("钱放行".equals(resp.path("data").path("releasedBy").asText())
                            && resp.path("data").path("releasedTime").asText().length() > 0,
                    "必须记录签名人与签名时间");
            String signature = resp.path("data").path("signature").asText();
            check(signature.matches("[0-9a-f]{64}"),
                    "电子签章应为HMAC-SHA256的64位十六进制串，实际=" + signature);

            JsonNode again = json(call(HttpMethod.POST,
                    "/api/aqua/release/inspections/" + inspection1 + "/release", ok));
            assertSuccess(again, "重复签名幂等");
            check(again.path("data").path("signature").asText().equals(signature),
                    "重复签名不得重新签章");
        });

        checkpoint("CP12", "签名放行后批次才允许出池进入发货；从未建检验单的历史批次行为不变", () -> {
            Map<String, Object> harvest1 = new HashMap<>();
            harvest1.put("harvestDate", "2026-10-06");
            harvest1.put("quantity", 1000);
            JsonNode ok = json(call(HttpMethod.POST,
                    "/api/aqua/batches/" + batch1 + "/harvest", harvest1));
            assertSuccess(ok, "已签名放行批次出池");

            // 批次追溯确认已封存为 HARVESTED
            JsonNode trace = json(call(HttpMethod.GET, "/api/aqua/batches/" + batch1 + "/trace", null));
            assertSuccess(trace, "批次追溯");
            check("HARVESTED".equals(trace.path("data").path("batch").path("status").asText()),
                    "放行出池后批次应封存为HARVESTED，实际="
                            + trace.path("data").path("batch").path("status").asText());

            Map<String, Object> harvest2 = new HashMap<>();
            harvest2.put("harvestDate", "2026-10-06");
            harvest2.put("quantity", 2000);
            JsonNode ok2 = json(call(HttpMethod.POST,
                    "/api/aqua/batches/" + batch2 + "/harvest", harvest2));
            assertSuccess(ok2, "从未建检验单的历史批次出池行为应保持不变（守卫不拦截）");
        });

        checkpoint("CP13", "全程留证：建单/初检/报告/退回/复检/签名证据链完整，带操作者可按requestId追溯", () -> {
            JsonNode ev = json(call(HttpMethod.GET,
                    "/api/aqua/release/evidence?entityType=INSPECTION&entityId=" + inspection1, null));
            assertSuccess(ev, "按检验单查证据");
            List<String> actions = new ArrayList<>();
            ev.path("data").forEach(e -> {
                actions.add(e.path("action").asText());
                check(!e.path("operator").asText().isEmpty(), "每条证据必须带操作者");
            });
            check(actions.containsAll(Arrays.asList("CREATE", "RETURN", "RELEASE")),
                    "检验单应可追溯建单/退回/签名，实际=" + actions);

            JsonNode roundEv = json(call(HttpMethod.GET,
                    "/api/aqua/release/evidence?entityType=ROUND", null));
            assertSuccess(roundEv, "按轮次查证据");
            boolean hasInitial = false;
            boolean hasRetest = false;
            for (JsonNode e : roundEv.path("data")) {
                hasInitial |= "SUBMIT".equals(e.path("action").asText());
                hasRetest |= "RETEST".equals(e.path("action").asText());
            }
            check(hasInitial && hasRetest, "初检SUBMIT与复检RETEST证据均应存在");

            JsonNode byReq = json(call(HttpMethod.GET,
                    "/api/aqua/release/evidence?requestId=return-1", null));
            assertSuccess(byReq, "按requestId追溯退回证据");
            check(byReq.path("data").size() == 1
                            && "RETURN".equals(byReq.path("data").get(0).path("action").asText())
                            && byReq.path("data").get(0).path("beforeJson").asText().contains("LAB_SUBMITTED"),
                    "退回证据应携带操作前快照");
        });

        report.add("验收结论：全部检查点通过——旧版自由文本抽检可回放且7项差异全部可解释；"
                + "检验项目按品种+用途组合版本化并在新单冻结；实验室录入系统按阈值逐项比对生成放行建议；"
                + "检测报告与样品编号一一对应；未授权/未签名一律无法出池，FAIL不得放行；"
                + "退回补检保留原结论原因与操作时间；复检追加不覆盖初检；授权电子签名后批次才进入发货；全程留证。");
        printReport();
    }

    // ------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------

    private Long createPond(String code, String name) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("pondCode", code);
        body.put("pondName", name);
        body.put("seaArea", "东山湾海区");
        body.put("waterDepthM", "2.00");
        body.put("waterAreaM2", "1500");
        body.put("salinityPpt", "28.5");
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/ponds", body));
        assertSuccess(resp, "建池[" + code + "]");
        return resp.path("data").path("id").asLong();
    }

    private Long stockBatch(String batchNo, Long pondId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("batchNo", batchNo);
        body.put("pondId", pondId);
        body.put("species", "LARGE_YELLOW_CROAKER");
        body.put("seedSource", "东山湾苗种场");
        body.put("quantity", 50000);
        body.put("stockDate", "2026-04-01");
        body.put("plannedHarvestDate", "2026-10-10");
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/batches", body));
        assertSuccess(resp, "投苗[" + batchNo + "]");
        return resp.path("data").path("id").asLong();
    }

    private Map<String, Object> indicator(String code, String name, String unit,
                                          String min, String max) {
        Map<String, Object> m = new HashMap<>();
        m.put("indicatorCode", code);
        m.put("indicatorName", name);
        m.put("unit", unit);
        if (min != null) {
            m.put("minValue", min);
        }
        if (max != null) {
            m.put("maxValue", max);
        }
        m.put("requiredFlag", true);
        return m;
    }

    private Map<String, Object> combo(String species, String usageCode, String usageName,
                                      List<Map<String, Object>> indicators) {
        Map<String, Object> m = new HashMap<>();
        m.put("species", species);
        m.put("usageCode", usageCode);
        m.put("usageName", usageName);
        m.put("indicators", indicators);
        return m;
    }

    private Map<String, Object> publishBody(String versionNo, String requestId, String operator,
                                            List<Map<String, Object>> combos) {
        Map<String, Object> m = new HashMap<>();
        m.put("versionNo", versionNo);
        m.put("remark", "出池检验阈值" + versionNo);
        m.put("operator", operator);
        m.put("requestId", requestId);
        m.put("combos", combos);
        return m;
    }

    private List<Map<String, Object>> samples(String... nos) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String no : nos) {
            Map<String, Object> s = new HashMap<>();
            s.put("sampleNo", no);
            s.put("sampleTime", "2026-10-05T08:30:00");
            s.put("sampler", "采样员");
            list.add(s);
        }
        return list;
    }

    private Map<String, Object> createBody(Long batchId, String usageCode, String inspectDate,
                                           String requestId, String operator,
                                           List<Map<String, Object>> samples) {
        Map<String, Object> m = new HashMap<>();
        m.put("batchId", batchId);
        m.put("inspectDate", inspectDate);
        m.put("usageCode", usageCode);
        m.put("operator", operator);
        m.put("requestId", requestId);
        m.put("samples", samples);
        return m;
    }

    private Map<String, Object> result(String sampleNo, String indicatorCode, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("sampleNo", sampleNo);
        m.put("indicatorCode", indicatorCode);
        m.put("value", value);
        return m;
    }

    private Map<String, Object> labBody(String requestId, String operator,
                                        List<Map<String, Object>> items) {
        Map<String, Object> m = new HashMap<>();
        m.put("operator", operator);
        m.put("requestId", requestId);
        m.put("items", items);
        return m;
    }

    private Map<String, Object> reportBody(String sampleNo, String reportNo, String requestId,
                                           String operator) {
        Map<String, Object> m = new HashMap<>();
        m.put("sampleNo", sampleNo);
        m.put("reportNo", reportNo);
        m.put("operator", operator);
        m.put("requestId", requestId);
        return m;
    }

    private Map<String, Object> signBody(String signer, String secret, String remark,
                                         String requestId) {
        Map<String, Object> m = new HashMap<>();
        m.put("signer", signer);
        m.put("signSecret", secret);
        m.put("signRemark", remark);
        m.put("requestId", requestId);
        return m;
    }

    private void checkpoint(String id, String name, CheckpointBody body) {
        try {
            body.run();
            String line = "通过 " + id + " " + name;
            report.add(line);
            System.out.println("[验收] " + line);
        } catch (Throwable t) {
            String stop = "停点 " + id + "「" + name + "」失败，原因：" + t.getMessage();
            report.add(stop);
            report.add("验收结论：未通过，后续检查点未执行");
            printReport();
            fail("验收" + stop);
        }
    }

    private void printReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== 批次出池检验与放行签名验收报告 ==========\n");
        for (String line : report) {
            sb.append(line).append('\n');
        }
        sb.append("====================================================\n");
        System.out.println(sb);
    }

    private ResponseEntity<String> call(HttpMethod method, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sessionCookie != null) {
            headers.add(HttpHeaders.COOKIE, sessionCookie);
        }
        return rest.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private void assertSuccess(JsonNode node, String what) {
        if (!node.path("success").asBoolean(false)) {
            throw new IllegalStateException(what + "接口返回失败：" + node.path("message").asText());
        }
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private interface CheckpointBody {
        void run() throws Exception;
    }
}
