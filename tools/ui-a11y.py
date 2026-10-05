# -*- coding: utf-8 -*-
"""可访问性审计（键盘可达性 / 焦点可见性 / 语义结构 / 错误关联）

与 tools/ui-audit.py 的分工：
  · ui-audit.py  偏"视觉与响应式的技术质量"（对比度、溢出、触控目标、DOM、特效）+ 五维评分
  · ui-a11y.py   偏"键盘与辅助技术可用性"（焦点是否可见、鼠标独占的交互元素、
                 图片替代文本、标题层级与地标、表单错误的 aria 关联）

用法：
  python tools/ui-a11y.py --label before
  python tools/ui-a11y.py --label after --base http://127.0.0.1:8081
"""
import argparse
import collections
import json
import pathlib
import re
import time

from playwright.sync_api import sync_playwright

PROJECT = pathlib.Path(__file__).resolve().parent.parent

PAGES = [
    ('home', '/home', 'student'),
    ('detail', '/product/6', 'student'),
    ('publish', '/product/publish', 'student'),
    ('messages', '/messages', 'student'),
    ('chat', '/chat/7', 'student'),
    ('admin-dashboard', '/admin/dashboard', 'admin'),
    ('login', '/login', None),
]

# 页面加载后：给可交互元素打标并记录未聚焦基线
TAG_BASELINE = r"""
() => {
  const wrapOf = (el) => el.closest('.el-input__wrapper, .el-select__wrapper, .el-textarea__inner') || el;
  const snap = (el) => {
    const cs = getComputedStyle(el);
    return [cs.outlineStyle, cs.outlineWidth, cs.outlineColor, cs.boxShadow,
            cs.backgroundColor, cs.borderColor].join('|');
  };
  const els = Array.from(document.querySelectorAll(
    'a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"])'
  )).filter(el => {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && !el.disabled;
  });
  const base = {};
  els.forEach((el, i) => { el.setAttribute('data-a11y-idx', String(i)); base[i] = { self: snap(el), wrap: snap(wrapOf(el)) }; });
  return { focusable: els.length, base };
}
"""

READ_ACTIVE = r"""
() => {
  const wrapOf = (el) => el.closest('.el-input__wrapper, .el-select__wrapper, .el-textarea__inner') || el;
  const snap = (el) => {
    const cs = getComputedStyle(el);
    return [cs.outlineStyle, cs.outlineWidth, cs.outlineColor, cs.boxShadow,
            cs.backgroundColor, cs.borderColor].join('|');
  };
  const el = document.activeElement;
  if (!el || el === document.body) return null;
  return {
    idx: el.getAttribute('data-a11y-idx'),
    tag: el.tagName.toLowerCase(),
    cls: (el.className || '').toString().slice(0, 50),
    text: (el.innerText || el.value || el.placeholder || '').toString().trim().slice(0, 16),
    focusVisible: el.matches(':focus-visible'),
    self: snap(el), wrap: snap(wrapOf(el)),
  };
}
"""

STRUCTURE = r"""
() => {
  const visible = (el) => {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none';
  };
  const imgs = Array.from(document.querySelectorAll('img')).filter(visible)
    .map(i => ({ src: (i.getAttribute('src') || '').split('/').pop().slice(0, 28), alt: i.getAttribute('alt') }));
  const headings = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6')).filter(visible)
    .map(h => ({ level: Number(h.tagName[1]), text: (h.textContent || '').trim().slice(0, 20) }));
  // 鼠标独占的疑似交互元素：光标是指针、但自身不可聚焦、也没有可聚焦后代。
  // 注意：必须排除"位于可聚焦祖先之内"的元素（例如 logo 的各个 span 只是继承了
  // 外层链接的 cursor:pointer，本身随链接一起可键盘聚焦），否则会大量误报。
  const mouseOnly = [];
  for (const el of Array.from(document.querySelectorAll('div, span, li, section, article'))) {
    if (!visible(el)) continue;
    const cs = getComputedStyle(el);
    if (cs.cursor !== 'pointer') continue;
    if (el.closest('a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"])')) continue;
    if (el.getAttribute('role') || el.hasAttribute('tabindex')) continue;
    if (el.querySelector('a[href], button, input, [tabindex]:not([tabindex="-1"])')) continue;
    const txt = (el.innerText || '').trim().slice(0, 26);
    mouseOnly.push({ tag: el.tagName.toLowerCase(), cls: (el.className || '').toString().slice(0, 46), text: txt });
    if (mouseOnly.length >= 12) break;
  }
  const interactive = Array.from(document.querySelectorAll('button, a, input, select, textarea, [role=button]'))
    .filter(visible).map(el => {
      const name = (el.getAttribute('aria-label') || el.getAttribute('title') ||
                    (el.innerText || el.value || '').trim() ||
                    (el.querySelector('img[alt]') ? el.querySelector('img[alt]').alt : ''));
      return { tag: el.tagName.toLowerCase(), cls: (el.className || '').toString().slice(0, 40), name: name.slice(0, 30) };
    });
  return {
    images: imgs,
    headings,
    landmarks: { nav: document.querySelectorAll('nav').length, main: document.querySelectorAll('main').length,
                 header: document.querySelectorAll('header').length, footer: document.querySelectorAll('footer').length },
    mouseOnly,
    interactive,
  };
}
"""


