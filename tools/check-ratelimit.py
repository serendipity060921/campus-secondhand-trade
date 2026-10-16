# -*- coding: utf-8 -*-
"""写接口限流覆盖检查（v0.18 / 第1批-③）

为什么需要这个脚本：
    项目用注解式限流（@RateLimit），好处是"贴着接口写、一眼能看到"，
    坏处是**新增写接口时很容易忘** —— 漏掉的那一个就是被刷的入口。
    因此把"所有写接口（POST/PUT/DELETE）是否都有限流"变成可检查的事实，
    并留一份白名单记录**有意不加限流**的接口及其理由。

检查方式（正向扫描 + 括号配平）：
    顺序读文件，把「注解行及其跨行续行」累积成 pending 块；
    遇到方法声明行时，pending 就是这个方法**自己的**注解块，在其中找 @XxxMapping 与 @RateLimit。
    这样可以同时处理两种注解顺序（@RateLimit 在 @PostMapping 之前或之后），
    也不会把相邻方法的注解算到自己头上。

    ★ 两个已修的自身缺陷（写在这里当教训）：
      1) 最初用"方法声明上下 ±N 字符内找 @RateLimit" —— 会把相邻方法的注解算进来
         （/api/user/avatar 紧跟 /api/user/update，就被误判为"已限流"）；
      2) 改成"向上逐行找注解"后，遇到 @RateLimit 的**跨行续行**（message = "..."）就断了，
         22 个写接口只识别出 14 个。
      检查工具自己有假阳性/假阴性，比没有工具更危险，因此最终改成现在这套正向扫描。

用法：
    python tools/check-ratelimit.py                 # 打印报告
    python tools/check-ratelimit.py --max-missing 0 # 棘轮：未限流接口数不得超过 N（CI 用）
    python tools/check-ratelimit.py --json          # 额外输出 JSON 供 CI 归档
"""
import argparse
import json
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CTRL_DIR = ROOT / 'backend' / 'src' / 'main' / 'java' / 'com' / 'campus' / 'trade' / 'controller'
OUT_DIR = ROOT / '.impeccable' / 'review' / 'ci'

