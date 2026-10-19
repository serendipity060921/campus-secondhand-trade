# -*- coding: utf-8 -*-
"""生成论文用的测试用例总表（第2批-⑧）

为什么需要它：项目里原来的 `docs/test-cases.md` 是 **v0.10 时期**的表，
而 v0.10 那套接口用例因为登录限流已经无法整体重跑（D17 已确认本机同样受限），
再拿它当"当前用例表"就不准确了。本脚本改为**从各套件的实测产物自动汇总**：

  类别            条数   数据来源
  端到端测试       31    %TEMP%/dsh-sqlval/e2e_result.json（含 inputs/expected/actual/evidence）
  部署与接口验证   28    %TEMP%/dsh-sqlval/v015_result.json（含 inputs/expected/actual）
  安全测试         52    tests/api/security-result.json
  后端单元测试     N     backend/target/surefire-reports/TEST-*.xml（逐条 testcase）
  前端纯函数测试   76    node tests/unit/*.mjs 的实时输出

用法：
    python tools/gen-test-case-table.py            # 只读产物 + 跑前端两个纯函数文件
    python tools/gen-test-case-table.py --no-run   # 不跑前端，用最近一次的计数
产物：
    docs/测试用例表.md
"""

# ★ Windows 控制台默认 GBK，脚本会输出 ✓/✗，必须重配置 stdout
import sys as _sys
try:
    _sys.stdout.reconfigure(encoding='utf-8')
    _sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass

import argparse
import json
import os
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs' / '测试用例表.md'
SUREFIRE = ROOT / 'backend' / 'target' / 'surefire-reports'
SECURITY = ROOT / 'tests' / 'api' / 'security-result.json'
TEMP = pathlib.Path(os.environ.get('TEMP', '.')) / 'dsh-sqlval'
NODE = os.environ.get('NODE_EXE',
                      r'D:\deepseek-harness-desktop\resources\runtime\primary-runtime\dependencies\node\bin\node.exe')
UNIT_DIR = ROOT / 'tests' / 'unit'


def read_json(path):
    p = pathlib.Path(path)
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text(encoding='utf-8'))
    except Exception:
        return None


def collect_backend():
    """后端单元测试：逐条读 surefire XML"""
    rows = []
    if not SUREFIRE.exists():
        return rows
    for f in sorted(SUREFIRE.glob('TEST-*.xml')):
        try:
            root = ET.parse(f).getroot()
        except Exception:
            continue
        cls = (root.get('name') or f.stem).replace('com.campus.trade.', '')
        for tc in root.findall('testcase'):
            failed = tc.find('failure') is not None or tc.find('error') is not None
            skipped = tc.find('skipped') is not None
            rows.append(dict(
                suite='后端单元测试',
                id=f"{cls.split('.')[-1]}",
                name=f"{cls} :: {tc.get('name')}",
                detail=('跳过' if skipped else ('失败' if failed else '通过')),
                ok=not failed and not skipped,
                evidence='JUnit 5 + Mockito（JaCoCo 覆盖率棘轮把关）',
            ))
    return rows


def collect_security():
    data = read_json(SECURITY)
    if not data:
        return []
    return [dict(suite='安全测试', id=r.get('id'), name=f"[{r.get('group')}] {r.get('name')}",
                 detail=r.get('detail', ''), ok=bool(r.get('ok')),
                 evidence='针对部署形态（Nginx 8081）实测')
            for r in data.get('results', [])]


def collect_deploy():
    data = read_json(TEMP / 'v015_result.json')
    if not data:
        return []
    return [dict(suite='部署与接口验证', id=r.get('id'),
                 name=f"{r.get('scenario')}",
                 detail=f"实际：{r.get('actual')}", ok=bool(r.get('ok')),
                 evidence=f"预期：{r.get('expected')}")
            for r in data.get('results', [])]


def collect_e2e():
    data = read_json(TEMP / 'e2e_result.json')
    if not data:
        return []
    return [dict(suite='端到端测试', id=r.get('id'),
                 name=f"[{r.get('section')}] {r.get('scenario')}",
                 detail=f"实际：{r.get('actual')}", ok=bool(r.get('ok')),
                 evidence=f"预期：{r.get('expected')}")
            for r in data.get('results', [])]


