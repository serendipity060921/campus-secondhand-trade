# -*- coding: utf-8 -*-
"""给 Controller 批量补 Swagger 中文注解（第2批-⑨）

思路：**文案手工撰写、插入机械化、验证交给编译器与测试**。
  · 文案：下面 TAG_INFO / OP_INFO 两张表是手写的（不是机器生成的中文），保证文档可读；
  · 插入：脚本只做"在正确位置插一行"，用正则定位方法块，不改动任何既有代码；
  · 验证：插完立即 `mvnw test`（325 条单测）+ 重新拉 /v3/api-docs 核对标签生效。
    这一点是吸取 BUG-015 的教训 —— 上一次批量改脚本时被 here-string 换行坑到、
    一次改坏 23 个文件，所以凡是批量修改都必须有编译/测试这道网。

用法：
    python tools/add-swagger-annotations.py --dry-run   # 只看计划，不改文件
    python tools/add-swagger-annotations.py --apply     # 实际写入
    python tools/add-swagger-annotations.py --report    # 只统计当前注解覆盖情况
"""

# ★ Windows 控制台默认 GBK，脚本会输出 ✓/✗，必须重配置 stdout
import sys as _sys
try:
    _sys.stdout.reconfigure(encoding='utf-8')
    _sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass

import argparse
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
CTRL = ROOT / 'backend' / 'src' / 'main' / 'java' / 'com' / 'campus' / 'trade' / 'controller'

# ---------------------------------------------------------------- 类级 @Tag（中文分组名）
TAG_INFO = {
    'UserController':            ('用户与认证', '注册、登录、退出登录；注册与登录都带限流'),
    'AuthController':            ('用户与认证（兼容别名）', '与 /api/user 等价的兼容路径，供脚手架前端使用'),
    'UserProfileController':     ('个人资料', '查询与修改自己的资料、上传头像'),
    'ProductModuleController':   ('商品（核心模块）', '发布、图片上传、列表、详情、我的商品、上下架'),
    'ProductController':         ('商品（脚手架示例）', 'v0.03 遗留的演示接口，保留以保证既有调用可用'),
    'ProductSearchController':   ('商品搜索', '按关键词搜索商品'),
    'RecommendController':       ('个性化推荐', '基于用户行为的推荐列表'),
    'CategoryController':        ('分类（公开）', '分类树查询，供前端筛选使用'),
    'CategoryModuleController':  ('分类（模块）', '分类模块接口'),
    'CategoryManageController':  ('分类管理（管理员）', '新增与修改分类，仅管理员可用'),
    'FavoriteController':        ('收藏', '收藏/取消收藏、我的收藏列表'),
    'OrderController':           ('订单交易', '下单、订单状态流转、我买到的/我卖出的、订单详情'),
    'MessageController':         ('私信与实时消息', '发消息、会话列表、聊天记录、标记已读、在线状态'),
    'ReportController':          ('举报', '提交举报与查看我的举报'),
    'AdminController':           ('管理后台', '看板、商品审核、强制下架、用户状态、举报处理；仅管理员可用'),
    'HealthController':          ('健康检查', '服务与依赖的存活探测'),
}

