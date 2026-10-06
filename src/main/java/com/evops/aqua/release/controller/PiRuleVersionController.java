package com.evops.aqua.release.controller;

import com.evops.aqua.release.dto.PiRuleVersionPublishRequest;
import com.evops.aqua.release.entity.PiIndicatorRule;
import com.evops.aqua.release.entity.PiRuleVersion;
import com.evops.aqua.release.service.PiRuleVersionService;
import com.evops.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 出池检验阈值版本：检验项目按品种+用途组合配置；新版本发布后旧版本自动停用。
 */
@RestController
@RequestMapping("/api/aqua/release")
public class PiRuleVersionController {

    private final PiRuleVersionService ruleVersionService;

    public PiRuleVersionController(PiRuleVersionService ruleVersionService) {
        this.ruleVersionService = ruleVersionService;
    }

    @PostMapping("/rule-versions")
    public ApiResponse<PiRuleVersion> publish(
            @Validated @RequestBody PiRuleVersionPublishRequest request) {
        return ApiResponse.ok(ruleVersionService.publish(request));
    }

    @GetMapping("/rule-versions")
    public ApiResponse<List<PiRuleVersion>> list() {
        return ApiResponse.ok(ruleVersionService.listVersions());
    }

    /** 查某版本下品种+用途组合的检验项目与阈值。 */
    @GetMapping("/rule-versions/{id}/indicators")
    public ApiResponse<List<PiIndicatorRule>> indicators(@PathVariable Long id,
                                                         @RequestParam String species,
                                                         @RequestParam String usageCode) {
        return ApiResponse.ok(ruleVersionService.listComboIndicators(id, species, usageCode));
    }
}
