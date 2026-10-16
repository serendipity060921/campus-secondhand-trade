# -*- coding: utf-8 -*-
"""后端代码卫生检查（棘轮）

与 tools/check-tokens.py、check-states.py 同一套哲学：**发现数只许下降，不许上升**。
每清理掉一批问题，就把 CI 里的阈值下调一次。

检查项（都是"真正的坏味道"，而不是格式洁癖）：
  1. System.out / System.err 直接打印     → 应该用日志框架（否则没有级别与上下文）
  2. e.printStackTrace()                 → 应该交给统一异常处理
  3. 空 catch 块                         → 吞异常是线上事故的常见来源
  4. 硬编码口令/密钥/令牌                 → 必须走配置（环境变量）
  5. SELECT *                            → 明确列名，避免表结构变化导致故障
  6. 通配符 import（import xxx.*）        → 显式依赖，便于阅读与重构
  7. TODO / FIXME                        → 未完成事项要可见、要收敛

用法：
  python tools/check-backend.py                       # 用内置阈值检查（CI 用）
  python tools/check-backend.py --label ci            # 写报告到 .impeccable/review/ci/
  python tools/check-backend.py --max-println 3       # 临时指定阈值
  python tools/check-backend.py --json                # 输出 JSON

退出码：任一检查项超出阈值 → 1；否则 0
"""

# ★ Windows 控制台的默认编码是 GBK，而本脚本会输出 ✓/✗/✅ 等字符 ——
#   不重新配置 stdout 的话，手动执行会直接抛 UnicodeEncodeError 中断。
import sys as _sys
try:
    _sys.stdout.reconfigure(encoding='utf-8')
    _sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass
import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / 'backend' / 'src' / 'main' / 'java'
XML = ROOT / 'backend' / 'src' / 'main' / 'resources'
REPORT_DIR = ROOT / '.impeccable' / 'review'

# 当前基线（2026-10-09 实测后固化）：只许下降
DEFAULT_MAX = {
    'println': 1,        # 启动类里刻意用 System.out 打印英文横幅（Windows 控制台中文乱码）
    'printstack': 0,
    'emptycatch': 0,
    'hardcoded': 0,
    'selectstar': 0,
    'wildcardimport': 0,
    'todo': 0,
}

CHECKS = {
    'println': ('System.out / System.err 直接打印', re.compile(r'System\.(out|err)\.print')),
    'printstack': ('e.printStackTrace()', re.compile(r'\.printStackTrace\s*\(')),
    'emptycatch': ('空 catch 块', re.compile(r'catch\s*\([^)]*\)\s*\{\s*\}')),
    'hardcoded': ('硬编码口令/密钥', re.compile(
        r'(password|passwd|secret|token|apikey|api_key)\s*[:=]\s*"[^"\s]{6,}"', re.I)),
    'selectstar': ('SELECT *', re.compile(r'select\s+\*', re.I)),
    'wildcardimport': ('通配符 import', re.compile(r'^\s*import\s+[\w.]+\*;', re.M)),
    'todo': ('TODO / FIXME', re.compile(r'\b(TODO|FIXME|XXX)\b')),
}

# 硬编码检查里允许出现的"不是密钥"的值
ALLOW_HARDCODED = re.compile(r'"(127\.0\.0\.1|localhost|utf-8|UTF-8|admin|root)"', re.I)

# 白名单：JwtProperties 的开发默认密钥。
# 核实结论（2026-10-09）：生产配置 application-prod.yml 用的是 secret: ${JWT_SECRET}（无默认值、
# 缺失即启动失败），因此该默认值只在开发环境生效，不构成生产风险。
# ★ 阈值仍保持 0 —— 白名单只针对这一条，任何**新增**的硬编码密钥都会立刻让 CI 失败。
ALLOW_HARDCODED_PATTERNS = [
    re.compile(r'campus-secondhand-trade-jwt-secret-key', re.I),
]


def scan(paths, pattern, allow=None, whitelist=None):
    hits = []
    for p in paths:
        try:
            text = p.read_text(encoding='utf-8', errors='ignore')
        except Exception:
            continue
        for m in pattern.finditer(text):
            line = text[:m.start()].count('\n') + 1
            snippet = text.splitlines()[line - 1].strip() if line - 1 < len(text.splitlines()) else ''
            if allow and allow.search(m.group(0)):
                continue
            if whitelist and any(w.search(m.group(0)) for w in whitelist):
                continue
            hits.append({'file': str(p.relative_to(ROOT)).replace('\\', '/'),
                         'line': line, 'snippet': snippet[:120]})
    return hits


def main():
    ap = argparse.ArgumentParser(description='后端代码卫生检查（棘轮）')
    ap.add_argument('--label', default=None, help='报告标签（如 ci）')
    ap.add_argument('--json', action='store_true', help='输出 JSON')
    for k in DEFAULT_MAX:
        ap.add_argument(f'--max-{k}', type=int, default=None, help=f'{k} 的最大允许数')
    args = ap.parse_args()

    java_files = sorted(SRC.rglob('*.java')) if SRC.exists() else []
    xml_files = sorted(XML.rglob('*.xml')) if XML.exists() else []
    files = java_files + xml_files

    results = {}
    over = []
    for key, (label, pattern) in CHECKS.items():
        allow = ALLOW_HARDCODED if key == 'hardcoded' else None
        whitelist = ALLOW_HARDCODED_PATTERNS if key == 'hardcoded' else None
        hits = scan(files, pattern, allow, whitelist)
        limit = getattr(args, f'max_{key}')
        limit = DEFAULT_MAX[key] if limit is None else limit
        results[key] = {'label': label, 'count': len(hits), 'max': limit, 'hits': hits[:20]}
        if len(hits) > limit:
            over.append((key, label, len(hits), limit))

    payload = {'scanned_files': len(files), 'checks': results,
               'verdict': 'pass' if not over else 'fail'}

    if args.label:
        out = REPORT_DIR / args.label
        out.mkdir(parents=True, exist_ok=True)
        (out / 'backend-hygiene.json').write_text(
            json.dumps(payload, ensure_ascii=False, indent=1), encoding='utf-8')

    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=1))
    else:
        print(f'后端代码卫生检查：扫描 {len(files)} 个文件（{len(java_files)} java / {len(xml_files)} xml）')
        print('-' * 74)
        for key, r in results.items():
            flag = '✓' if r['count'] <= r['max'] else '✗'
            print(f"  {flag} {r['label']:<28} 发现 {r['count']:>3}   阈值 {r['max']:>3}")
            for h in r['hits'][:3]:
                print(f"        {h['file']}:{h['line']}")
        print('-' * 74)
        if over:
            print('  ✗ 有检查项超出阈值（棘轮只许下降）：')
            for key, label, cnt, limit in over:
                print(f'      {label}: {cnt} > {limit}')
        else:
            print('  ✓ 全部在阈值内')

    return 0 if not over else 1


if __name__ == '__main__':
    sys.exit(main())
