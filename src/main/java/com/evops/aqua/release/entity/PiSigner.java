package com.evops.aqua.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 授权电子签名人：登记在册且 enabled=TRUE 的人员凭签名口令才能放行批次。
 * 禁用（enabled=FALSE）后立即失去签名权限；历史签名记录保留不变。
 */
@TableName("t_pi_signer")
public class PiSigner extends BaseEntity {

    private String signerName;
    private String signSecret;
    private Boolean enabled;
    private String remark;
    private String requestId;

    public String getSignerName() { return signerName; }
    public void setSignerName(String signerName) { this.signerName = signerName; }
    public String getSignSecret() { return signSecret; }
    public void setSignSecret(String signSecret) { this.signSecret = signSecret; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
