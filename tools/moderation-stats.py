# -*- coding: utf-8 -*-
"""机审效果统计（内容治理第一层的量化依据）

回答三个问题：
  1. 机审把多少商品直接放行、多少转人工复核、多少直接拒绝？
  2. 命中的是哪些规则、哪些词？（词表调优的依据）
  3. 机审的效果怎么评估？误报/漏报怎么算？（如实说明方法，不含糊）

数据来源：后端日志里的统一格式行（v0.17 起三种结果都记录）
    [机审] action=PASS   sellerId=5 title=xxx
    [机审] action=REVIEW sellerId=5 title=xxx hits=[R2-联系方式(description:13812345678)]
    [机审] action=REJECT sellerId=5 title=xxx hits=[R1-违禁词(title:代写)] reason=...

用法：
  python tools/moderation-stats.py                    # 统计现有日志并打印
  python tools/moderation-stats.py --report           # 额外写出 docs/机审效果统计.md
  python tools/moderation-stats.py --demo 12          # 先造 12 条样本流量（标题以 E2E 开头，可被清理）
  python tools/moderation-stats.py --demo 12 --report # 造流量 + 出报告
"""
import argparse
import collections
import datetime as dt
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOG_DIRS = [ROOT / 'deploy' / 'windows' / 'logs']
REPORT = ROOT / 'docs' / '机审效果统计.md'
API = 'http://127.0.0.1:8080'
SELLER = ('stu_test01', 'abc12345')

# 日志里的一条机审记录：只要求到 title 为止，后面的 hits / reason 单独解析，
# 这样即使命中明细里含有空格或特殊字符也不会整行匹配失败。
LINE_RE = re.compile(
    r'\[机审\]\s+action=(?P<action>PASS|REVIEW|REJECT)\s+sellerId=(?P<seller>\d+)\s+title=(?P<title>.*)$')
HITS_PART_RE = re.compile(r'hits=(?P<hits>\[.*?\])')
REASON_PART_RE = re.compile(r'reason=(?P<reason>.*)$')
HIT_RE = re.compile(r'(?P<rule>[\w\-]+)\((?P<field>\w+):(?P<word>[^)]*)\)')   # \w 含中文：规则名形如 R1-违禁词

# 样本流量：覆盖三种结果与多种规则，用于生成有意义的分布
DEMO_ITEMS = [
    ('专业代写毕业论文包过', '各科都能写，价格好谈', 'REJECT'),
    ('代办各类证件 当天出', '身份证学生证均可', 'REJECT'),
    ('出售管制刀具 可邮寄', '开刃，图便宜出', 'REJECT'),
    ('成人视频资源打包', '网盘发货', 'REJECT'),
    ('四级词汇书 九成新', '有意者联系 13812345678', 'REVIEW'),
    ('闲置机械键盘', '加微信 abc123456 详聊', 'REVIEW'),
    ('闲置显示器 27寸', '详情见 http://example.com/item', 'REVIEW'),
    ('考研数学复习全书', '有少量笔记，东校区自提', 'PASS'),
    ('宿舍小台灯 护眼款', '用了半年，无损坏', 'PASS'),
    ('篮球 斯伯丁 7号', '打过几次，成色好', 'PASS'),
    ('高等数学教材 上册', '大一用过，图书馆门口面交', 'PASS'),
    ('iPad 保护壳 全新', '买重了，出掉一个', 'PASS'),
]


def read_logs():
    """读取所有日志里的机审行。

    为什么不能只用一种编码：后端日志是「控制台 appender（跟随系统编码，本机为 GBK）」
    与「文件 appender（UTF-8）」混合写入的，同一次运行里两种编码可能并存。
    因此对每个文件分别用 utf-8 / gbk 解码，取"机审行更多"的那一次结果。
    """
    best = {}
    for d in LOG_DIRS:
        if not d.exists():
            continue
        for f in sorted(d.glob('*.log')):
            raw = f.read_bytes()
            for enc in ('utf-8', 'gbk'):
                text = raw.decode(enc, errors='replace')
                hits = [(f.name, ln) for ln in text.splitlines() if '[机审]' in ln]
                if f.name not in best or len(hits) > len(best[f.name][1]):
                    best[f.name] = (enc, hits)
    out = []
    for name, (_enc, hits) in sorted(best.items()):
        out.extend(hits)
    return out


def parse(lines):
    records, seen = [], set()
    for src, ln in lines:
        m = LINE_RE.search(ln)
        if not m:
            continue
        action, seller = m.group('action'), m.group('seller')
        tail = (m.group('title') or '').strip()
        hm = HITS_PART_RE.search(tail)
        hits = [h.groupdict() for h in HIT_RE.finditer(hm.group('hits'))] if hm else []
        rm = REASON_PART_RE.search(tail)
        reason = (rm.group('reason') or '').strip() if rm else ''
        clean_title = (HITS_PART_RE.split(tail)[0] or '').strip()[:40]
        # 去重键必须包含时间戳：它用来去掉"同一行被两种编码各读一次"的重复，
        # 但不能把同一商品被重复发布的两次真实记录也合并掉。
        key = (action, seller, clean_title, ln[:19])
        if key in seen:
            continue
        seen.add(key)
        records.append({'action': action, 'seller': seller, 'title': clean_title,
                        'hits': hits, 'reason': reason, 'time': ln[:19], 'source': src})
    return records


