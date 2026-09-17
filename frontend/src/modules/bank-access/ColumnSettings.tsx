import { useState } from 'react';
import { Button, Checkbox, Empty, Popover, Space } from 'antd';
import { SettingOutlined } from '@ant-design/icons';

/** 列设置选项：key 与 Table column 的 key 一致。 */
export type ColumnOption = { key: string; label: string };

/**
 * WP-C（2026-09-17）Excel 式列设置：勾选控制表格列显隐，选择持久化到 localStorage。
 * 页面侧持有 hidden 状态与默认隐藏清单，本组件只负责渲染与回传。
 */
export function ColumnSettings({ options, hidden, onChange }: {
  options: ColumnOption[];
  hidden: string[];
  onChange: (nextHidden: string[]) => void;
}) {
  const [open, setOpen] = useState(false);
  const visible = options.filter((option) => !hidden.includes(option.key));
  const toggle = (key: string, checked: boolean) => {
    const next = checked
      ? hidden.filter((item) => item !== key)
      : [...hidden, key];
    // 全部隐藏时保留至少一列没有意义，但允许用户自由选择；仅阻止把「详情」隐藏的误操作由页面侧控制。
    onChange(next);
  };
  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      placement="bottomRight"
      content={
        options.length === 0 ? (
          <Empty description="无可配置列" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <div style={{ maxWidth: 240 }}>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Checkbox.Group
                value={visible.map((option) => option.key)}
                style={{ display: 'flex', flexDirection: 'column', gap: 4 }}
              >
                {options.map((option) => (
                  <Checkbox
                    key={option.key}
                    value={option.key}
                    onChange={(event) => toggle(option.key, event.target.checked)}
                  >
                    {option.label}
                  </Checkbox>
                ))}
              </Checkbox.Group>
              <Button
                size="small"
                type="link"
                style={{ alignSelf: 'flex-start', paddingLeft: 0 }}
                onClick={() => onChange([])}
              >
                恢复默认
              </Button>
              <span className="muted" style={{ fontSize: 12 }}>选择保存在本浏览器</span>
            </Space>
          </div>
        )
      }
    >
      <Button size="small" icon={<SettingOutlined />}>列设置</Button>
    </Popover>
  );
}