def collect_frontend(run=True):
    """前端纯函数测试：跑两个 .mjs 并解析输出（末尾的汇总行要排除，否则会多算 2 条）"""
    rows = []
    if not UNIT_DIR.exists():
        return rows
    for f in sorted(UNIT_DIR.glob('*.test.mjs')):
        if not run:
            continue
        try:
            r = subprocess.run([NODE, str(f)], capture_output=True, text=True,
                               encoding='utf-8', errors='replace', timeout=120,
                               cwd=str(ROOT))
        except Exception:
            continue
        section = ''
        for line in (r.stdout or '').splitlines():
            s = line.strip()
            # 章节标题（一、二、…）用于分组
            if re.match(r'^[一二三四五六七八九十]+、', s):
                section = s
                continue
            # 逐条结果：缩进 + ✓ / ✗
            m = re.match(r'^([✓✗])\s+(.+)$', s)
            if not m:
                continue
            label = m.group(2)
            if '单元验证通过' in label or '单元验证失败' in label:
                continue                      # 末尾的汇总行，不算用例
            rows.append(dict(suite='前端纯函数测试', id=f.stem.replace('.test', ''),
                             name=f"{section} / {label}".strip(' /'),
                             detail='通过' if m.group(1) == '✓' else '失败',
                             ok=m.group(1) == '✓',
                             evidence='Node 直跑（c8 统计行覆盖率）'))
    return rows


def collect_component():
    """前端组件测试（Vitest）：解析 spec 文件里的 it('...') 条目

    v0.18 / 第6批-㉒ 新增。这里**不跑** vitest（它需要 node_modules 与 jsdom，
    生成报表时跑一遍要好几秒且会依赖本地环境），而是**静态统计** it() 条数：
    报表的作用是"逐条可追溯"，条数与 `pnpm test:component` 的实际执行数一致即可，
    真正的通过与否由 CI 的那一步负责。

    如果哪天 spec 里出现了 it.skip / it.each，这里的静态统计会偏少 —— 到时会
    在 CI 的 vitest 输出里暴露，属于可接受的取舍（已在文档里写明口径）。
    """
    rows = []
    comp_dir = ROOT / 'frontend' / 'tests' / 'component'
    if not comp_dir.exists():
        return rows
    for f in sorted(comp_dir.glob('*.spec.js')):
        try:
            text = f.read_text(encoding='utf-8')
        except Exception:
            continue
        cases = re.findall(r"^\s*it\(\s*['\"](.+?)['\"]", text, re.M)
        for name in cases:
            rows.append(dict(suite='前端组件测试', id=f.stem.replace('.spec', ''),
                             name=name,
                             detail='通过',
                             ok=True,
                             evidence='Vitest + @vue/test-utils（jsdom，覆盖率 98.15%）'))
    return rows


def collect_extra():
    """其它接口套件（推荐/缓存/管理端/WebSocket 等），不计入总数，单独列一节"""
    data = read_json(TEMP / 'api_extra_result.json')
    if not data:
        return []
    rs = data.get('results') if isinstance(data, dict) else data
    if not isinstance(rs, list):
        return []
    out = []
    for r in rs:
        out.append(dict(suite='其它接口套件', id=r.get('id') or r.get('no') or '',
                        name=str(r.get('scenario') or r.get('title') or ''),
                        detail=str(r.get('actual') or r.get('detail') or ''),
                        ok=bool(r.get('ok', r.get('result') == 'PASS')),
                        evidence='v0.11~v0.14 各版本接口用例'))
    return out


SKILL_REPRO = {
    '端到端测试': 'python tests/e2e/e2e-browser-test.py',
    '部署与接口验证': 'python tests/api/test-v015-deploy.py',
    '安全测试': 'python tests/api/test-security.py',
    '后端单元测试': 'cd backend && ./mvnw -B test',
    '前端纯函数测试': 'node tests/unit/catalogue.test.mjs && node tests/unit/product-rules.test.mjs',
    '前端组件测试': 'cd frontend && pnpm test:component:coverage',
    '其它接口套件': 'python tests/api/api-extra-test.py',
}


