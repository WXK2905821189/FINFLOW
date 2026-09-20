import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Input, Spin } from 'antd';
import { ApartmentOutlined, SearchOutlined, StarFilled, StarOutlined } from '@ant-design/icons';
import { bankPipelineApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useSubjectScope } from '../../store/scope';
import { useRemote } from '../shared/components';
import type { CompanyOption } from '../bank-access/types';

/**
 * V36 D3：顶栏全局主体作用域切换器（demo 第二版交互的落地版）。
 *
 * · 代替每页的「公司主体」筛选字段：余额 / 流水 / 账户页共享，选中即全站生效。
 * · W8（2026-09-20）：选择语义升级为**多选 + 全部主体**（后端 companyIds 多值参数，
 *   银行数据链路一期接入）；列表勾选即累加范围，顶栏 chips 展示当前集合。
 * · 搜索 / 常用收藏 / 列表勾选，均对照 demo；主体「启用 / 停用」分组暂缺——
 *   company-options 接口不返回状态，等档案接口补字段后再分组。
 * · 无跨公司权限的用户渲染为静态「本公司」标识：查询接口对无权限用户传 companyIds
 *   会 403，绝不能给他们可点的切换器。
 */
export function SubjectScope() {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const userCompanyName = useAuthStore((state) => state.user?.companyName);
  const canCrossCompany = hasPermission('bankdata:cross-company:view');
  const companyIds = useSubjectScope((state) => state.companyIds);
  const favs = useSubjectScope((state) => state.favs);
  const setCompanyIds = useSubjectScope((state) => state.setCompanyIds);
  const toggleCompany = useSubjectScope((state) => state.toggleCompany);
  const toggleFav = useSubjectScope((state) => state.toggleFav);

  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const wrapRef = useRef<HTMLDivElement>(null);

  const loader = useCallback(
    () => (canCrossCompany ? bankPipelineApi.companyOptions() : Promise.resolve([] as CompanyOption[])),
    [canCrossCompany],
  );
  const { data: optionRows, loading } = useRemote<CompanyOption[]>(loader, [loader]);
  const companies = useMemo(() => optionRows || [], [optionRows]);
  const nameById = useMemo(() => new Map(companies.map((c) => [String(c.id), c.name])), [companies]);

  // 点外部收起（mousedown：拖动选择文本时 click 可能不派发）。
  useEffect(() => {
    if (!open) return;
    const onDocMouseDown = (event: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
  }, [open]);

  if (!canCrossCompany) {
    return (
      <div className="scope-btn is-static" title="跨公司查看需「跨公司银行数据查看」权限；当前仅显示本公司数据">
        <ApartmentOutlined className="scope-ico" />
        <span className="scope-text"><b>{userCompanyName || '本公司'}</b><span>仅本公司数据</span></span>
      </div>
    );
  }

  const scoped = companyIds.length > 0;
  const label = !scoped
    ? '全部主体'
    : companyIds.length === 1
      ? (nameById.get(companyIds[0]) || `主体 #${companyIds[0]}`)
      : `${companyIds.length} 个主体`;
  const hint = !scoped
    ? `${companies.length} 个主体在范围内`
    : `范围已收窄到 ${companyIds.length} 个主体`;
  const isOn = (id: string) => companyIds.includes(id);

  const filtered = companies.filter((c) => {
    const q = query.trim().toLowerCase();
    if (!q) return true;
    return String(c.name).toLowerCase().includes(q) || String(c.id).includes(q);
  });
  const favFirst = [...filtered].sort((a, b) => {
    const fa = favs.includes(String(a.id)) ? 0 : 1;
    const fb = favs.includes(String(b.id)) ? 0 : 1;
    return fa - fb || String(a.name).localeCompare(String(b.name), 'zh-CN');
  });

  return (
    <div className="scope-wrap" ref={wrapRef}>
      <button
        type="button"
        className={`scope-btn${scoped ? ' is-scoped' : ''}`}
        onClick={() => { setOpen((v) => !v); setQuery(''); }}
        aria-haspopup="dialog"
        aria-expanded={open}
        title="选择公司主体范围（可多选；全站生效：余额 / 流水 / 账户）"
      >
        <ApartmentOutlined className="scope-ico" />
        <span className="scope-text"><b>{label}</b><span>{hint}</span></span>
        <span className="scope-count">{scoped ? companyIds.length : companies.length}</span>
        <span className="caret" />
      </button>

      {open && (
        <div className="scope-pop" role="dialog" aria-label="选择公司主体范围">
          <div className="scope-pop-head">
            <Input
              autoFocus
              allowClear
              size="small"
              prefix={<SearchOutlined style={{ color: '#9fb3b8' }} />}
              placeholder="搜索主体名称 / 编号"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </div>

          <div className="scope-chips">
            <span className="scope-chips-label">当前范围（全站生效，可多选）</span>
            <div className="scope-chips-row">
              {!scoped && <span className="scope-chip">全部主体</span>}
              {companyIds.map((id) => (
                <span key={id} className="scope-chip is-on">
                  {nameById.get(id) || `主体 #${id}`}
                  <button type="button" title="从范围中移除" onClick={() => toggleCompany(id)}>✕</button>
                </span>
              ))}
            </div>
          </div>

          {favs.length > 0 && (
            <div className="scope-favrow">
              <span className="lbl">常用</span>
              {favs.filter((id) => nameById.has(id)).map((id) => (
                <button
                  key={id}
                  type="button"
                  className={`fav-chip${isOn(id) ? ' is-on' : ''}`}
                  onClick={() => toggleCompany(id)}
                >
                  <StarFilled /> {nameById.get(id)}
                </button>
              ))}
            </div>
          )}

          <div className="scope-list">
            {loading && !companies.length ? (
              <div className="scope-loading"><Spin size="small" /></div>
            ) : favFirst.length ? (
              favFirst.map((company) => {
                const id = String(company.id);
                const on = isOn(id);
                const faved = favs.includes(id);
                return (
                  <div key={id} className={`srow${on ? ' is-on' : ''}`}>
                    <span
                      role="checkbox"
                      aria-checked={on}
                      tabIndex={0}
                      className="box"
                      onClick={() => toggleCompany(id)}
                      onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); toggleCompany(id); } }}
                    >
                      {on ? '✓' : ''}
                    </span>
                    <span className="srow-name" onClick={() => toggleCompany(id)}>{company.name}</span>
                    <button
                      type="button"
                      className={`star${faved ? ' is-on' : ''}`}
                      title={faved ? '取消收藏' : '收藏为常用'}
                      onClick={(event) => { event.stopPropagation(); toggleFav(id); }}
                    >
                      {faved ? <StarFilled /> : <StarOutlined />}
                    </button>
                  </div>
                );
              })
            ) : (
              <div className="scope-empty">没有匹配的主体</div>
            )}
          </div>

          <div className="scope-pop-foot">
            <span className="hint">选择结果对余额 / 流水 / 账户等页全站生效</span>
            {scoped && <Button size="small" onClick={() => setCompanyIds([])}>全部主体</Button>}
            <Button size="small" type="primary" onClick={() => setOpen(false)}>完成</Button>
          </div>
        </div>
      )}
    </div>
  );
}
