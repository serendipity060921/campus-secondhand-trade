#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 schema / 补丁脚本应用到**指定库**，并剥掉文件里的 USE / CREATE DATABASE

背景（BUG-19）：`db/schema/01-schema.sql` 开头有 `CREATE DATABASE ... campus_trade` 与
`USE campus_trade;`，且含 `DROP TABLE IF EXISTS`。我本想把它应用到测试库，
结果执行到 `USE` 就切回演示库 → 演示库被删表重建、数据清空。

规则：**永远不要直接把 schema 文件喂给 mysql**，用这个工具。

用法：
    python tools/apply-schema.py db/schema/01-schema.sql campus_trade_test
"""
import pathlib
import re
import subprocess
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

MYSQL_CANDIDATES = [
    r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe',
    'mysql',
]


def mysql_bin():
    for c in MYSQL_CANDIDATES:
        if pathlib.Path(c).exists() or c == 'mysql':
            return c
    return 'mysql'


def apply(sql_path: str, db: str) -> bool:
    p = pathlib.Path(sql_path)
    if not p.exists():
        print(f'  ✗ 文件不存在: {sql_path}')
        return False
    sql = p.read_text(encoding='utf-8')
    stripped = re.sub(r'(?im)^\s*(USE|CREATE DATABASE|DROP DATABASE)[^;]*;\s*$', '', sql)
    removed = len(sql.splitlines()) - len(stripped.splitlines())
    proc = subprocess.run([mysql_bin(), '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                           '--default-character-set=utf8mb4', db],
                          input=stripped.encode('utf-8'), capture_output=True)
    err = [l for l in proc.stderr.decode('utf-8', 'replace').splitlines() if 'Using a password' not in l]
    print(f'  {sql_path} → {db}: rc={proc.returncode}  （已剥掉 {removed} 行切库语句）'
          + (f'  首个错误: {err[0][:110]}' if err else ''))
    return proc.returncode == 0


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print('用法: python tools/apply-schema.py <sql 文件> <目标库>')
        sys.exit(2)
    sys.exit(0 if apply(sys.argv[1], sys.argv[2]) else 1)
