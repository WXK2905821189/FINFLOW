#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""将 openapi-docs/*.json 快照汇总为人读 Markdown 文档"""
import json, os, glob

HERE = os.path.dirname(os.path.abspath(__file__))
DOCS = os.path.normpath(os.path.join(HERE, "..", "openapi-docs"))
OUT = os.path.join(DOCS, "OpenAPI接口文档汇总-20260904.md")

OBJECTS = [
    ("BD_Customer", "客户", "基础管理 > 基础资料", "9/10 网关开发：流水对手方为客户时同步/校验客户档案，凭证往来科目辅助资料"),
    ("BD_Supplier", "供应商", "基础管理 > 基础资料", "9/10 网关开发：流水对手方为供应商时同步/校验供应商档案"),
    ("CN_BANKACNT", "银行账号", "基础管理 > 基础资料", "9/10 网关开发：FINFLOW 银行账户 ↔ 金蝶银行账号映射，收付款单结算账号字段来源"),
    ("AP_PAYBILL", "付款单", "财务会计 > 出纳管理", "9/10 网关开发：付款类流水在金蝶侧生成收付款单（含银行状态同步类操作）"),
    ("AR_RECEIVEBILL", "收款单", "财务会计 > 出纳管理", "9/10 网关开发：收款类流水在金蝶侧生成收款单"),
]

def render_fields(nodes, rows, depth=0):
    if not nodes:
        return
    for n in nodes:
        name = str(n.get("name") or "")
        pad = "　" * depth + ("└ " if depth else "")
        rows.append("| {n} | {t} | {m} | {d} | {desc} |".format(
            n=pad + name,
            t=n.get("fieldType") or "",
            m="是" if n.get("isMust") else "",
            d=str(n.get("defaultValue")) if n.get("defaultValue") is not None else "",
            desc=(n.get("description") or "").replace("|", "\\|").replace("\n", " "),
        ))
        render_fields(n.get("children"), rows, depth + 1)

def pretty_json(s):
    if not s:
        return None
    try:
        return json.dumps(json.loads(s), ensure_ascii=False, indent=2)
    except Exception:
        return str(s)

def main():
    lines = []
    A = lines.append
    A("# 金蝶云星空 OpenAPI 接口文档快照（客户 / 供应商 / 银行账号 / 收款单 / 付款单）")
    A("")
    A("> 抓取时间：2026-09-04 ｜ 来源：openapi.open.kingdee.com/ApiDoc（登录态拉取，端点逆向见 README 抓取报告）")
    A("> 快照原始 JSON：`docs/kingdee-openapi/openapi-docs/{FormId}/{操作Key}.json`（每文件含 info + 请求字段树 + 响应字段 + 示例 JSON）")
    A("> 调用形态：经金蝶 SDK（X-KDApi-* 配置 + X-KDApi-ServerUrl 决定服务地址）调用，ServerUrl 为公有云网关或私有云 K3Cloud 地址；本文档中心不直接暴露 REST URL")
    A("")
    A("## 总览")
    A("")
    A("| FormId | 业务对象 | 领域路径 | 操作数 | FINFLOW 用途 |")
    A("|---|---|---|---|---|")
    for fid, label, dom, use in OBJECTS:
        n = len(glob.glob(os.path.join(DOCS, fid, "*.json")))
        A(f"| `{fid}` | {label} | {dom} | {n} | {use} |")
    A("")
    A("---")
    A("")
    for fid, label, dom, use in OBJECTS:
        files = sorted(glob.glob(os.path.join(DOCS, fid, "*.json")))
        A(f"## {label}（`{fid}`）")
        A("")
        # 概览表
        A("| 操作Key | 名称 | 版本 | 白名单 | 请求字段数(顶层) |")
        A("|---|---|---|---|---|")
        for fp in files:
            d = json.load(open(fp, encoding="utf-8"))
            info = d.get("info") or {}
            req = d.get("reqField") or []
            A(f"| `{info.get('number') or os.path.basename(fp)[:-5]}` | {info.get('name') or ''} | {info.get('version') or ''} | {'是' if info.get('isInWhiteList') else '否'} | {len(req)} |")
        A("")
        # 每操作详情
        for fp in files:
            d = json.load(open(fp, encoding="utf-8"))
            info = d.get("info") or {}
            key = os.path.basename(fp)[:-5]
            A(f"### {label} · {info.get('name') or key}（`{fid}.{key}`）")
            A("")
            desc = info.get("description")
            if desc:
                A(f"**说明**：{desc}")
                A("")
            req = d.get("reqField") or []
            if req:
                A("**请求参数**：")
                A("")
                A("| 字段 | 类型 | 必录 | 默认值 | 说明 |")
                A("|---|---|---|---|---|")
                rows = []
                render_fields(req, rows)
                lines.extend(rows)
                A("")
            res = d.get("resField")
            if res:
                A("**响应参数**：")
                A("")
                A("| 字段 | 类型 | 必录 | 默认值 | 说明 |")
                A("|---|---|---|---|---|")
                rows = []
                render_fields(res, rows)
                lines.extend(rows)
                A("")
            else:
                A("*（文档中心未提供响应参数树，实际响应以在线测试为准）*")
                A("")
            rj = pretty_json(d.get("requestJson"))
            if rj:
                A("**请求示例 JSON**：")
                A("")
                A("```json")
                A(rj)
                A("```")
                A("")
            sj = pretty_json(d.get("responseJson"))
            if sj:
                A("**响应示例 JSON**：")
                A("")
                A("```json")
                A(sj)
                A("```")
                A("")
            A("---")
            A("")
    content = "\n".join(lines)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(content)
    print("生成:", OUT, f"({len(content)} chars)")

if __name__ == "__main__":
    main()
