# -*- coding: utf-8 -*-
"""前端技术质量自动测量（可访问性 / 性能 / 响应式 / 主题 / 实现一致性）

对应 Impeccable `audit` 的五个维度，把其中**可机械测量**的部分自动化，
输出可复现的 JSON 与 0-4 分评分，用于改版前后对比（论文证据）。

用法：
  python tools/ui-audit.py --base http://127.0.0.1:8081 --label before
  python tools/ui-audit.py --base http://127.0.0.1:8081 --label after
"""
import argparse
import json
import pathlib
import re
import subprocess
import time

from playwright.sync_api import sync_playwright

# 抽样页面（覆盖公开页 / 学生页 / 管理页三类形态）
PAGES = [
    ('login', '/login', False),
    ('home', '/home', True),
    ('detail', '/product/6', True),
    ('publish', '/product/publish', True),
    ('messages', '/messages', True),
    ('chat', '/chat/7', True),
    ('admin-dashboard', '/admin/dashboard', 'admin'),
    ('admin-products', '/admin/products', 'admin'),
]

WIDTHS = [390, 768, 1024, 1440]

# ---- 在浏览器里跑的测量脚本（返回原始数据，判定放 Python 侧，便于审计）----
MEASURE_JS = r"""
() => {
  const visible = (el) => {
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0';
  };
  const parseColor = (c) => {
    const m = c && c.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const p = m[1].split(',').map(x => parseFloat(x.trim()));
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  };
  const bgOf = (el) => {
    let n = el;
    while (n && n !== document.documentElement) {
      const c = parseColor(getComputedStyle(n).backgroundColor);
      if (c && c.a > 0.5) return c;
      n = n.parentElement;
    }
    return { r: 255, g: 255, b: 255, a: 1 };
  };

  // 1) 对比度抽样：有直接文字的元素
  const texts = [];
  const all = Array.from(document.querySelectorAll('body *'));
  for (const el of all) {
    if (texts.length >= 140) break;
    if (!visible(el)) continue;
    const own = Array.from(el.childNodes).some(n => n.nodeType === 3 && n.textContent.trim().length > 1);
    if (!own) continue;
    const cs = getComputedStyle(el);
    const fg = parseColor(cs.color);
    if (!fg) continue;
    const size = parseFloat(cs.fontSize) || 14;
    const weight = parseInt(cs.fontWeight) || 400;
    const large = size >= 24 || (size >= 18.66 && weight >= 700);
    texts.push({
      tag: el.tagName.toLowerCase(),
      cls: (el.className || '').toString().slice(0, 60),
      text: (el.textContent || '').trim().slice(0, 32),
      fg, bg: bgOf(el), fontSize: size, large,
    });
  }

  // 2) 图片与替代文本
  const imgs = Array.from(document.querySelectorAll('img')).map(i => ({
    src: (i.getAttribute('src') || '').slice(0, 80),
    alt: i.getAttribute('alt'),
    lazy: i.getAttribute('loading') === 'lazy',
    visible: visible(i),
  }));

  // 3) 可交互元素的可访问名与触控尺寸
  const interactive = [];
  for (const el of Array.from(document.querySelectorAll('button, a, input, select, textarea, [role=button]'))) {
    if (!visible(el)) continue;
    const r = el.getBoundingClientRect();
    const name = (el.getAttribute('aria-label') || el.getAttribute('title') ||
                  (el.innerText || el.value || '').trim() ||
                  (el.querySelector('img[alt]') ? el.querySelector('img[alt]').alt : '')).slice(0, 40);
    interactive.push({
      tag: el.tagName.toLowerCase(),
      cls: (el.className || '').toString().slice(0, 50),
      name,
      w: Math.round(r.width), h: Math.round(r.height),
      inlineInText: el.tagName === 'A' && el.closest('p, li, span') !== null,
      disabled: el.disabled === true,
    });
  }

  // 4) 标题层级
  const headings = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6'))
    .filter(visible).map(h => ({ level: parseInt(h.tagName[1]), text: (h.textContent || '').trim().slice(0, 30) }));

  // 5) 昂贵视觉效果
  let heavy = { filter: 0, backdrop: 0, bigShadow: 0, willChange: 0 };
  for (const el of all) {
    if (!visible(el)) continue;
    const cs = getComputedStyle(el);
    if (cs.filter && cs.filter !== 'none') heavy.filter++;
    if (cs.backdropFilter && cs.backdropFilter !== 'none') heavy.backdrop++;
    const sh = cs.boxShadow || '';
    const blur = (sh.match(/(\d+)px/g) || []).map(x => parseInt(x)).sort((a, b) => b - a)[0] || 0;
    if (blur > 24) heavy.bigShadow++;
    if (cs.willChange && cs.willChange !== 'auto') heavy.willChange++;
  }

  const nav = performance.getEntriesByType('navigation')[0] || {};
  return {
    url: location.pathname,
    domNodes: all.length,
    contrastSamples: texts,
    images: imgs,
    interactive,
    headings,
    heavy,
    timing: {
      dcl: Math.round((nav.domContentLoadedEventEnd || 0)),
      load: Math.round((nav.loadEventEnd || 0)),
      transferKb: Math.round(((nav.transferSize || 0) / 1024)),
    },
    viewport: { w: window.innerWidth, h: window.innerHeight, scrollW: document.documentElement.scrollWidth },
  };
}
"""