def summarize(records):
    total = len(records)
    by_action = collections.Counter(r['action'] for r in records)
    by_rule = collections.Counter(h['rule'] for r in records for h in r['hits'])
    by_word = collections.Counter(h['word'] for r in records for h in r['hits'])
    by_field = collections.Counter(h['field'] for r in records for h in r['hits'])
    return {
        'total': total,
        'by_action': dict(by_action),
        'by_rule': dict(by_rule.most_common()),
        'by_word': dict(by_word.most_common(15)),
        'by_field': dict(by_field),
        'ratio': {k: (v / total * 100 if total else 0) for k, v in by_action.items()},
    }


def print_summary(s):
    if not s['total']:
        print('  日志里还没有机审记录。可先造样本流量：--demo 12')
        return
    print(f"  机审样本总数: {s['total']}")
    print('  ' + '-' * 62)
    labels = {'PASS': '放行（直接上架）', 'REVIEW': '转人工复核（待审核）', 'REJECT': '拒绝发布'}
    for k in ('PASS', 'REVIEW', 'REJECT'):
        v = s['by_action'].get(k, 0)
        print(f"    {labels[k]:<22} {v:>4} 条   占比 {s['ratio'].get(k, 0):5.1f}%")
    print('\n  命中规则分布:')
    for k, v in s['by_rule'].items():
        print(f'    {k:<20} {v:>4} 次')
    print('\n  命中字段: ' + '，'.join(f'{k} {v} 次' for k, v in s['by_field'].items()))
    print('\n  命中词 TOP:')
    for k, v in list(s['by_word'].items())[:10]:
        print(f'    {k:<18} {v:>4} 次')


def write_report(s, records):
    now = dt.datetime.now().strftime('%Y-%m-%d %H:%M')
    lines = [
        '# 机审效果统计（内容治理第一层）', '',
        f'> 统计时间：{now}　·　数据来源：后端运行日志中的 `[机审]` 结构化记录',
        f'> 生成方式：`python tools/moderation-stats.py --report`', '',
        '## 一、为什么要有这份统计', '',
        '内容机审上线后，"它到底拦住了多少、放错了多少"必须有数字回答，',
        '否则词表只能凭感觉维护。本报告把机审结果量化，作为**词表调优与效果评估的依据**。', '',
        '## 二、统计口径', '',
        '| 结果 | 含义 | 后续动作 |',
        '| --- | --- | --- |',
        '| PASS | 未命中任何规则 | 直接上架（status=1） |',
        '| REVIEW | 命中可疑规则（联系方式引流、价格异常） | 转「待审核」（status=0）交人工复核 |',
        '| REJECT | 命中明确违禁规则 | 拒绝发布，返回具体原因 |', '',
        '数据来自发布时写入的统一日志行：',
        '```',
        '[机审] action=REJECT sellerId=5 title=xxx hits=[R1-违禁词(title:代写)] reason=...',
        '```', '',
        '## 三、统计结果', '',
    ]
    if not s['total']:
        lines.append('_当前日志中没有机审记录。_')
    else:
        t = s['total']
        lines += [f'样本总数：**{t}** 条', '',
                  '| 结果 | 条数 | 占比 |', '| --- | ---: | ---: |']
        for k, label in (('PASS', '放行'), ('REVIEW', '转人工复核'), ('REJECT', '拒绝')):
            v = s['by_action'].get(k, 0)
            lines.append(f'| {label} | {v} | {v / t * 100:.1f}% |')
        lines += ['', '### 命中规则分布', '', '| 规则 | 次数 |', '| --- | ---: |']
        for k, v in s['by_rule'].items():
            lines.append(f'| {k} | {v} |')
        lines += ['', '### 命中词 TOP10', '', '| 命中词 | 次数 |', '| --- | ---: |']
        for k, v in list(s['by_word'].items())[:10]:
            lines.append(f'| {k} | {v} |')
        lines += ['', '### 命中字段', '',
                  '| 字段 | 次数 |', '| --- | ---: |']
        for k, v in s['by_field'].items():
            lines.append(f'| {k} | {v} |')
        lines += ['', '### 被拒样本（示例）', '']
        for r in [x for x in records if x['action'] == 'REJECT'][:8]:
            lines.append(f"- `{r['title']}` —— {r['reason'] or '命中违禁规则'}")
    lines += [
        '', '## 四、效果评估方法（如实说明）', '',
        '### 4.1 误报（False Positive）', '',
        '**定义**：应该放行的商品被判成 REVIEW/REJECT。', '',
        '**测量方法**：人工复核「转人工复核」队列 —— 管理员在后台逐条判定，',
        '记录"实际合规"的条数。误报率 = 实际合规数 ÷ 转审总数。',
        '本平台该队列天然存在（机审转审 → 后台审核页），因此**测量成本很低**。', '',
        '### 4.2 漏报（False Negative）', '',
        '**定义**：应该拦截的商品被放行（PASS）。', '',
        '**测量方法**：无法用日志直接得出（放行的商品不会被逐条人工检查）。',
        '可行做法是**抽样复核**：每天随机抽取 N 条 PASS 商品人工检查，',
        '若发现违规则反推漏报率；样本量足够时可用于估计词表覆盖率。', '',
        '### 4.3 词表调优闭环', '',
        '```',
        '命中词 TOP 分析 → 高频词确认有效性 / 低频词检查是否冗余',
        '转审队列人工判定 → 误报高的词降级（REJECT 降为 REVIEW）或移出词表',
        '抽样复核放行商品 → 发现漏报 → 补充新词/新规则',
        '```', '',
        '## 五、已知局限', '',
        '| 局限 | 说明 |',
        '| --- | --- |',
        '| 统计基于日志 | 日志轮转后会丢失历史；若要长期留存应改为落库（moderation_log 表） |',
        '| 未区分来源 | 自动化测试产生的样本与真实用户样本混在一起，分析时应先清理 E2E 夹具 |',
        '| 误报/漏报尚未实测 | 方法已明确（见第四节），但需要人工复核队列的真实数据才能给出数字 |',
        '| 未覆盖图片 | 机审只审标题与描述，图片内容审核未纳入 |',
    ]
    REPORT.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f'  ✓ 报告已写出：{REPORT}')