# ---------------------------------------------------------------- 方法级 @Operation（中文摘要）
# key = "Controller#methodName"
OP_INFO = {
    # 用户与认证
    'UserController#register':          '注册新用户',
    'UserController#login':             '用户登录（返回 JWT Token）',
    'UserController#logout':            '退出登录（Token 加入黑名单）',
    'UserController#info':              '查询指定用户的公开信息',
    'AuthController#login':             '用户登录（兼容别名，等价于 /api/user/login）',
    'AuthController#logout':            '退出登录（兼容别名，无状态直接成功）',
    # 个人资料
    'UserProfileController#profile':    '查询自己的完整资料（含手机号/邮箱）',
    'UserProfileController#update':     '修改个人资料（昵称/头像/校区/联系方式）',
    'UserProfileController#uploadAvatar': '上传头像（校验文件真实内容）',
    # 商品
    'ProductModuleController#publish':      '发布商品（先过内容机审）',
    'ProductModuleController#upload':       '上传商品图片（支持单张或多张，可带 productId 直接入库）',
    'ProductModuleController#list':         '商品分页列表（仅上架商品，支持分类/关键词/价格区间/排序）',
    'ProductModuleController#mine':          '我的商品（含待审核与已下架）',
    'ProductModuleController#detail':        '商品详情（每次访问浏览量 +1，带缓存与防击穿）',
    'ProductModuleController#changeStatus':  '商品上架 / 下架（仅限自己的商品）',
    'ProductController#page':                 '商品列表（脚手架示例接口）',
    'ProductController#detail':               '商品详情（脚手架示例接口）',
    'ProductSearchController#search':        '关键词搜索商品',
    'RecommendController#recommend':         '个性化推荐（基于用户行为，带缓存）',
    'RecommendController#hot':               '热门商品榜',
    'RecommendController#similar':           '相似商品推荐',
    # 分类
    'CategoryController#list':               '分类树（两级：一级 + 二级）',
    'CategoryModuleController#list':         '分类列表',
    'CategoryManageController#add':          '新增分类（管理员）',
    'CategoryManageController#update':       '修改分类（管理员）',
    # 收藏
    'FavoriteController#operate':            '收藏 / 取消收藏（幂等：重复调用不报错）',
    'FavoriteController#list':               '我的收藏列表（分页）',
    'FavoriteController#hasFavorite':        '查询是否已收藏某商品',
    # 订单
    'OrderController#create':                '创建订单（下单即锁定商品，防一物多卖）',
    'OrderController#changeStatus':          '修改订单状态：3 已完成 / 4 已取消',
    'OrderController#buyList':               '我买到的订单（分页，可按状态筛选）',
    'OrderController#sellList':              '我卖出的订单（分页，可按状态筛选）',
    'OrderController#detail':                '订单详情（仅买卖双方可查看）',
    # 私信
    'MessageController#unreadTotal':         '未读私信总数（顶栏角标）',
    'MessageController#online':              '查询在线状态（不传 peerId 则查自己）',
    'MessageController#send':                '发送私信（落库 + 实时推送）',
    'MessageController#conversationList':    '会话列表（聊天对象 + 最后一条消息 + 未读数）',
    'MessageController#history':             '聊天记录（分页，最新在前）',
    'MessageController#read':                '标记消息已读（按聊天对象或按消息 ID）',
    'MessageController#peer':                '聊天对象公开信息（昵称/头像/校区/信用分）',
    # 举报
    'ReportController#submit':               '提交举报（商品或用户）',
    'ReportController#mine':                 '我的举报记录',
    # 管理后台
    'AdminController#dashboard':             '管理看板（用户/商品/订单/举报统计与趋势）',
    'AdminController#productList':           '商品列表（管理端，可按状态筛选待审核）',
    'AdminController#audit':                 '商品审核：通过 / 驳回（写审计日志）',
    'AdminController#offline':               '强制下架商品（写审计日志）',
    'AdminController#userList':              '用户列表（管理端）',
    'AdminController#changeUserStatus':      '启用 / 禁用用户（写审计日志）',
    'AdminController#reportList':            '举报列表（管理端）',
    'AdminController#handleReport':          '处理举报（写审计日志）',
    'AdminController#logList':               '管理员操作日志（审计追溯）',
    # 健康检查
    'HealthController#health':               '服务健康检查（版本、环境、运行时长）',
    'HealthController#dbHealth':             '数据库健康检查',
}

MAPPING_RE = re.compile(r'@(Get|Post|Put|Delete)Mapping')
METHOD_RE = re.compile(r'^\s*public\s+[\w<>,\[\]\.\s]+?\s+(\w+)\s*\(')
IMPORT_ANCHOR = re.compile(r'^import\s+io\.swagger', re.M)


def ensure_imports(text):
    """确保导入了 @Tag 与 @Operation（按需，避免未使用导入）"""
    want = []
    if '@Tag(' in text and 'io.swagger.v3.oas.annotations.tags.Tag' not in text:
        want.append('import io.swagger.v3.oas.annotations.tags.Tag;')
    if '@Operation(' in text and 'io.swagger.v3.oas.annotations.Operation' not in text:
        want.append('import io.swagger.v3.oas.annotations.Operation;')
    if not want:
        return text, 0
    lines = text.splitlines(keepends=True)
    # 插到最后一个 import 之后，保持导入区整洁
    last = max((i for i, l in enumerate(lines) if l.startswith('import ')), default=None)
    if last is None:
        return text, 0
    lines[last + 1:last + 1] = [w + '\n' for w in want]
    return ''.join(lines), len(want)


