package com.evops.aqua.release.dto;

import javax.validation.constraints.NotBlank;

/**
 * 电子签名放行请求：只有授权人员（RELEASE 权限）可签名。
 * signer + 签名密钥/口令 + 签名意见缺一不可；系统对检验单关键事实生成 HMAC 电子签章。
 * 建议不予放行（FAIL）时签名会被拒绝，需先退回补检并复检合格。
 */
public class ReleaseSignRequest {

    @NotBlank(message = "签名人不能为空")
    private String signer;

    @NotBlank(message = "签名口令不能为空")
    private String signSecret;

    @NotBlank(message = "签名意见不能为空")
    private String signRemark;

    @NotBlank(message = "请求号不能为空")
    private String requestId;

    public String getSigner() { return signer; }
    public void setSigner(String signer) { this.signer = signer; }
    public String getSignSecret() { return signSecret; }
    public void setSignSecret(String signSecret) { this.signSecret = signSecret; }
    public String getSignRemark() { return signRemark; }
    public void setSignRemark(String signRemark) { this.signRemark = signRemark; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