FOCUS_JS = r"""
() => {
  const visible = (el) => {
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
  };
  return Array.from(document.querySelectorAll('button, a, input, select, textarea, [tabindex]'))
    .filter(el => visible(el) && !el.disabled && el.tabIndex >= 0)
    .slice(0, 15)
    .map(el => {
      const before = getComputedStyle(el);
      const snap = () => `${before.outlineStyle}|${before.outlineWidth}|${before.boxShadow}|${before.borderColor}|${before.backgroundColor}`;
      return { tag: el.tagName.toLowerCase(), cls: (el.className || '').toString().slice(0, 50), rest: snap() };
    });
}
"""


def luminance(c):
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c['r']) + 0.7152 * ch(c['g']) + 0.0722 * ch(c['b'])


def contrast_ratio(fg, bg):
    l1, l2 = luminance(fg), luminance(bg)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def blend(fg, bg):
    """半透明前景与背景混合"""
    a = fg['a']
    return {k: fg[k] * a + bg[k] * (1 - a) for k in 'rgb'}


def login(page, base, who):
    username, password = ('admin', '123456') if who == 'admin' else ('stu_test01', 'abc12345')
    page.goto(f'{base}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(username)
    page.get_by_placeholder('请输入密码').fill(password)
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile(r'/home'), timeout=30000)
    page.wait_for_timeout(1200)