def post(path, payload, token=None):
    req = urllib.request.Request(
        API + path, data=json.dumps(payload).encode(),
        headers={'Content-Type': 'application/json',
                 **({'Authorization': 'Bearer ' + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode() or '{}')


def demo(n, delay=0.0):
    """造样本流量：标题以 E2E 开头，事后可用 clean-test-data.py 清理。

    ⚠️ 发布接口有速率限制（@RateLimit：每用户 10 次 / 300 秒），
    一次造太多会被限流，返回的不是发布结果 —— 此时脚本会明确打印真实返回码，
    不会把"被限流"误算成"机审判定为 REVIEW"。
    """
    r = post('/api/user/login', {'username': SELLER[0], 'password': SELLER[1]})
    token = (r.get('data') or {}).get('token')
    if not token:
        print('  ✗ 登录失败，无法造样本')
        return 1
    tag = uuid.uuid4().hex[:5]
    print(f'  造样本流量 {n} 条（TAG={tag}；发布接口限流 10 次/300 秒）')
    ok, limited = 0, 0
    for i in range(n):
        title, desc, expect = DEMO_ITEMS[i % len(DEMO_ITEMS)]
        res = post('/api/product/publish', {
            'title': f'E2E机审样本-{tag}-{title}', 'description': desc,
            'categoryId': 1, 'price': '88.50', 'originalPrice': None,
            'conditionLevel': 2, 'campus': '东校区', 'tradePlace': '图书馆门口',
            'imageUrls': []}, token)
        code = res.get('code')
        status = (res.get('data') or {}).get('status')
        if code == 3010:
            got = 'REJECT'
        elif code == 200 and status == 1:
            got = 'PASS'
        elif code == 200 and status == 0:
            got = 'REVIEW'
        else:
            got = f'未判定(code={code})'
            limited += (code not in (200, 3010))
        flag = '✓' if got == expect else ('-' if '未判定' in got else '✗')
        print(f'    {flag} 期望 {expect:<6} 实际 {got:<16} {title[:20]}')
        ok += (got == expect)
        if delay:
            time.sleep(delay)
    print(f'  与预期一致 {ok}/{n}' + (f'；另有 {limited} 条被接口限流（非机审判定）' if limited else ''))
    return 0


def main():
    ap = argparse.ArgumentParser(description='机审效果统计')
    ap.add_argument('--demo', type=int, default=0, help='先造 N 条样本流量')
    ap.add_argument('--report', action='store_true', help='写出 docs/机审效果统计.md')
    ap.add_argument('--json', action='store_true', help='以 JSON 输出汇总')
    args = ap.parse_args()

    if args.demo:
        demo(args.demo)

    lines = read_logs()
    records = parse(lines)
    s = summarize(records)

    if args.json:
        print(json.dumps(s, ensure_ascii=False, indent=1))
    else:
        print(f'  扫描日志 {len(lines)} 行机审相关记录')
        print_summary(s)
    if args.report:
        write_report(s, records)
    return 0


if __name__ == '__main__':
    sys.exit(main())
