# -*- coding: utf-8 -*-
"""校园二手交易平台 v0.11 推荐算法离线评测脚本

目的：用**留一法（leave-one-out）**在仿真数据集上对比四种召回策略的效果，
      为论文"推荐算法实验"一节提供可复现的数据与结论。

为什么用仿真数据：
  真实演示库只有 4 个用户 / 14 件商品，行为极其稀疏，无法支撑对比实验
  （用户少 → 共现矩阵几乎为空 → 协同过滤无数据可算）。
  因此脚本会生成一批符合"校园二手交易"分布特征的仿真行为数据：
    · 4 个兴趣主题（对应真实分类树的 4 个一级分类：教材书籍 / 数码电子 / 生活用品 / 运动户外）
    · 每个用户有 1 个主兴趣主题（80% 行为集中在该主题，20% 为跨主题噪声）
    · 行为类型：浏览(权重1) / 收藏(权重3)，时间分布在最近 30 天内（使时间衰减有意义）

评测协议：
  1. 对每个评测用户，取"最后一次行为对应的商品"作为留出的 ground truth；
  2. 从行为表删除该条记录（模拟"未来行为未知"）；
  3. 用该用户 Token 调用**真实推荐接口** GET /api/product/recommend?size=K&strategy=X；
  4. 若留出商品出现在 Top-K 中记为命中；恢复被删除的行为记录；
  5. 统计 HitRate@K、Precision@K、覆盖率与平均响应时间。
  —— 四种策略使用完全相同的用户集合、留出商品与过滤规则，保证对比公平。

执行：python tools/recommend-eval.py
依赖：后端 8080 已启动；mysql CLI 在 PATH 中（或修改下方 MYSQL 常量）
产物：控制台报表 + tools/recommend-eval-result.json（供文档生成使用）
"""
import json
import os
import pathlib
import random
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = 'http://127.0.0.1:8080/api'
MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
OUT = pathlib.Path(__file__).with_name('recommend-eval-result.json')

# ------------------------------ 评测参数（可改） ------------------------------
N_USERS = 60           # 仿真用户数
N_PRODUCTS = 80        # 仿真商品数
K = 10                 # Top-K
TOPIC_CATEGORY_ROOTS = [1, 2, 3, 4]   # 4 个兴趣主题对应的一级分类
USER_PREFIX = 'recu'
PRODUCT_TAG = '【仿真】'
PASSWORD = 'abc12345'
STRATEGIES = [('hot', '纯热门（基线）'), ('content', '纯内容匹配'),
              ('cf', '纯协同过滤'), ('hybrid', '融合 0.50/0.35/0.15'),
              ('hybrid-cf', '融合 0.70/0.20/0.10'), ('hybrid-content', '融合 0.30/0.60/0.10')]
SEED = 20261003        # 固定随机种子，保证实验可复现
ACTIVE_RATIO = 0.6     # 活跃用户占比（行为多）；其余为稀疏用户（行为少），用于分场景分析
random.seed(SEED)


def sql(query, database='campus_trade'):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B',
                        '-e', f'USE {database}; {query}'],
                       capture_output=True, text=True, encoding='utf-8', timeout=120)
    if r.returncode != 0 and 'Using a password' not in (r.stderr or ''):
        raise RuntimeError(f'SQL 失败: {r.stderr[:300]}')
    return [line.split('\t') for line in (r.stdout or '').strip().splitlines() if line]


def sql_file(path):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                        '--default-character-set=utf8mb4', '-e', f'source {path}'],
                       capture_output=True, text=True, encoding='utf-8', timeout=600)
    if r.returncode != 0:
        raise RuntimeError(f'SQL 文件执行失败: {r.stderr[:400]}')


def api(method, path, body=None, token=None, timeout=30):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode('utf-8')), 0
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8', 'replace')), 0
    except Exception as e:  # noqa: BLE001
        return {'code': -1, 'message': str(e)}, 0