ANY_MAPPING = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping')
WRITE_MAPPING = re.compile(r'@(Post|Put|Delete)Mapping')
RATE_LIMIT = re.compile(r'@RateLimit\s*\(')
METHOD_SIG = re.compile(r'^\s*public\s+[\w<>,\[\]\.\s]+?\s+(\w+)\s*\(')
CLASS_MAPPING = re.compile(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
COMMENT_LINE = re.compile(r'^\s*(/\*|\*|//)')

# 有意不加限流的接口白名单：key = "类名#方法名"，value = 理由
# 说明：白名单本身也是文档 —— 以后有人问"这个接口为什么没限流"，答案在这里。
WHITELIST = {
    'ProductModuleController#upload': '上传接口按用户与文件大小双重约束（5MB/单次），且发布流程本身已限流',
    'UserProfileController#uploadAvatar': '同上：头像上传受文件大小限制，且需登录',
    'AuthController#logout': '无状态、幂等、不写库（仅返回成功），限流无意义',
    'UserController#logout': '同上：退出登录为无状态操作，限流无意义',
    # 管理端：管理员专属（@LoginRequired(admin = true)），操作低频且每次都会写 admin_log 审计记录。
    # 对管理端加限流反而可能挡住正常的批量审核，因此选择"审计留痕"而非"频率限制"。
    'AdminController#audit': '管理员专属 + 每次操作写 admin_log 审计',
    'AdminController#offline': '管理员专属 + 每次操作写 admin_log 审计',
    'AdminController#changeUserStatus': '管理员专属 + 每次操作写 admin_log 审计',
    'AdminController#handleReport': '管理员专属 + 每次操作写 admin_log 审计',
    'CategoryManageController#add': '管理员专属（@LoginRequired(admin = true)）+ 调 admin 接口写审计',
    'CategoryManageController#update': '管理员专属（@LoginRequired(admin = true)）+ 调 admin 接口写审计',
}


def scan_file(path, class_limit):
    """正向扫描：返回该 Controller 中的写接口列表"""
    text = path.read_text(encoding='utf-8')
    cls_match = CLASS_MAPPING.search(text)
    base = cls_match.group(1) if cls_match else ''

    results = []
    pending = []      # 当前累积的注解行
    depth = 0         # 未配平的左括号数（用于收集跨行注解的续行）
    skip_sig = 0      # 方法签名本身跨行时，跳过其参数行 ——
                      # ★ 参数上的 @RequestParam 不是"方法的注解"，若不跳过会把括号深度搞成负数，
                      #   进而让后面 @RateLimit 的跨行续行被"清空"规则抹掉（MessageController 就是这样漏报的）

    for line in text.splitlines():
        s = line.strip()

        if skip_sig > 0:                   # 正在跳过某个跨行方法签名的参数部分
            skip_sig = max(0, skip_sig + s.count('(') - s.count(')'))
            continue

        if depth > 0:                      # 跨行注解的续行
            pending.append(s)
            depth += s.count('(') - s.count(')')
            continue

        if s.startswith('@'):
            pending.append(s)
            depth = max(0, depth + s.count('(') - s.count(')'))
            continue

        sig = METHOD_SIG.match(line)
        if sig:
            # 记录方法签名自身的括号缺口：若签名跨行，后续参数行由 skip_sig 跳过
            skip_sig = max(0, line.count('(') - line.count(')'))
            if pending:
                block = pending[:]
                mapping = next((b for b in block if ANY_MAPPING.search(b)), None)
                if mapping and WRITE_MAPPING.search(mapping):
                    m = re.search(r'"([^"]*)"', mapping)
                    sub = m.group(1) if m else ''
                    method = sig.group(1)
                    results.append({
                        'file': path.name,
                        'class': path.stem,
                        'verb': WRITE_MAPPING.search(mapping).group(1).upper(),
                        'path': (base + sub) or sub,
                        'method': method,
                        'rate_limit': any(RATE_LIMIT.search(b) for b in block) or class_limit,
                        'whitelisted': f'{path.stem}#{method}' in WHITELIST,
                        'whitelist_reason': WHITELIST.get(f'{path.stem}#{method}', ''),
                    })
            pending = []
            continue

        # 其它代码行（字段、`}`、方法体）→ 注解块到此为止；注释与空行不影响
        if s and not COMMENT_LINE.match(line):
            pending = []

    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--max-missing', type=int, default=None,
                    help='未限流（且不在白名单）的接口数上限，超过则退出码非 0')
    ap.add_argument('--json', action='store_true', help='输出 JSON 供 CI 归档')
    ap.add_argument('--label', default='local')
    args = ap.parse_args()

    endpoints = []
    for f in sorted(CTRL_DIR.glob('*.java')):
        text = f.read_text(encoding='utf-8')
        head = text[:text.index('public class')] if 'public class' in text else ''
        endpoints.extend(scan_file(f, bool(RATE_LIMIT.search(head))))

    limited = [e for e in endpoints if e['rate_limit']]
    whitelisted = [e for e in endpoints if not e['rate_limit'] and e['whitelisted']]
    missing = [e for e in endpoints if not e['rate_limit'] and not e['whitelisted']]

    print('=' * 92)
    print(f'写接口限流覆盖检查：共 {len(endpoints)} 个写接口（POST/PUT/DELETE）')
    print(f'  已加 @RateLimit     : {len(limited)}')
    print(f'  白名单（有意不加）  : {len(whitelisted)}')
    print(f'  ★ 未限流且无理由    : {len(missing)}')
    print('=' * 92)

    if limited:
        print('\n【已限流的写接口】')
        for e in limited:
            print(f"  ✓ {e['verb']:<6} {e['path']:<34} {e['class']}#{e['method']}")

    if whitelisted:
        print('\n【白名单：有意不加限流】')
        for e in whitelisted:
            print(f"  – {e['verb']:<6} {e['path']:<34} {e['class']}#{e['method']}")
            print(f"      理由：{e['whitelist_reason']}")

    if missing:
        print('\n【★ 未限流且无理由（应处理）】')
        for e in missing:
            print(f"  ✗ {e['verb']:<6} {e['path']:<34} {e['class']}#{e['method']}")

    if args.json:
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        out = OUT_DIR / f'ratelimit-{args.label}.json'
        out.write_text(json.dumps({
            'total': len(endpoints), 'limited': len(limited),
            'whitelisted': len(whitelisted), 'missing': len(missing),
            'missing_detail': missing, 'endpoints': endpoints,
        }, ensure_ascii=False, indent=1), encoding='utf-8')
        print(f'\n  JSON 已写出：{out}')

    if args.max_missing is not None and len(missing) > args.max_missing:
        print(f'\n✗ 棘轮失败：未限流且无理由的接口数 {len(missing)} > 上限 {args.max_missing}')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