def plan_file(path):
    """返回 (新文本, 插入的注解列表)"""
    text = path.read_text(encoding='utf-8')
    lines = text.splitlines()
    cls = path.stem
    inserts = []          # (行号, 要插入的文本)

    # ① 类级 @Tag：插在 public class 之前
    if '@Tag(' not in text and cls in TAG_INFO:
        name, desc = TAG_INFO[cls]
        for i, l in enumerate(lines):
            if re.match(r'^public\s+class\s+' + re.escape(cls) + r'\b', l):
                inserts.append((i, f'@Tag(name = "{name}", description = "{desc}")\n'))
                break

    # ② 方法级 @Operation：用正向扫描找每个映射注解自己的方法名
    pending = []
    depth = 0
    skip_sig = 0
    for i, line in enumerate(lines):
        s = line.strip()
        if skip_sig > 0:
            skip_sig = max(0, skip_sig + s.count('(') - s.count(')'))
            continue
        # ★ 跨行注解的续行（例如 @RateLimit(...,\n message = "...")）必须并入同一个块，
        #   否则续行会落到最后那个"清空 pending"分支，把整个方法块抹掉 ——
        #   干跑时表现就是"凡是带跨行注解的接口全被漏掉"（publish/changeStatus/create/
        #   send/read/operate/submit 一个不落）。这与 BUG-013 是同一类错误，
        #   说明"按注解块解析"这件事必须同时处理三件事：跨行注解、跨行签名、括号配平。
        if depth > 0:
            pending.append((i, s))
            depth = max(0, depth + s.count('(') - s.count(')'))
            continue
        if s.startswith('@'):
            pending.append((i, s))
            depth = max(0, depth + s.count('(') - s.count(')'))
            continue
        sig = METHOD_RE.match(line)
        if sig:
            skip_sig = max(0, line.count('(') - line.count(')'))
            method = sig.group(1)
            key = f'{cls}#{method}'
            has_op = any('@Operation(' in t for _, t in pending)
            mp = next(((li, t) for li, t in pending if MAPPING_RE.search(t)), None)
            if mp and not has_op and key in OP_INFO:
                inserts.append((mp[0], f'@Operation(summary = "{OP_INFO[key]}")\n'))
            pending = []
            continue
        if s and not (s.startswith('*') or s.startswith('/*') or s.startswith('//')):
            pending = []

    if not inserts:
        return text, []
    out = list(lines)
    for idx, add in sorted(inserts, key=lambda x: -x[0]):     # 从后往前插，行号不漂移
        out.insert(idx, add.rstrip('\n'))
    new = '\n'.join(out) + ('\n' if text.endswith('\n') else '')
    new, _ = ensure_imports(new)
    return new, [a.strip() for _, a in sorted(inserts)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--apply', action='store_true')
    ap.add_argument('--report', action='store_true')
    args = ap.parse_args()

    files = sorted(CTRL.glob('*.java'))
    if args.report:
        total_ep = total_tag = total_op = 0
        for f in files:
            t = f.read_text(encoding='utf-8')
            total_tag += 1 if '@Tag(' in t else 0
            total_op += len(re.findall(r'@Operation\(', t))
            total_ep += len(MAPPING_RE.findall(t))
        print(f'  控制器 {len(files)} 个，接口 {total_ep} 个；'
              f'已有 @Tag {total_tag} 个、@Operation {total_op} 个')
        return 0

    grand = 0
    for f in files:
        new, added = plan_file(f)
        if not added:
            continue
        grand += len(added)
        print(f'  {f.stem:<30} 计划插入 {len(added)} 条')
        for a in added:
            print(f'      + {a}')
        if args.apply:
            f.write_text(new, encoding='utf-8')
    print(f'\n  合计计划插入 {grand} 条注解' + ('（已写入）' if args.apply else '（--dry-run，未写入）'))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