def score_dimensions(agg):
    """按 audit.md 的 0-4 分口径，用可测量指标打出分数（口径写进报告，保证前后可比）"""
    s = {}

    # 1 可访问性：对比度不合格比例 + 无可见焦点的可聚焦元素 + 无替代文本图片 + 无名称控件
    total_c = max(1, agg['contrastChecked'])
    bad_c = agg['contrastFail'] / total_c
    name_missing = agg['interactiveNoName'] / max(1, agg['interactiveTotal'])
    a11y = 4
    if bad_c > 0.25 or agg['focusInvisible'] > 5 or name_missing > 0.1:
        a11y = 1
    elif bad_c > 0.10 or agg['focusInvisible'] > 0 or agg['imgNoAlt'] > 0 or name_missing > 0:
        a11y = 2
    elif bad_c > 0.03:
        a11y = 3
    s['accessibility'] = a11y

    # 2 性能：DOM 规模 + 昂贵效果 + 未懒加载图片 + 首屏传输
    perf = 4
    if agg['maxDom'] > 2500 or agg['heavy']['bigShadow'] > 40:
        perf = 1
    elif agg['maxDom'] > 1500 or agg['heavy']['filter'] > 3 or agg['heavy']['backdrop'] > 3:
        perf = 2
    elif agg['imagesNoLazy'] > 5 or agg['heavy']['bigShadow'] > 10:
        perf = 3
    s['performance'] = perf

    # 3 主题：设计令牌覆盖率（此处用 CSS 变量使用量为代理指标；0 即完全硬编码）
    tokens = agg.get('tokenUsages', 0)
    theming = 0 if tokens == 0 else (1 if tokens < 30 else (2 if tokens < 120 else (3 if tokens < 400 else 4)))
    s['theming'] = theming

    # 4 响应式：横向溢出页数 + 过小触控目标
    overflow_pages = agg['overflowPages']
    small_targets = agg['smallTouchTargets']
    resp = 4
    if overflow_pages >= 3 or small_targets > 40:
        resp = 1
    elif overflow_pages >= 1 or small_targets > 15:
        resp = 2
    elif small_targets > 0:
        resp = 3
    s['responsive'] = resp

    # 5 实现一致性：detector 发现数（实现漂移的机械代理）；未测量则不计分
    d = agg.get('detectorFindings')
    if d is None:
        s['integrity'] = None
    else:
        s['integrity'] = 4 if d == 0 else (3 if d <= 2 else (2 if d <= 5 else 1))

    known = {k: v for k, v in s.items() if v is not None}
    s['total'] = sum(known.values())
    s['totalScale'] = 4 * len(known)
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='http://127.0.0.1:8081')
    ap.add_argument('--label', default='before')
    ap.add_argument('--out', default=r'D:\campus-secondhand-trade\.impeccable\review')
    args = ap.parse_args()

    out_dir = pathlib.Path(args.out) / args.label
    out_dir.mkdir(parents=True, exist_ok=True)
    pages_data = []

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='msedge', headless=True)

        # 先按角色登录一次，拿到可复用的登录态
        ctx = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
        page = ctx.new_page()
        page.set_default_timeout(30000)
        login(page, args.base, 'student')

        agg = {
            'contrastChecked': 0, 'contrastFail': 0, 'contrastFailSamples': [],
            'imgNoAlt': 0, 'imagesNoLazy': 0, 'interactiveTotal': 0, 'interactiveNoName': 0,
            'focusInvisible': 0, 'smallTouchTargets': 0, 'overflowPages': 0,
            'maxDom': 0, 'heavy': {'filter': 0, 'backdrop': 0, 'bigShadow': 0, 'willChange': 0},
            'headingJumps': 0,
        }

        for name, path, need in PAGES:
            if need == 'admin':
                ctx2 = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
                p2 = ctx2.new_page()
                p2.set_default_timeout(30000)
                login(p2, args.base, 'admin')
                target = p2
            else:
                ctx2, p2, target = None, None, page

            # --- 桌面测量 ---
            target.set_viewport_size({'width': 1440, 'height': 1000})
            target.goto(args.base + path, wait_until='domcontentloaded')
            try:
                target.wait_for_load_state('networkidle', timeout=10000)
            except Exception:
                pass
            target.wait_for_timeout(900)
            data = target.evaluate(MEASURE_JS)

            # 对比度判定
            for s in data['contrastSamples']:
                fg = s['fg']
                bg = s['bg']
                if fg['a'] < 1:
                    fg = blend(fg, bg)
                ratio = contrast_ratio(fg, bg)
                need_ratio = 3.0 if s['large'] else 4.5
                agg['contrastChecked'] += 1
                if ratio < need_ratio:
                    agg['contrastFail'] += 1
                    if len(agg['contrastFailSamples']) < 12:
                        agg['contrastFailSamples'].append({
                            'page': name, 'tag': s['tag'], 'cls': s['cls'], 'text': s['text'],
                            'ratio': round(ratio, 2), 'need': need_ratio, 'fontSize': s['fontSize'],
                        })

            agg['imgNoAlt'] += sum(1 for i in data['images'] if i['alt'] is None)
            agg['imagesNoLazy'] += sum(1 for i in data['images'] if i['visible'] and not i['lazy'])
            agg['interactiveTotal'] += len(data['interactive'])
            agg['interactiveNoName'] += sum(1 for i in data['interactive'] if not i['name'] and not i['disabled'])
            agg['maxDom'] = max(agg['maxDom'], data['domNodes'])
            for k in agg['heavy']:
                agg['heavy'][k] = max(agg['heavy'][k], data['heavy'].get(k, 0))

            # 标题层级跳级
            prev = 0
            for h in data['headings']:
                if prev and h['level'] > prev + 1:
                    agg['headingJumps'] += 1
                prev = h['level']

            # 焦点可见性（前 12 个可聚焦元素）
            focus_probe = []
            for _ in range(12):
                target.keyboard.press('Tab')
                target.wait_for_timeout(60)
                snap = target.evaluate("""() => {
                  const el = document.activeElement;
                  if (!el || el === document.body) return null;
                  const cs = getComputedStyle(el);
                  return {tag: el.tagName.toLowerCase(), cls: (el.className||'').toString().slice(0,40),
                          style: `${cs.outlineStyle}|${cs.outlineWidth}|${cs.boxShadow}|${cs.borderColor}`};
                }""")
                if snap:
                    focus_probe.append(snap)
            invisible = sum(1 for s in focus_probe
                            if s['style'].startswith('none|0px|none|') and 'none' in s['style'].split('|')[2])
            agg['focusInvisible'] += invisible

            # --- 响应式：多宽度横向溢出 + 移动端触控目标 ---
            widths_result = {}
            for w in WIDTHS:
                target.set_viewport_size({'width': w, 'height': 900})
                target.wait_for_timeout(400)
                m = target.evaluate("""() => ({sw: document.documentElement.scrollWidth, iw: window.innerWidth})""")
                overflow = m['sw'] > m['iw'] + 2
                widths_result[w] = {'scrollWidth': m['sw'], 'overflow': overflow}
                if overflow and w == 390:
                    agg['overflowPages'] += 1
            if 390 in widths_result:
                target.set_viewport_size({'width': 390, 'height': 844})
                target.wait_for_timeout(400)
                # 口径固定（与 before/tokens 档位一致，保证前后可比）：
                # 逐个可交互元素量其自身矩形，正文内联链接豁免。
                # 注意：这会把"输入框内部的 input"也算一个目标（其外层包装才是真实命中区），
                # 因此该数字偏高、只用于同口径对比；真实命中区的改善另由 tools/ui-a11y.py
                # 与专项探针报告（例如窄屏下按钮高度 32px → 42px）。
                small = target.evaluate("""() => {
                  const vis = (el) => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
                    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'; };
                  let n = 0;
                  for (const el of document.querySelectorAll('button, a, input, select, textarea, [role=button]')) {
                    if (!vis(el)) continue;
                    const r = el.getBoundingClientRect();
                    const inlineText = el.tagName === 'A' && el.closest('p, li, span') !== null;
                    if (!inlineText && (r.width < 44 || r.height < 44)) n++;
                  }
                  return n;
                }""")
                agg['smallTouchTargets'] += small

            pages_data.append({
                'page': name, 'path': path,
                'domNodes': data['domNodes'],
                'timing': data['timing'],
                'contrastSamples': len(data['contrastSamples']),
                'interactive': len(data['interactive']),
                'headings': data['headings'][:8],
                'heavy': data['heavy'],
                'responsive': widths_result,
            })
            print(f"  测量 {name:16s} DOM={data['domNodes']:5d}  对比度样本={len(data['contrastSamples']):3d}  "
                  f"可交互={len(data['interactive']):3d}  溢出(390)={'是' if widths_result.get(390, {}).get('overflow') else '否'}")

            if ctx2:
                ctx2.close()
        ctx.close()
        browser.close()

    # 设计令牌使用量（来自 ui-inventory）
    inv = out_dir / 'inventory.json'
    token_usages = 0
    if inv.exists():
        token_usages = json.loads(inv.read_text(encoding='utf-8'))['tokens']['usages']
    agg['tokenUsages'] = token_usages

    # detector：本 label 目录没有就现跑一次，绝不默认成 0 条（那会白送分）
    det = out_dir / 'detect.json'
    if not det.exists():
        print('  （本 label 尚无 detect.json，现跑一次 impeccable detect）')
        imp = pathlib.Path(r'D:\campus-secondhand-trade\.dsh\skills\impeccable\scripts\impeccable.cmd')
        try:
            # 直接把参数交给 cmd.exe，避免 cmd 的引号剥离规则（整串加引号反而会失败）
            r = subprocess.run(['cmd.exe', '/c', str(imp), 'detect', '--json', 'frontend/src'],
                               cwd=r'D:\campus-secondhand-trade', capture_output=True,
                               text=True, encoding='utf-8', errors='ignore', timeout=300)
            raw = (r.stdout or '').strip()
            start = raw.find('[')
            if start >= 0:
                det.write_text(raw[start:], encoding='utf-8')
                print('    ✓ detector 已运行并保存')
            else:
                print('    detector 无 JSON 输出；stderr:', (r.stderr or '')[:200])
        except Exception as e:
            print('    detector 运行失败：', e)

    if det.exists():
        try:
            findings = json.loads(det.read_text(encoding='utf-8-sig').lstrip('\ufeff'))
            agg['detectorFindings'] = len(findings)
        except Exception as e:
            agg['detectorFindings'] = None
            print('    detector 结果无法解析：', e)
    else:
        agg['detectorFindings'] = None
        print('    ✗ 拿不到 detector 结果，一致性维度记为未测量（不计分）')

    scores = score_dimensions(agg)
    report = {
        'label': args.label,
        'base': args.base,
        'capturedAt': time.strftime('%Y-%m-%d %H:%M:%S'),
        'aggregate': agg,
        'scores': scores,
        'pages': pages_data,
    }
    (out_dir / 'audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    print('\n===== 技术质量测量（%s）=====' % args.label)
    print(f"  对比度：检查 {agg['contrastChecked']} 处，不达标 {agg['contrastFail']} 处（{agg['contrastFail']*100//max(1,agg['contrastChecked'])}%）")
    print(f"  图片：无 alt {agg['imgNoAlt']} 张，未懒加载 {agg['imagesNoLazy']} 张")
    print(f"  可交互元素：{agg['interactiveTotal']} 个，无可见名称 {agg['interactiveNoName']} 个")
    print(f"  键盘焦点：不可见的可聚焦元素 {agg['focusInvisible']} 个；标题跳级 {agg['headingJumps']} 处")
    print(f"  响应式：390px 下横向溢出页 {agg['overflowPages']} 个；过小触控目标 {agg['smallTouchTargets']} 个")
    print(f"  性能代理：最大 DOM {agg['maxDom']} 节点；大阴影 {agg['heavy']['bigShadow']} 处；filter/backdrop {agg['heavy']['filter']}/{agg['heavy']['backdrop']}")
    print(f"  设计令牌使用：{token_usages} 处；detector 发现：{agg.get('detectorFindings')} 条")
    ints = scores.get('integrity')
    ints_txt = '未测量' if ints is None else f"{ints}/4"
    print(f"\n  评分：A11y {scores['accessibility']}/4  性能 {scores['performance']}/4  "
          f"主题 {scores['theming']}/4  响应式 {scores['responsive']}/4  一致性 {ints_txt}"
          f"  →  总分 {scores['total']}/{scores.get('totalScale', 20)}")
    print(f"  已保存：{out_dir / 'audit.json'}")


if __name__ == '__main__':
    main()
