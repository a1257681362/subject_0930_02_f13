package com.evops.aqua.release.dto;

import javax.validation.constraints.NotBlank;

/**
 * 退回补检请求：原因必填。退回不删除初检——初检结论、退回原因与操作时间
 * 永久保留在结论历史与检验单上，复检只追加新轮次。
 */
public class ReturnRequest {

    @NotBlank(message = "退回原因不能为空")
    private String reason;

    @NotBlank(message = "操作人不能为空")
    private String operator;

    @NotBlank(message = "请求号不能为空")
    private String requestId;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
