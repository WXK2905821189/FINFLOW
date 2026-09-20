import { useEffect, useState } from 'react';
import { Button, Input, message, Modal, Space, Tag } from 'antd';
import { FileTextOutlined } from '@ant-design/icons';
import { aiPromptApi, type AiPromptView, type AiPromptCapability } from '../../services/api';

const { TextArea } = Input;

/**
 * AI 提示词设置（V38，W9 需求 4）：每个 AI 能力入口旁的「提示词」按钮 + 编辑弹窗。
 *
 * - 仅超管（ai:config）可见（由调用方按权限控制渲染；后端同样强制）；
 * - 打开时拉取该能力当前生效值（覆盖值或系统默认）与默认值；
 * - 保存 = 写覆盖行（全局即时生效）；「恢复默认」= 删覆盖行回落代码内默认；
 * - 每个入口只需一行：<PromptSettingButton capability="accounting-suggestion" />。
 */
export function PromptSettingButton({ capability, hint }: { capability: AiPromptCapability; hint?: string }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button size="small" icon={<FileTextOutlined />} onClick={() => setOpen(true)} title={hint || '设置该 AI 能力的系统提示词'}>
        提示词
      </Button>
      <PromptSettingModal capability={capability} open={open} onClose={() => setOpen(false)} />
    </>
  );
}

export function PromptSettingModal({
  capability, open, onClose,
}: { capability: AiPromptCapability; open: boolean; onClose: () => void }) {
  const [view, setView] = useState<AiPromptView>();
  const [draft, setDraft] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    let alive = true;
    aiPromptApi.list()
      .then((rows) => {
        if (!alive) return;
        const row = rows.find((item) => item.capability === capability);
        if (!row) {
          message.error('该能力未登记提示词配置');
          onClose();
          return;
        }
        setView(row);
        setDraft(row.effectivePrompt);
      })
      .catch((reason) => {
        message.error(reason instanceof Error ? reason.message : '提示词配置加载失败');
        onClose();
      });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, capability]);

  const save = async () => {
    if (!view) return;
    if (!draft.trim()) {
      message.warning('提示词不能为空；如需恢复默认请点击「恢复默认」');
      return;
    }
    setSaving(true);
    try {
      const saved = await aiPromptApi.save(capability, draft);
      setView(saved);
      setDraft(saved.effectivePrompt);
      message.success('提示词已保存并即时生效');
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '提示词保存失败');
    } finally {
      setSaving(false);
    }
  };

  const reset = () => {
    if (!view) return;
    Modal.confirm({
      title: `恢复「${view.name}」的系统默认提示词？`,
      content: '将删除当前自定义覆盖，所有用户立即回到系统默认提示词。恢复前建议先复制备份当前内容。',
      okText: '恢复默认',
      cancelText: '取消',
      onOk: async () => {
        try {
          await aiPromptApi.reset(capability);
          const rows = await aiPromptApi.list();
          const row = rows.find((item) => item.capability === capability);
          if (row) {
            setView(row);
            setDraft(row.effectivePrompt);
          }
          message.success('已恢复系统默认提示词');
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : '重置失败');
        }
      },
    });
  };

  return (
    <Modal
      title={view ? `提示词设置 · ${view.name}` : '提示词设置'}
      open={open}
      onCancel={onClose}
      width={720}
      confirmLoading={saving}
      okText="保存"
      okButtonProps={{ disabled: !view }}
      onOk={() => void save()}
      footer={(_, { OkBtn, CancelBtn }) => (
        <Space wrap>
          <span className="muted" style={{ marginRight: 'auto' }}>
            {view?.customized
              ? <>当前为自定义覆盖{view.updatedBy ? `（${view.updatedBy} 编辑）` : ''}，全局即时生效</>
              : '当前为系统默认提示词，可直接修改后保存为全局覆盖'}
          </span>
          {view?.customized && <Button onClick={reset}>恢复默认</Button>}
          <CancelBtn />
          <OkBtn />
        </Space>
      )}
    >
      {view && (
        <>
          <div style={{ marginBottom: 8 }}>
            {view.customized ? <Tag color="orange">自定义覆盖</Tag> : <Tag>系统默认</Tag>}
            <span className="muted" style={{ fontSize: 12 }}>{view.description}</span>
          </div>
          <TextArea
            value={draft}
            disabled={saving}
            onChange={(event) => setDraft(event.target.value)}
            rows={14}
            styles={{ textarea: { fontFamily: 'var(--mono, monospace)', fontSize: 12.5, lineHeight: 1.6 } }}
            placeholder="系统提示词（System Prompt）——约束 AI 的角色、输出 JSON 结构与规则；用户数据由系统自动追加，无需在此拼接。"
          />
        </>
      )}
    </Modal>
  );
}
