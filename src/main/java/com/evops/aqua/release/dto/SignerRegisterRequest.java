package com.evops.aqua.release.dto;

import javax.validation.constraints.NotBlank;

/**
 * 授权签名人登记请求：登记后该人员才可对检验单电子签名放行。
 * 名称重复返回已存在记录（requestId 幂等）。
 */
public class SignerRegisterRequest {

    @NotBlank(message = "签名人姓名不能为空")
    private String signerName;

    @NotBlank(message = "签名口令不能为空")
    private String signSecret;

    private String remark;

    @NotBlank(message = "请求号不能为空")
    private String requestId;

    public String getSignerName() { return signerName; }
    public void setSignerName(String signerName) { this.signerName = signerName; }
    public String getSignSecret() { return signSecret; }
    public void setSignSecret(String signSecret) { this.signSecret = signSecret; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