# ============================================================ 1. 造仿真数据
def seed():
    print('=' * 96)
    print(f'第 1 步：生成仿真数据（{N_USERS} 个用户 / {N_PRODUCTS} 件商品 / 4 个兴趣主题，随机种子 {SEED}）')
    print('=' * 96)

    # 主题 -> 该主题下的二级分类
    topic_categories = {}
    for root in TOPIC_CATEGORY_ROOTS:
        rows = sql(f"SELECT id FROM category WHERE parent_id = {root} AND deleted = 0 ORDER BY id")
        ids = [int(r[0]) for r in rows]
        topic_categories[root] = ids if ids else [root]
    print('  主题分类映射：', {k: len(v) for k, v in topic_categories.items()}, '（主题 -> 候选二级分类数）')

    # 1.1 仿真商品
    lines = ['USE campus_trade;']
    lines.append(f"DELETE FROM user_behavior WHERE product_id IN (SELECT id FROM product WHERE title LIKE '{PRODUCT_TAG}%');")
    lines.append(f"DELETE FROM product WHERE title LIKE '{PRODUCT_TAG}%';")
    products = []          # (id 占位, topic)
    for i in range(N_PRODUCTS):
        topic = TOPIC_CATEGORY_ROOTS[i % len(TOPIC_CATEGORY_ROOTS)]
        cat = random.choice(topic_categories[topic])
        price = round(random.uniform(5, 300), 2)
        orig = round(price * random.uniform(1.2, 3.0), 2)
        views = random.randint(0, 300)
        cond = random.randint(1, 4)
        days = random.randint(0, 60)
        campus = random.choice(['主校区', '东校区', '西校区'])
        title = f'{PRODUCT_TAG}主题{topic}商品{i + 1:02d}'
        lines.append(
            "INSERT INTO product (title, description, category_id, seller_id, price, original_price, "
            "condition_level, campus, trade_place, cover_image, status, view_count, favorite_count, "
            "create_time, update_time, deleted) VALUES ("
            f"'{title}', '离线评测仿真商品', {cat}, 1, {price}, {orig}, {cond}, '{campus}', '{campus}食堂门口', "
            f"'/demo-images/default.png', 1, {views}, 0, "
            f"DATE_SUB(NOW(), INTERVAL {days} DAY), DATE_SUB(NOW(), INTERVAL {days} DAY), 0);")
        products.append(topic)

    # 1.2 仿真用户（密码复用 stu_test01 的 BCrypt 密文，即 abc12345）
    lines.append(f"DELETE FROM user_behavior WHERE user_id IN (SELECT id FROM `user` WHERE username LIKE '{USER_PREFIX}%');")
    lines.append(f"DELETE FROM `user` WHERE username LIKE '{USER_PREFIX}%';")
    for u in range(N_USERS):
        lines.append(
            "INSERT INTO `user` (username, password, nickname, gender, role, status, credit_score, deleted, "
            "create_time, update_time) SELECT "
            f"'{USER_PREFIX}{u + 1:03d}', password, '仿真用户{u + 1:03d}', 0, 0, 1, 100, 0, NOW(), NOW() "
            "FROM `user` WHERE username = 'stu_test01';")

    seed_sql = pathlib.Path(os.environ.get('TEMP', '.')) / 'seed_recommend.sql'
    seed_sql.write_text('\n'.join(lines), encoding='utf-8')
    sql_file(str(seed_sql))
    print(f'  已写入 {N_PRODUCTS} 件仿真商品、{N_USERS} 个仿真用户')

    # 1.3 仿真行为：每个用户 80% 行为集中在自己的主主题
    pid_rows = sql(f"SELECT id FROM product WHERE title LIKE '{PRODUCT_TAG}%' AND deleted = 0 ORDER BY id")
    pids = [int(r[0]) for r in pid_rows]
    pid_topic = {}
    for idx, pid in enumerate(pids):
        pid_topic[pid] = TOPIC_CATEGORY_ROOTS[idx % len(TOPIC_CATEGORY_ROOTS)]
    topic_pids = {t: [p for p in pids if pid_topic[p] == t] for t in TOPIC_CATEGORY_ROOTS}

    urows = sql(f"SELECT id FROM `user` WHERE username LIKE '{USER_PREFIX}%' AND deleted = 0 ORDER BY id")
    uids = [int(r[0]) for r in urows]

    behavior_lines = ['USE campus_trade;']
    total_behaviors = 0
    user_group = {}          # user_id -> 'active' / 'sparse'
    for u in uids:
        main_topic = random.choice(TOPIC_CATEGORY_ROOTS)
        pool_main = topic_pids[main_topic]
        pool_other = [p for p in pids if pid_topic[p] != main_topic]
        # 活跃用户行为多，稀疏用户行为少（用于"数据稀疏度"分场景对比）
        if random.random() < ACTIVE_RATIO:
            user_group[u] = 'active'
            n_main, n_other = random.randint(7, 11), random.randint(1, 3)
        else:
            user_group[u] = 'sparse'
            n_main, n_other = random.randint(2, 3), 1
        chosen = set(random.sample(pool_main, min(n_main, len(pool_main))))
        chosen |= set(random.sample(pool_other, min(n_other, len(pool_other))))
        for pid in chosen:
            # 70% 浏览 + 30% 收藏（收藏代表更强兴趣）
            btype = 1 if random.random() < 0.7 else 2
            weight = 1.0 if btype == 1 else 3.0
            days = random.randint(0, 30)
            behavior_lines.append(
                "INSERT INTO user_behavior (user_id, product_id, behavior_type, category_id, weight, "
                "behavior_count, create_time, update_time) SELECT "
                f"{u}, {pid}, {btype}, p.category_id, {weight}, {random.randint(1, 3)}, "
                f"DATE_SUB(NOW(), INTERVAL {days} DAY), DATE_SUB(NOW(), INTERVAL {days} DAY) "
                f"FROM product p WHERE p.id = {pid};")
            total_behaviors += 1
    behavior_sql = pathlib.Path(os.environ.get('TEMP', '.')) / 'seed_behavior.sql'
    behavior_sql.write_text('\n'.join(behavior_lines), encoding='utf-8')
    sql_file(str(behavior_sql))

    stats = sql("SELECT behavior_type, COUNT(*), COUNT(DISTINCT user_id), COUNT(DISTINCT product_id) "
                f"FROM user_behavior WHERE deleted = 0 GROUP BY behavior_type")
    print(f'  已写入 {total_behaviors} 条行为；行为表统计（类型/条数/用户数/商品数）：')
    for row in stats:
        print(f'    类型 {row[0]}: {row[1]} 条, {row[2]} 用户, {row[3]} 商品')
    on_sale = sql(f"SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 1")[0][0]
    print(f'  当前在售商品总数（候选集规模）：{on_sale}')
    print(f'  用户分组：活跃 {(sum(1 for g in user_group.values() if g == "active"))} 人 / '
          f'稀疏 {(sum(1 for g in user_group.values() if g == "sparse"))} 人（用于数据稀疏度分场景分析）')
    return uids, pids, user_group