def login(page, base, who):
    user, pwd = ('admin', '123456') if who == 'admin' else ('stu_test01', 'abc12345')
    page.goto(f'{base}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(user)
    page.get_by_placeholder('请输入密码').fill(pwd)
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile(r'/home'), timeout=30000)
    page.wait_for_timeout(1200)


def audit_page(page, base, path, max_tabs=25):
    page.goto(base + path, wait_until='domcontentloaded')
    try:
        page.wait_for_load_state('networkidle', timeout=10000)
    except Exception:
        pass
    page.wait_for_timeout(1200)

    page.keyboard.press('Tab')
    page.evaluate("document.activeElement && document.activeElement.blur()")
    page.wait_for_timeout(150)
    base_info = page.evaluate(TAG_BASELINE)
    basemap = {int(k): v for k, v in base_info['base'].items()}

    stops, invisible = [], []
    seen = set()
    for _ in range(max_tabs):
        page.keyboard.press('Tab')
        page.wait_for_timeout(90)
        cur = page.evaluate(READ_ACTIVE)
        if not cur or cur['idx'] is None:
            continue
        idx = int(cur['idx'])
        if idx in seen:
            continue
        seen.add(idx)
        bl = basemap.get(idx)
        # 基线里 self / wrap 都是拼接好的字符串快照，直接比较即可
        changed = (bl is None) or (cur['self'] != bl['self']) or (cur['wrap'] != bl['wrap'])
        rec = {'tag': cur['tag'], 'cls': cur['cls'], 'text': cur['text'],
               'focusVisible': cur['focusVisible'], 'visibleChange': changed}
        stops.append(rec)
        if not changed:
            invisible.append(rec)

    struct = page.evaluate(STRUCTURE)
    alts = [i['alt'] for i in struct['images']]
    dup_alt = {a: n for a, n in collections.Counter(alts).items() if a and n > 1}
    jump = 0
    prev = 0
    for h in struct['headings']:
        if prev and h['level'] > prev + 1:
            jump += 1
        prev = h['level']
    noname = [i for i in struct['interactive'] if not i['name']]

    return {
        'page': path,
        'focusable': base_info['focusable'],
        'tabStops': len(stops),
        'focusInvisible': len(invisible),
        'focusInvisibleSamples': invisible[:5],
        'images': len(struct['images']),
        'imagesNoAlt': sum(1 for a in alts if a is None),
        'duplicateAlt': dup_alt,
        'h1': sum(1 for h in struct['headings'] if h['level'] == 1),
        'headingCount': len(struct['headings']),
        'headingJumps': jump,
        'landmarks': struct['landmarks'],
        'mouseOnlyTargets': len(struct['mouseOnly']),
        'mouseOnlySamples': struct['mouseOnly'][:5],
        'interactiveNoName': len(noname),
        'interactiveNoNameSamples': noname[:5],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='http://127.0.0.1:8081')
    ap.add_argument('--label', default='before')
    ap.add_argument('--out', default=str(PROJECT / '.impeccable' / 'review'))
    args = ap.parse_args()

    out_dir = pathlib.Path(args.out) / args.label
    out_dir.mkdir(parents=True, exist_ok=True)
    results = []

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='msedge', headless=True)
        student_ctx = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
        student = student_ctx.new_page()
        student.set_default_timeout(30000)
        login(student, args.base, 'student')
        admin_ctx = admin_page = None

        for name, path, who in PAGES:
            try:
                if who == 'admin':
                    if admin_page is None:
                        admin_ctx = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
                        admin_page = admin_ctx.new_page()
                        admin_page.set_default_timeout(30000)
                        login(admin_page, args.base, 'admin')
                    target = admin_page
                elif who is None:
                    ctx = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
                    target = ctx.new_page()
                    target.set_default_timeout(30000)
                else:
                    target = student
                r = audit_page(target, args.base, path)
                r['name'] = name
                results.append(r)
                print(f"  {name:16s} 焦点站={r['tabStops']:3d} 不可见={r['focusInvisible']:2d} "
                      f"鼠标独占={r['mouseOnlyTargets']:3d} h1={r['h1']} 无alt={r['imagesNoAlt']:2d} "
                      f"无名称={r['interactiveNoName']:2d}")
            except Exception as e:                                    # noqa: BLE001
                print(f'  {name:16s} 失败：{str(e)[:90]}')
                results.append({'name': name, 'page': path, 'error': str(e)[:160]})

        # 登录页表单错误的 aria 关联
        form_aria = None
        try:
            ctx = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
            p = ctx.new_page()
            p.set_default_timeout(20000)
            p.goto(f'{args.base}/login', wait_until='domcontentloaded')
            p.wait_for_timeout(700)
            p.get_by_role('button', name=re.compile(r'登\s*录')).click()
            p.wait_for_timeout(1200)
            form_aria = p.evaluate(r"""() => ({
              errors: Array.from(document.querySelectorAll('.el-form-item__error')).map(e => (e.textContent||'').trim().slice(0,20)),
              inputs: Array.from(document.querySelectorAll('input')).map(i => ({
                ph: i.getAttribute('placeholder'),
                ariaInvalid: i.getAttribute('aria-invalid'),
                ariaDescribedby: i.getAttribute('aria-describedby'),
              })),
            })""")
            ctx.close()
        except Exception as e:                                        # noqa: BLE001
            form_aria = {'error': str(e)[:120]}

        student_ctx.close()
        if admin_ctx:
            admin_ctx.close()
        browser.close()

    agg = {
        'pagesAudited': len([r for r in results if 'error' not in r]),
        'focusInvisibleTotal': sum(r.get('focusInvisible', 0) for r in results),
        'mouseOnlyTotal': sum(r.get('mouseOnlyTargets', 0) for r in results),
        'pagesWithoutH1': sum(1 for r in results if r.get('h1', 0) == 0 and 'error' not in r),
        'imagesNoAltTotal': sum(r.get('imagesNoAlt', 0) for r in results),
        'duplicateAltPages': sum(1 for r in results if r.get('duplicateAlt')),
        'interactiveNoNameTotal': sum(r.get('interactiveNoName', 0) for r in results),
        'pagesWithoutNavLandmark': sum(1 for r in results if r.get('landmarks', {}).get('nav', 0) == 0 and 'error' not in r),
        'formAriaMissing': 0 if not form_aria or 'error' in (form_aria or {}) else
            sum(1 for i in form_aria['inputs'] if not i['ariaInvalid'] and not i['ariaDescribedby']),
        'capturedAt': time.strftime('%Y-%m-%d %H:%M:%S'),
    }
    report = {'label': args.label, 'base': args.base, 'aggregate': agg, 'pages': results, 'formAria': form_aria}
    (out_dir / 'a11y.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    print('\n===== 可访问性审计（%s）=====' % args.label)
    print(f"  键盘焦点不可见的 Tab 站：{agg['focusInvisibleTotal']}")
    print(f"  鼠标独占的疑似交互元素：{agg['mouseOnlyTotal']}")
    print(f"  无 h1 的页面：{agg['pagesWithoutH1']}/{agg['pagesAudited']}；无 nav 地标的页面：{agg['pagesWithoutNavLandmark']}/{agg['pagesAudited']}")
    print(f"  图片无 alt：{agg['imagesNoAltTotal']}；存在重复 alt 的页面：{agg['duplicateAltPages']}")
    print(f"  无可访问名称的可交互元素：{agg['interactiveNoNameTotal']}")
    print(f"  表单错误缺乏 aria 关联的输入框：{agg['formAriaMissing']}")
    print(f"\n  已保存：{out_dir / 'a11y.json'}")


if __name__ == '__main__':
    main()
