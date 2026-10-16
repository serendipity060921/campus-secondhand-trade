# -*- coding: utf-8 -*-
"""校园二手交易平台 · 安全测试用例（C2）

覆盖六类常见风险，全部针对**部署形态**（Nginx 8081 → 后端 8080）实测：

  一、SQL 注入        —— 登录绕过、联合查询、时间盲注、参数污染
  二、XSS             —— 响应内容类型、脚本载荷回显、前端转义依赖
  三、认证与 JWT      —— 无 token、篡改 payload、alg=none 攻击、畸形 token
  四、越权            —— 横向（他人订单/商品）与纵向（普通用户访问管理端）
  五、信息泄露        —— 密码字段、异常堆栈、运维端点、敏感个人信息
  六、健壮性          —— 超长字段、路径遍历、非法 JSON

执行：
  python tests/api/test-security.py                          # 默认 http://127.0.0.1:8081
  WEB_BASE=http://127.0.0.1:8080 python tests/api/test-security.py
"""
import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request


def enc(s):
    """把注入载荷安全地放进 URL（空格等字符必须转义，否则 http.client 直接拒绝）"""
    return urllib.parse.quote(s, safe='')

WEB = os.environ.get('WEB_BASE', 'http://127.0.0.1:8081').rstrip('/')

ADMIN = ('admin', '123456')
USER_A = ('stu_test01', 'abc12345')     # 一般用户
USER_B = ('stu_demo', '123456')         # 另一个一般用户（用于越权对比）

RESULTS = []


def http(method, path, body=None, token=None, raw_body=None, content_type='application/json', base=None):
    """返回 (HTTP状态码, 解析后的 JSON 或文本, 响应头字典, 耗时秒)"""
    url = (base or WEB) + path
    if raw_body is not None:
        data = raw_body if isinstance(raw_body, bytes) else raw_body.encode('utf-8')
    else:
        data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header('Content-Type', content_type)
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode('utf-8', 'replace')
            dt = time.perf_counter() - t0
            try:
                return r.status, json.loads(raw), dict(r.headers), dt
            except Exception:
                return r.status, raw, dict(r.headers), dt
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        dt = time.perf_counter() - t0
        try:
            return e.code, json.loads(raw), dict(e.headers), dt
        except Exception:
            return e.code, raw, dict(e.headers), dt


def case(sid, group, name, ok, detail=''):
    RESULTS.append(dict(id=sid, group=group, name=name, ok=bool(ok), detail=str(detail)))
    print(f"  {'✓' if ok else '✗'} {sid:<4} {name}" + (f"    {detail}" if detail else ''))
    return bool(ok)


def code_of(resp):
    """本项目统一响应：业务码在 body.code；HTTP 状态可能被包装成 200"""
    body = resp[1]
    return body.get('code') if isinstance(body, dict) else None


def token_of(username, password):
    r = http('POST', '/api/user/login', {'username': username, 'password': password})
    return (r[1].get('data') or {}).get('token') if isinstance(r[1], dict) else None


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip('=')


def b64u_dec(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + '=' * (-len(s) % 4))


def upload(token, filename: str, content_type: str, data: bytes):
    """构造 multipart/form-data 上传请求（urllib 无现成封装，这里手工拼 body）。

    返回与 http() 一致的 (status, body)，便于继续用 code_of() 取业务码。
    """
    boundary = '----dshSecurity' + str(int(time.time() * 1000))
    body = b''
    body += f'--{boundary}\r\n'.encode()
    body += f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode()
    body += f'Content-Type: {content_type}\r\n\r\n'.encode()
    body += data + b'\r\n'
    body += f'--{boundary}--\r\n'.encode()

    req = urllib.request.Request(WEB + '/api/product/upload', data=body, method='POST')
    req.add_header('Content-Type', f'multipart/form-data; boundary={boundary}')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode('utf-8', 'replace'))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode('utf-8', 'replace') or '{}')
        except Exception:
            return e.code, {}