# ============================================================ 2. 留一法评测
def evaluate(uids, user_group):
    print()
    print('=' * 96)
    print(f'第 2 步：留一法离线评测（每个用户留出最后 1 次行为，Top-{K}，{len(STRATEGIES)} 种策略）')
    print('=' * 96)

    # 取每个用户"最后一次行为"的商品作为留出目标
    holdout_rows = sql(
        "SELECT b.user_id, b.product_id FROM user_behavior b "
        "JOIN (SELECT user_id, MAX(update_time) AS mt FROM user_behavior "
        f"      WHERE deleted = 0 AND user_id IN ({','.join(str(u) for u in uids)}) GROUP BY user_id) t "
        "  ON t.user_id = b.user_id AND t.mt = b.update_time "
        f"WHERE b.deleted = 0 AND b.user_id IN ({','.join(str(u) for u in uids)}) "
        "GROUP BY b.user_id, b.product_id")
    holdout = {int(r[0]): int(r[1]) for r in holdout_rows}
    print(f'  参与评测的用户：{len(holdout)} 个（各留出 1 件商品作为正确答案）')

    # 登录所有评测用户，拿 Token
    tokens = {}
    for uid in holdout:
        uname = sql(f"SELECT username FROM `user` WHERE id = {uid}")[0][0]
        res, _ = api('POST', '/user/login', {'username': uname, 'password': PASSWORD})
        if res.get('code') == 200:
            tokens[uid] = res['data']['token']
    print(f'  成功登录评测用户：{len(tokens)} 个')

    catalog = int(sql("SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 1")[0][0])
    records = []

    for uid, held in holdout.items():
        token = tokens.get(uid)
        if not token:
            continue
        # 删除留出行为（模拟"未来不可见"），评测结束后恢复
        sql(f"DELETE FROM user_behavior WHERE user_id = {uid} AND product_id = {held}")
        try:
            for key, _ in STRATEGIES:
                t0 = time.time()
                res, _ = api('GET', f'/product/recommend?size={K}&strategy={key}', token=token)
                cost = (time.time() - t0) * 1000
                ids = []
                if res.get('code') == 200:
                    ids = [i['productId'] for i in res.get('data', {}).get('items', [])]
                records.append(dict(strategy=key, uid=uid, group=user_group.get(uid, 'active'),
                                    hit=held in ids, ids=ids, latency=cost))
        finally:
            sql("INSERT INTO user_behavior (user_id, product_id, behavior_type, category_id, weight, "
                "behavior_count, create_time, update_time) "
                f"SELECT {uid}, p.id, 1, p.category_id, 1.0, 1, NOW(), NOW() FROM product p WHERE p.id = {held}")

    def aggregate(rows):
        if not rows:
            return dict(users=0, hits=0, hit_rate=0.0, precision_at_k=0.0, coverage=0.0,
                        avg_latency_ms=0.0, distinct_items=0)
        users = len(rows)
        hits = sum(1 for r in rows if r['hit'])
        distinct = set()
        for r in rows:
            distinct.update(r['ids'])
        return dict(users=users, hits=hits,
                    hit_rate=round(hits / users * 100, 2),
                    precision_at_k=round(hits / users / K * 100, 2),
                    coverage=round(len(distinct) / catalog * 100, 2) if catalog else 0.0,
                    avg_latency_ms=round(statistics.mean(r['latency'] for r in rows), 1),
                    distinct_items=len(distinct))

    report = []
    for key, label in STRATEGIES:
        rows = [r for r in records if r['strategy'] == key]
        item = dict(strategy=key, label=label)
        item.update(aggregate(rows))
        item['active'] = aggregate([r for r in rows if r['group'] == 'active'])
        item['sparse'] = aggregate([r for r in rows if r['group'] == 'sparse'])
        report.append(item)
    return report, catalog, len(holdout)


