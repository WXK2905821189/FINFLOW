import { FeishuConnectWizard } from './FeishuConnectWizard';

/**
 * 飞书协同（V29 起）：页面主体即连接向导——建应用 → 填凭证 → 真实验证。
 * 一期的服务端 MOCK 模拟验证区已移除；通知发送记录等真实链路上线后再补展示。
 */
export function FeishuCollaboration() {
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="section-kicker">系统管理 / 协同配置</span>
          <h2>飞书协同</h2>
          <p className="muted">
            四步接入真实飞书：创建应用 → 填写凭证 → 验证连通 → 后续通知走真实发送。
          </p>
        </div>
      </div>
      <FeishuConnectWizard />
    </>
  );
}