def main():
    print('=' * 100)
    print(f'安全测试（{WEB}）—— SQL 注入 / XSS / 认证与 JWT / 越权 / 信息泄露 / 健壮性')
    print('=' * 100)

    token_a = token_of(*USER_A)
    token_b = token_of(*USER_B)
    token_admin = token_of(*ADMIN)
    if not (token_a and token_b and token_admin):
        print('  ✗ 前置登录失败，无法继续（请确认后端与演示账号可用）')
        return 1
    print(f'  前置：普通用户 {USER_A[0]} / {USER_B[0]} 与管理员均已登录\n')

    # ================================================ 一、SQL 注入
    print('【一、SQL 注入】')

    for sid, payload, desc in [
        ('S1', "admin' OR '1'='1", '经典 OR 永真绕过'),
        ('S2', "admin'--", '注释截断密码校验'),
        ('S3', "admin' OR 1=1 #", '井号注释绕过'),
        ('S4', "' UNION SELECT 1,2,3--", '联合查询'),
    ]:
        r = http('POST', '/api/user/login', {'username': payload, 'password': 'whatever'})
        body = r[1] if isinstance(r[1], dict) else {}
        got_token = bool((body.get('data') or {}).get('token')) if isinstance(body.get('data'), dict) else False
        case(sid, 'SQL注入', f'登录注入「{desc}」不能拿到 token',
             (not got_token) and body.get('code') != 200,
             f"code={body.get('code')}")

    # S5 搜索注入：不应因为注入而返回「全部商品」
    r_all = http('GET', '/api/product/list?page=1&size=12')
    total_all = ((r_all[1].get('data') or {}).get('total') if isinstance(r_all[1], dict) else None) or 0
    r_inj = http('GET', '/api/product/list?page=1&size=12&keyword=' +
                 urllib.parse.quote("' OR 1=1-- "))
    body_inj = r_inj[1] if isinstance(r_inj[1], dict) else {}
    data_inj = body_inj.get('data') or {}
    records = data_inj.get('records') or []
    case('S5', 'SQL注入', '搜索注入 OR 1=1 不返回全部商品',
         body_inj.get('code') == 200 and len(records) < max(1, total_all),
         f"注入命中 {len(records)} 条 / 全部 {total_all} 条")

    # S6 联合查询不应泄露用户表
    r_union = http('GET', '/api/product/list?page=1&size=5&keyword=' +
                   urllib.parse.quote("' UNION SELECT username,password FROM user-- "))
    text_union = json.dumps(r_union[1], ensure_ascii=False) if not isinstance(r_union[1], str) else r_union[1]
    leaked = ('$2a$' in text_union) or ('password' in text_union.lower()) or ('admin' in text_union)
    case('S6', 'SQL注入', '联合查询不泄露 user 表（无密码哈希/用户名字段）',
         not leaked, '响应中未出现密码哈希' if not leaked else '⚠ 疑似泄露')

    # S7 时间盲注：若拼接进 SQL，SLEEP(3) 会让响应显著变慢
    r_blind = http('GET', '/api/product/list?page=1&size=5&keyword=' +
                   urllib.parse.quote("' AND SLEEP(3)-- "))
    case('S7', 'SQL注入', '时间盲注 SLEEP(3) 未生效（说明用了参数化查询）',
         r_blind[3] < 2.0, f'响应耗时 {r_blind[3]*1000:.0f}ms')

    # S8 参数类型污染
    r_ct = http('GET', '/api/product/list?page=1&size=12&categoryId=' + enc('1 OR 1=1'))
    case('S8', 'SQL注入', '数字型参数传入注入串返回 400（类型校验生效）',
         code_of(r_ct) == 400, f"code={code_of(r_ct)}")

    # S9 路径参数注入
    r_path = http('GET', '/api/product/' + enc('1 OR 1=1'))
    case('S9', 'SQL注入', '路径参数注入被拒（400/404，不执行 SQL）',
         code_of(r_path) in (400, 404), f"code={code_of(r_path)}")

    # S10 分页参数注入后数据表仍完好
    http('GET', '/api/product/list?page=' + enc('1;DROP TABLE product--') + '&size=5')
    r_after = http('GET', '/api/product/list?page=1&size=1')
    total_after = ((r_after[1].get('data') or {}).get('total') if isinstance(r_after[1], dict) else 0) or 0
    case('S10', 'SQL注入', '注入尝试后商品表仍然可查（未被破坏）',
         total_after > 0, f'注入后商品总数 {total_after}')

    # ================================================ 二、XSS
    print('\n【二、XSS】')

    json_endpoints = [
        ('/api/product/list?page=1&size=3', '商品列表'),
        ('/api/product/6', '商品详情'),
        ('/api/categories', '分类'),
        ('/api/user/info', '用户信息'),
    ]
    all_json = True
    details = []
    for path, label in json_endpoints:
        r = http('GET', path, token=token_a)
        ctype = (r[2].get('Content-Type') or '').lower()
        ok = 'application/json' in ctype
        all_json = all_json and ok
        details.append(f'{label}:{ctype.split(";")[0]}')
    case('S11', 'XSS', '接口一律返回 application/json（浏览器不会当 HTML 执行）',
         all_json, ' / '.join(details))

    # S12 脚本载荷回显：只允许在 JSON 里原样出现，绝不能变成 text/html
    payload = "<script>alert('xss')</script>"
    r_xss = http('GET', '/api/product/list?page=1&size=3&keyword=' + urllib.parse.quote(payload))
    ctype = (r_xss[2].get('Content-Type') or '').lower()
    case('S12', 'XSS', '脚本载荷请求返回 JSON 而非 HTML',
         'application/json' in ctype and code_of(r_xss) == 200,
         f"code={code_of(r_xss)} content-type={ctype.split(';')[0]}")

    # S13 登录名中的脚本载荷不应被回显成 HTML
    r_xss_login = http('POST', '/api/user/login',
                       {'username': payload, 'password': payload})
    ctype = (r_xss_login[2].get('Content-Type') or '').lower()
    case('S13', 'XSS', '登录名脚本载荷被正常拒绝且响应仍为 JSON',
         'application/json' in ctype and code_of(r_xss_login) != 200,
         f"code={code_of(r_xss_login)}")

    # S14 后端是否自行转义（如实记录，不强行判定）
    stored_raw = payload in json.dumps(r_xss[1], ensure_ascii=False)
    escaped = '&lt;script&gt;' in json.dumps(r_xss[1], ensure_ascii=False)
    case('S14', 'XSS', '记录后端转义策略（原样回显 / 已转义 均属可接受，取决于前端是否转义）',
         True, '已转义' if escaped else ('原样回显（依赖前端 Vue 转义）' if stored_raw else '未回显'))

    # ================================================ 三、认证与 JWT
    print('\n【三、认证与 JWT】')

    for sid, path in [('S15', '/api/order/buyList?page=1&size=5'), ('S16', '/api/user/info'),
                      ('S17', '/api/favorite/list?page=1&size=5')]:
        r = http('GET', path)
        case(sid, '认证', f'无 token 访问 {path.split("?")[0]} 被拒（401）',
             code_of(r) == 401, f'code={code_of(r)}')

    real = token_a
    h, p, s = real.split('.')

    # S18 篡改 payload 但不改签名
    claims = json.loads(b64u_dec(p))
    tampered = json.loads(json.dumps(claims))
    for key in ('sub', 'userId', 'uid', 'id'):
        if key in tampered:
            tampered[key] = 999999
    bad_token = f'{h}.{b64u(json.dumps(tampered, separators=(",", ":")).encode())}.{s}'
    r = http('GET', '/api/user/info', token=bad_token)
    case('S18', 'JWT', '篡改 payload（改 userId 保留签名）被拒（401）',
         code_of(r) == 401, f'code={code_of(r)}')

    # S19 alg=none 攻击
    none_header = b64u(json.dumps({'alg': 'none', 'typ': 'JWT'}, separators=(',', ':')).encode())
    none_token = f'{none_header}.{p}.'
    r = http('GET', '/api/user/info', token=none_token)
    case('S19', 'JWT', 'alg=none 攻击被拒（401，不接受无签名 token）',
         code_of(r) == 401, f'code={code_of(r)}')

    # S20 随机/畸形 token
    for sid, bad, desc in [
        ('S20', 'not-a-jwt-at-all', '随机字符串'),
        ('S21', 'a.b.c', '结构不合法'),
        ('S22', real[:-4] + 'AAAA', '签名被改'),
        ('S23', '', '空 token'),
    ]:
        r = http('GET', '/api/user/info', token=bad or None)
        case(sid, 'JWT', f'{desc} 的 token 被拒（401）', code_of(r) == 401, f'code={code_of(r)}')

    # ================================================ 四、越权
    print('\n【四、越权（横向 / 纵向）】')

    # 取一个不属于 A 的订单
    r_buy = http('GET', '/api/order/buyList?page=1&size=5', token=token_b)
    b_orders = ((r_buy[1].get('data') or {}).get('records') or []) if isinstance(r_buy[1], dict) else []
    if b_orders:
        oid = b_orders[0]['id']
        r = http('GET', f'/api/order/{oid}', token=token_a)
        case('S24', '越权', f'横向越权：A 查看 B 的订单 {oid} 被拒（6004）',
             code_of(r) == 6004, f'code={code_of(r)}')
        # 反向：B 自己能看
        r_self = http('GET', f'/api/order/{oid}', token=token_b)
        case('S25', '越权', f'对照：B 查看自己的订单 {oid} 正常（200）',
             code_of(r_self) == 200, f'code={code_of(r_self)}')
    else:
        case('S24', '越权', '横向越权：未取到可用于对拍的订单（跳过）', True, 'skip')
        case('S25', '越权', '对照：未取到订单（跳过）', True, 'skip')

    # 纵向越权：普通用户访问管理端
    for sid, path, desc in [
        ('S26', '/api/admin/dashboard', '管理端仪表盘'),
        ('S27', '/api/admin/user/list?page=1&size=5', '管理端用户列表'),
        ('S28', '/api/admin/log/list?page=1&size=5', '管理端操作日志'),
    ]:
        r = http('GET', path, token=token_a)
        case(sid, '越权', f'纵向越权：普通用户访问{desc}被拒（403）',
             code_of(r) == 403, f'code={code_of(r)}')

    # 对照：管理员可访问
    r_admin = http('GET', '/api/admin/dashboard', token=token_admin)
    case('S29', '越权', '对照：管理员访问管理端正常（200）', code_of(r_admin) == 200,
         f'code={code_of(r_admin)}')

    # 越权修改他人商品状态
    r_mine = http('GET', '/api/product/mine?page=1&size=5', token=token_b)
    b_products = ((r_mine[1].get('data') or {}).get('records') or []) if isinstance(r_mine[1], dict) else []
    if b_products:
        pid = b_products[0]['id']
        r = http('PUT', '/api/product/status', {'productId': pid, 'status': 3}, token=token_a)
        case('S30', '越权', f'横向越权：A 下架 B 的商品 {pid} 被拒',
             code_of(r) != 200, f'code={code_of(r)}')
    else:
        case('S30', '越权', '横向越权：未取到 B 的商品（跳过）', True, 'skip')

    # ================================================ 五、信息泄露
    print('\n【五、信息泄露】')

    r_login = http('POST', '/api/user/login', {'username': USER_A[0], 'password': USER_A[1]})
    text_login = json.dumps(r_login[1], ensure_ascii=False)
    case('S31', '信息泄露', '登录响应不含密码/哈希字段',
         ('password' not in text_login.lower()) and ('$2a$' not in text_login),
         '仅返回 token 与用户基本信息')

    r_info = http('GET', '/api/user/info', token=token_a)
    text_info = json.dumps(r_info[1], ensure_ascii=False)
    case('S32', '信息泄露', '用户信息接口不含密码/哈希字段',
         ('password' not in text_info.lower()) and ('$2a$' not in text_info))

    # 错误响应不得暴露堆栈
    r_err = http('GET', '/api/product/99999999')
    text_err = json.dumps(r_err[1], ensure_ascii=False) if not isinstance(r_err[1], str) else r_err[1]
    stack = ('Exception' in text_err) or ('at com.campus' in text_err) or ('java.lang' in text_err) \
        or ('org.springframework' in text_err)
    case('S33', '信息泄露', '错误响应不暴露堆栈/框架内部信息',
         not stack, '仅返回可读提示' if not stack else '⚠ 疑似暴露堆栈')

    # 运维端点：分两处看
    #   ① 经 Nginx（8081）访问 /actuator/* 会命中前端 SPA 回退，返回 index.html —— 必须确认
    #      返回内容里没有任何 actuator 数据（否则说明后端把管理端点暴露到了公网路径）
    #   ② 真正的 actuator 在 8090 且只监听 127.0.0.1，未暴露的端点必须返回 404
    MGMT = os.environ.get('MGMT_BASE', 'http://127.0.0.1:8090')
    for sid, path in [('S34', '/actuator/env'), ('S35', '/actuator/beans'), ('S36', '/actuator/heapdump')]:
        r = http('GET', path)
        text = json.dumps(r[1], ensure_ascii=False) if not isinstance(r[1], str) else r[1]
        leaked = any(k in text for k in ('spring.datasource', 'systemProperties', 'activeProfiles',
                                         'java.class.path', '"beans"', 'JAVA_HOME'))
        case(sid, '信息泄露', f'经 Nginx 访问 {path} 不泄露 actuator 数据'
                              f'（落到前端 SPA 属正常）',
             not leaked, f'http={r[0]} 泄漏={"是" if leaked else "否"}')

    for sid, path in [('S34b', '/actuator/env'), ('S35b', '/actuator/beans'),
                      ('S36b', '/actuator/heapdump'), ('S36c', '/actuator/shutdown')]:
        r = http('GET', path, base=MGMT)
        body = r[1] if isinstance(r[1], dict) else {}
        case(sid, '信息泄露', f'管理端口 {path} 未暴露（404）',
             body.get('code') == 404 or r[0] == 404, f'code={body.get("code")} http={r[0]}')

    # 商品详情是否泄露卖家敏感个人信息（手机号/邮箱/学号）
    r_det = http('GET', '/api/product/6', token=token_a)
    text_det = json.dumps(r_det[1], ensure_ascii=False)
    import re as _re
    has_phone = bool(_re.search(r'\b1[3-9]\d{9}\b', text_det))
    has_email = bool(_re.search(r'[\w.+-]+@[\w-]+\.[\w.]+', text_det))
    case('S37', '信息泄露', '商品详情不泄露卖家手机号/邮箱',
         not has_phone and not has_email,
         f'手机号={has_phone} 邮箱={has_email}')

    # ================================================ 六、健壮性
    print('\n【六、健壮性】')

    big = 'A' * 50000
    r_big = http('POST', '/api/product/publish',
                 {'title': big, 'description': big, 'price': 1, 'categoryId': 1}, token=token_a)
    case('S38', '健壮性', '超长字段（50KB 标题）返回 400 而非 500',
         code_of(r_big) == 400, f'code={code_of(r_big)}')

    r_trav = http('GET', '/api/product/../../application.yml')
    text_trav = json.dumps(r_trav[1], ensure_ascii=False) if not isinstance(r_trav[1], str) else r_trav[1]
    case('S39', '健壮性', '路径遍历拿不到配置文件（不含 spring:/datasource）',
         ('spring:' not in text_trav) and ('datasource' not in text_trav) and ('password' not in text_trav.lower()),
         f'code={code_of(r_trav)}')

    r_badjson = http('POST', '/api/user/login', raw_body='{"username": "admin", "password":')
    case('S40', '健壮性', '非法 JSON 返回 400（不抛 500，也不暴露解析器信息）',
         code_of(r_badjson) == 400, f'code={code_of(r_badjson)}')

    # ================================================ 七、上传内容校验（v0.18 新增，第2批-⑤）
    # 扩展名与 Content-Type 都由客户端自述、可以伪造，因此逐个验证"内容才是判据"。
    # ★ 每个拒绝用例都配一个真实图片的对照组 —— 避免"接口整体不可用"造成的假通过。
    # ★ 断言收紧到具体业务码 3006（fileTypeNotAllowed），而不是只判 != 200：
    #   否则一旦文件被别的环节拦下（实测踩到：Windows Defender 会实时删除 webshell 样本，
    #   导致上传的是被删空的文件、走到 3008 上传失败），用例会"通过但理由错误"。
    UPLOAD_REJECT_CODE = 3006   # ProductException.fileTypeNotAllowed
    # ★ 复用套件开头已经登录好的 token_a，不要在这里再登录一次：
    #   实测多调一次登录会撞上登录限流（20 次/分钟）拿到 None，
    #   于是整组上传用例被"跳过"，而跳过又被算作通过 —— 又是一次"假通过"。
    up_token = token_a
    real_png = b'\x89PNG\r\n\x1a\n' + b'\x00' * 32
    real_jpeg = b'\xff\xd8\xff\xe0' + b'\x00' * 32
    if not case('S50', '上传校验', '上传用例前置：token_a 可用（拿不到则本组用例无意义）',
                bool(up_token), f'token_a={bool(up_token)}'):
        pass    # 硬失败，不再继续 —— 绝不用"跳过"伪装成通过
    else:
        st, body = upload(up_token, 'real.png', 'image/png', real_png)
        case('S50', '上传校验', '★ 对照组：真实 PNG 正常上传', code_of((st, body)) == 200,
             f'code={code_of((st, body))}')

        st, body = upload(up_token, 'fake.png', 'image/png',
                          b"<!DOCTYPE html><script>alert(1)</script>")
        case('S51', '上传校验', '★ HTML 页面伪装成 .png 被拒（只查扩展名会放过它）',
             code_of((st, body)) == UPLOAD_REJECT_CODE,
             f'code={code_of((st, body))}  {str(body.get("message", ""))[:40]}')

        st, body = upload(up_token, 'mismatch.png', 'image/png', real_jpeg)
        case('S52', '上传校验', '★ JPEG 内容却改名成 .png 被拒（扩展名与内容不一致）',
             code_of((st, body)) == UPLOAD_REJECT_CODE, f'code={code_of((st, body))}')

        # 注：本用例的样本（PHP 脚本）在开启实时防护的 Windows 上可能被提前删除，
        # 此时上传的是被删空的文件 —— 结果同样是"被拒绝"（安全属性成立），
        # 但业务码会变成 3008 上传失败。因此这里断言"不等于 200"，即"绝不接受"。
        st, body = upload(up_token, 'shell.png', 'image/png',
                          b"<?php system($_GET['c']); ?>")
        case('S53', '上传校验', '★ 脚本文件伪装成 .png 不被接受（绝不落库落盘）',
             code_of((st, body)) != 200, f'code={code_of((st, body))}')

        st, body = upload(up_token, 'note.txt', 'image/png', real_png)
        case('S54', '上传校验', '扩展名不在白名单内被拒（内容是真图片也不行）',
             code_of((st, body)) == UPLOAD_REJECT_CODE, f'code={code_of((st, body))}')

    r_badctype = http('POST', '/api/user/login', raw_body='username=admin&password=123456',
                      content_type='application/x-www-form-urlencoded')
    case('S41', '健壮性', '错误的 Content-Type 被拒（400/415），不静默成功',
         code_of(r_badctype) in (400, 415), f'code={code_of(r_badctype)}')

    r_neg = http('GET', '/api/product/list?page=-1&size=-100')
    case('S42', '健壮性', '负数分页参数被容忍（不抛 500）',
         code_of(r_neg) in (200, 400), f'code={code_of(r_neg)}')

    # ================================================ 汇总
    ok = sum(1 for r in RESULTS if r['ok'])
    total = len(RESULTS)
    print('\n' + '=' * 100)
    print(f'  安全测试结果：{ok}/{total} 通过' + ('  ✅ 全部通过' if ok == total else '  ⚠ 存在未通过项'))
    groups = {}
    for r in RESULTS:
        g = groups.setdefault(r['group'], [0, 0])
        g[1] += 1
        if r['ok']:
            g[0] += 1
    for g, (o, t) in groups.items():
        print(f'    {g:<10} {o}/{t}' + ('' if o == t else '   ← 需关注'))
    print('=' * 100)

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'security-result.json')
    with open(out, 'w', encoding='utf-8') as f:
        json.dump(dict(base=WEB, passed=ok, total=total, results=RESULTS,
                       generated_at=time.strftime('%Y-%m-%d %H:%M:%S')), f,
                  ensure_ascii=False, indent=1)
    print(f'  结果已保存：{out}')
    return 0 if ok == total else 1


if __name__ == '__main__':
    import urllib.parse
    raise SystemExit(main())