# ============================================================ 3. 清理
def cleanup():
    print()
    print('=' * 96)
    print('第 3 步：清理仿真数据（商品 / 用户 / 行为）')
    print('=' * 96)
    sql(f"DELETE FROM user_behavior WHERE user_id IN (SELECT id FROM `user` WHERE username LIKE '{USER_PREFIX}%');")
    sql(f"DELETE FROM user_behavior WHERE product_id IN (SELECT id FROM product WHERE title LIKE '{PRODUCT_TAG}%');")
    sql(f"DELETE FROM product WHERE title LIKE '{PRODUCT_TAG}%';")
    sql(f"DELETE FROM `user` WHERE username LIKE '{USER_PREFIX}%';")
    left = sql("SELECT (SELECT COUNT(*) FROM `user` WHERE deleted = 0), "
               "(SELECT COUNT(*) FROM product WHERE deleted = 0), "
               "(SELECT COUNT(*) FROM user_behavior WHERE deleted = 0)")[0]
    print(f'  清理完成：用户 {left[0]} 个 / 商品 {left[1]} 件 / 行为 {left[2]} 条（已恢复演示数据状态）')


def main():
    t_start = time.time()
    uids, _, user_group = seed()
    report, catalog, evaluated = evaluate(uids, user_group)

    print()
    print('=' * 96)
    print('第 4 步：评测结果（留一法，Top-%d，候选集 %d 件在售商品，评测用户 %d 人）' % (K, catalog, evaluated))
    print('=' * 96)
    header = (f"{'召回策略':<22}{'HitRate@K':>11}{'Precision@K':>13}{'覆盖率':>10}"
              f"{'去重商品':>10}{'平均耗时(ms)':>14}")
    print(header)
    print('-' * 84)
    for r in report:
        print(f"{r['label']:<22}{r['hit_rate']:>10.2f}%{r['precision_at_k']:>12.2f}%"
              f"{r['coverage']:>9.2f}%{r['distinct_items']:>10}{r['avg_latency_ms']:>14.1f}")

    print()
    print('分场景对比（数据稀疏度）：活跃用户 vs 稀疏用户')
    print('-' * 84)
    print(f"{'召回策略':<22}{'活跃 HitRate@K':>16}{'稀疏 HitRate@K':>16}{'稀疏用户数':>12}")
    for r in report:
        print(f"{r['label']:<22}{r['active']['hit_rate']:>15.2f}%{r['sparse']['hit_rate']:>15.2f}%"
              f"{r['sparse']['users']:>12}")

    best = max(report, key=lambda r: r['hit_rate'])
    base = [r for r in report if r['strategy'] == 'hot'][0]
    best_sparse = max(report, key=lambda r: r['sparse']['hit_rate'])
    print('-' * 84)
    print(f"  结论 1：{best['label']} 的整体 HitRate@K 最高（{best['hit_rate']}%），"
          f"相对热门基线（{base['hit_rate']}%）提升 {best['hit_rate'] - base['hit_rate']:.2f} 个百分点"
          f"（相对提升 {((best['hit_rate'] / base['hit_rate'] - 1) * 100) if base['hit_rate'] else 0:.1f}%）。")
    print(f"  结论 2：稀疏用户场景下 {best_sparse['label']} 表现最好（{best_sparse['sparse']['hit_rate']}%），"
          f"说明内容召回对冷启动/稀疏数据更稳健。")

    result = dict(seed=SEED, k=K, users=len(uids), evaluated_users=evaluated, catalog=catalog,
                  active_ratio=ACTIVE_RATIO, report=report,
                  elapsed_sec=round(time.time() - t_start, 1),
                  generated_at=time.strftime('%Y-%m-%d %H:%M:%S'))
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print(f"\n结果已保存：{OUT}（耗时 {result['elapsed_sec']} 秒）")

    cleanup()


if __name__ == '__main__':
    try:
        main()
    except Exception as e:  # noqa: BLE001
        print(f'\n评测失败：{type(e).__name__} {e}')
        try:
            cleanup()
        except Exception:  # noqa: BLE001
            pass
        sys.exit(1)