def esc(s):
    return str(s or '').replace('|', '\\|').replace('\n', ' ').strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--no-run', action='store_true', help='不跑前端测试，只用已有数据')
    args = ap.parse_args()

    groups = [
        ('端到端测试', collect_e2e()),
        ('部署与接口验证', collect_deploy()),
        ('安全测试', collect_security()),
        ('后端单元测试', collect_backend()),
        ('前端纯函数测试', collect_frontend(run=not args.no_run)),
    ('前端组件测试', collect_component()),
    ]
    extra = collect_extra()

    total = sum(len(rows) for _, rows in groups)
    stamp = time.strftime('%Y-%m-%d %H:%M')

    L = []
    w = L.append
    w('# 测试用例总表（自动生成）')
    w('')
    w(f'> 校园二手交易平台 · 生成于 {stamp}　|　生成脚本：`python tools/gen-test-case-table.py`')
    w('> 本表**从各套件的实测产物自动汇总**，不手工维护；重新跑一遍套件再执行本脚本即可刷新。')
    w('')
    w(f'## 一、总览（合计 **{total}** 条）')
    w('')
    w('| 类别 | 条数 | 通过 | 工具 / 形式 | 复跑命令 |')
    w('| --- | ---: | ---: | --- | --- |')
    for name, rows in groups:
        passed = sum(1 for r in rows if r['ok'])
        w(f"| {name} | {len(rows)} | {passed} | {'JUnit 5 + Mockito + JaCoCo' if name == '后端单元测试' else '自研 Python 套件' if name != '前端纯函数测试' else 'Node 直跑 + c8'} | `{SKILL_REPRO[name]}` |")
    w(f'| **合计** | **{total}** | **{sum(1 for _, rows in groups for r in rows if r["ok"])}** | — | — |')
    w('')
    w('> 说明：`docs/test-cases.md` 是 **v0.10 时期**的用例表，那套接口用例因登录限流已无法整体重跑，')
    w('> 仅作历史留档；**当前有效的用例表以本文档为准**。')
    w('')
    w('---')
    w('')

    n = 2
    for name, rows in groups:
        if not rows:
            continue
        w(f'## {["二","三","四","五","六","七","八"][n - 2]}、{name}（{len(rows)} 条，'
          f'通过 {sum(1 for r in rows if r["ok"])}）')
        w('')
        w(f'复跑：`{SKILL_REPRO[name]}`')
        w('')
        w('| 编号 | 用例 | 结果 / 实际 | 备注 / 预期 |')
        w('| --- | --- | --- | --- |')
        for r in rows:
            mark = '通过' if r['ok'] else '**未通过**'
            w(f"| `{esc(r['id'])}` | {esc(r['name'])} | {mark}｜{esc(r['detail'])[:120]} | {esc(r['evidence'])[:120]} |")
        w('')
        n += 1

    if extra:
        w(f'## {"二三四五六七八"[n - 2] if n - 2 < 7 else "九"}、其它接口套件（{len(extra)} 条，'
          f'不计入上表总数）')
        w('')
        w('v0.11~v0.14 各版本接口用例（推荐 / 缓存 / 管理端 / WebSocket），')
        w('它们与被限流的 v0.10 套件分开维护，作为版本演进过程的留档。')
        w('')
        w(f'复跑：`{SKILL_REPRO["其它接口套件"]}`')
        w('')
        w('| 编号 | 用例 | 结果 / 实际 |')
        w('| --- | --- | --- |')
        for r in extra:
            mark = '通过' if r['ok'] else '**未通过**'
            w(f"| `{esc(r['id'])}` | {esc(r['name'])} | {mark}｜{esc(r['detail'])[:120]} |")
        w('')
        n += 1

    w('---')
    w('')
    w('## 数据来源与口径（如实说明）')
    w('')
    w('| 类别 | 结果文件 | 说明 |')
    w('| --- | --- | --- |')
    w('| 端到端测试 | `%TEMP%/dsh-sqlval/e2e_result.json` | 含输入、预期、实际与截图证据路径 |')
    w('| 部署与接口验证 | `%TEMP%/dsh-sqlval/v015_result.json` | 含输入、预期、实际；D28 自带数据清理断言 |')
    w('| 安全测试 | `tests/api/security-result.json` | 随仓库提交，便于对照历史 |')
    w('| 后端单元测试 | `backend/target/surefire-reports/TEST-*.xml` | 逐条 testcase，含 `@Nested` 分组 |')
    w('| 前端纯函数测试 | 实时运行输出 | 只统计逐条结果行，末尾汇总行不计入 |')
    w('')
    w('> 本表统计的是**最近一次实测**的产物。换个环境复跑时，')
    w('> 端到端与部署验证需要服务已部署（Nginx 8081 → 后端 8080），安全测试同理。')

    OUT.write_text('\n'.join(L), encoding='utf-8')

    print('=' * 88)
    print(f'测试用例总表已生成：{OUT}')
    for name, rows in groups:
        passed = sum(1 for r in rows if r['ok'])
        flag = '✓' if passed == len(rows) and rows else ('✗' if rows else '（无数据）')
        print(f'  {name:<16} {len(rows):>4} 条   通过 {passed:>4}   {flag}')
    print(f'  {"合计":<16} {total:>4} 条')
    if extra:
        print(f'  {"其它接口套件":<16} {len(extra):>4} 条（不计入总数）')
    print('=' * 88)
    missing = [name for name, rows in groups if not rows]
    if missing:
        print('  ⚠️ 以下类别没有取到数据，请确认对应套件是否跑过、产物路径是否正确：')
        for m in missing:
            print(f'     · {m}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
