#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""金蝶开放平台 OpenAPI 文档批量抓取（客户/供应商/银行账号/付款单/收款单）
参照 README 抓取经验：真实端点 = /mscdp/apicenter/... + CDP_TK 头
数据源逆向结论（2026-09-04）:
  GET /mscdp/apicenter/apitree                              -> 文档树(4层: 领域/模块/业务对象/操作)
  GET /mscdp/apicenter/apiinfo/detail/{apiInfoId}            -> 操作基本信息(formId/name/description)
  GET /mscdp/apicenter/apiinfoparam/apiinfo/{id}/paramfield/{req|res} -> 参数字段树
  GET /mscdp/apicenter/apiinfoparam/apiinfo/{id}/paramjson   -> 请求/响应示例 JSON
"""
import json, os, time, urllib.request, sys

BASE = "https://openapi.open.kingdee.com/mscdp/apicenter"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT_ROOT = os.path.normpath(os.path.join(HERE, "..", "openapi-docs"))
TREE_PATH = os.path.join(HERE, "openapi-apitree.json")
STATE_PATH = os.path.join(HERE, "openapi-storage-state.json")

# 目标：领域路径 -> (描述)
TARGETS = [
    (["基础管理", "基础资料", "客户"], "BD_Customer"),
    (["基础管理", "基础资料", "供应商"], "BD_Supplier"),
    (["基础管理", "基础资料", "银行账号"], "CN_BANKACNT"),
    (["财务会计", "出纳管理", "付款单"], "AP_PAYBILL"),
    (["财务会计", "出纳管理", "收款单"], "AR_RECEIVEBILL"),
]

def load_tree():
    d = json.load(open(TREE_PATH, encoding="utf-8"))
    body = json.loads(d["body"]) if isinstance(d.get("body"), str) else d
    return body.get("dataList") or []

def find_path(nodes, labels):
    for n in nodes:
        if n.get("label") == labels[0]:
            if len(labels) == 1:
                return n
            r = find_path(n.get("children") or [], labels[1:])
            if r:
                return r
    return None

def get_token():
    d = json.load(open(STATE_PATH, encoding="utf-8"))
    for c in d.get("cookies", []):
        if c.get("name") == "CDP_TK":
            return c["value"]
    raise SystemExit("NO CDP_TK in storage-state")

def http_get(url, tk, retries=2):
    req = urllib.request.Request(url, headers={
        "CDP_TK": tk,
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Content-Type": "application/json; charset=utf-8",
    })
    for i in range(retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, r.read()
        except Exception as e:
            if i == retries:
                return None, str(e).encode()
            time.sleep(1.5)
    return None, b""

def main():
    os.makedirs(OUT_ROOT, exist_ok=True)
    tk = get_token()
    tree = load_tree()
    ops = []
    for path, fid in TARGETS:
        node = find_path(tree, path)
        if not node:
            print("!! 未找到:", " > ".join(path))
            continue
        for op in node.get("children") or []:
            ops.append({"formId": fid, "opKey": op.get("key"), "opLabel": op.get("label"),
                        "id": op.get("id"), "path": " > ".join(path)})
    print(f"目标操作总数: {len(ops)}")
    failed = []
    for i, o in enumerate(ops, 1):
        aid = o["id"]
        out = {"info": None, "reqField": None, "resField": None,
               "requestJson": None, "responseJson": None}
        # 1) detail
        st, raw = http_get(f"{BASE}/apiinfo/detail/{aid}", tk)
        if st == 200:
            d = json.loads(raw)
            out["info"] = d.get("data")
        else:
            failed.append(f"{o['formId']}.{o['opKey']} detail HTTP {st} {raw[:120]!r}")
        # 2) paramfield req
        st, raw = http_get(f"{BASE}/apiinfoparam/apiinfo/{aid}/paramfield/req", tk)
        if st == 200:
            d = json.loads(raw)
            out["reqField"] = d.get("dataList") or d.get("data")
        else:
            failed.append(f"{o['formId']}.{o['opKey']} paramfield/req HTTP {st}")
        # 3) paramfield res
        st, raw = http_get(f"{BASE}/apiinfoparam/apiinfo/{aid}/paramfield/res", tk)
        if st == 200:
            d = json.loads(raw)
            out["resField"] = d.get("dataList") or d.get("data")
        # 4) paramjson
        st, raw = http_get(f"{BASE}/apiinfoparam/apiinfo/{aid}/paramjson", tk)
        if st == 200:
            d = json.loads(raw)
            dd = d.get("data") or {}
            out["requestJson"] = dd.get("requestJson")
            out["responseJson"] = dd.get("responseJson")
        else:
            failed.append(f"{o['formId']}.{o['opKey']} paramjson HTTP {st}")
        # 存盘: openapi-docs/{formId}/{opKey}.json
        fdir = os.path.join(OUT_ROOT, o["formId"])
        os.makedirs(fdir, exist_ok=True)
        with open(os.path.join(fdir, f"{o['opKey']}.json"), "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
        if i % 10 == 0 or i == len(ops):
            print(f"  [{i}/{len(ops)}] {o['formId']}.{o['opKey']}")
        time.sleep(0.15)
    print("完成。失败项:", len(failed))
    for x in failed[:20]:
        print("  FAIL", x)
    with open(os.path.join(HERE, "openapi-fetch-failed.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(failed))

if __name__ == "__main__":
    main()
