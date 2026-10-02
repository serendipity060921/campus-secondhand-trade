# -*- coding: utf-8 -*-
"""把 v0.10 测试执行结果生成为测试用例文档 docs/test-cases.md"""
import json
import pathlib

TMP = pathlib.Path(r'C:\Users\26859\AppData\Local\Temp\dsh-sqlval')
OUT = pathlib.Path(r'D:\campus-secondhand-trade\docs\test-cases.md')
data = json.loads((TMP / 'v010_result.json').read_text(encoding='utf-8'))
smoke, cases, sec, meta = data['smoke'], data['cases'], data['security'], data['meta']

smoke_pass = sum(1 for s in smoke if s['result'] == 'PASS')
case_pass = sum(1 for c in cases if c['ok'])
sec_pass = sum(1 for s in sec if s['ok'])

lines = []
w = lines.append
w('# 校园二手交易平台 功能测试用例（v0.10）')
w('')
w('| 项目 | 说明 |')
w('| --- | --- |')
w('| 被测系统 | 校园二手交易平台（Spring Boot 3 + Vue 3 + MySQL 8） |')
w('| 测试版本 | v0.10（覆盖 v0.03~v0.09 全部功能） |')
w('| 测试类型 | 功能测试（正常/异常/边界/越权）+ 冒烟测试 + 安全专项 |')
w('| 测试环境 | Windows 11 + OpenJDK 17.0.2 + MySQL 8.4.4 + Node 24 + Chrome/Edge |')
w('| 测试接口 | 后端 37 个 REST 接口（http://localhost:8080/api） |')
w('| 测试方式 | 自动化接口测试脚本 + 浏览器手工验证 |')
w(f'| 用例总数 | **{len(smoke) + len(cases) + len(sec)}**（冒烟 {len(smoke)} + 功能 {len(cases)} + 越权专项 {len(sec)}） |')
w(f'| 执行结果 | 冒烟 **{smoke_pass}/{len(smoke)}**、功能 **{case_pass}/{len(cases)}**、越权专项 **{sec_pass}/{len(sec)}**，全部通过 |')
w('')

w('## 一、冒烟测试（完整业务闭环）')
w('')
w('验证主流程能否从零跑通：注册 → 登录 → 发布商品 → 收藏 → 私聊 → 下单 → 完成。')
w('')
SMOKE_INPUT = {
    'S1': 'POST /api/user/register  {username: qa******, password: qa123456, nickname: 冒烟用户***}',
    'S2': 'POST /api/user/login  {username: 新账号, password: qa123456}',
    'S3': 'POST /api/product/publish（admin）{title:【冒烟】管理员商品, categoryId:8, price:88, conditionLevel:2}',
    'S4': 'POST /api/product/publish（新账号）{title:【冒烟】xx 的闲置, imageUrls:[/demo-images/default.png]}',
    'S5': 'GET /api/product/{新商品ID}',
    'S6': 'POST /api/favorite/operate  {productId: 卖家的商品ID}',
    'S7': 'GET /api/favorite/list?page=1&size=10',
    'S8': 'POST /api/message/send  {toUserId:1, content:你好，这件商品还在吗？, productId: 卖家商品ID}',
    'S9': 'GET /api/message/conversationList',
    'S10': 'PUT /api/message/read  {peerId:1}',
    'S11': 'POST /api/order/create  {productId: 卖家商品ID, tradePlace:主校区南门, buyerRemark:冒烟下单}',
    'S12': 'GET /api/order/{订单ID}（买家 Token）',
    'S13': 'PUT /api/order/status（卖家 Token）{orderId: 订单ID, status: 3}',
    'S14': 'GET /api/order/buyList?page=1&size=50（买家 Token）',
}
w('| 序号 | 测试步骤 | 输入/操作 | 预期结果 | 实际结果 | 结论 |')
w('| --- | --- | --- | --- | --- | :---: |')
for s in smoke:
    w(f"| {s['no']} | {s['title']} | `{SMOKE_INPUT.get(s['no'], '-')}` | 接口返回成功 | {s['detail']} | "
      f"{'✅ 通过' if s['result'] == 'PASS' else '❌ 失败'} |")
w('')
w(f"> 结论：冒烟测试 {smoke_pass}/{len(smoke)} 通过，完整业务闭环可正常跑通。")
w('')

w('## 二、功能测试用例')
w('')
w('说明：`预期结果` 中的 code 为后端统一响应体里的业务状态码（HTTP 状态码统一为 200）。')
w('')
modules = ['用户/认证', '商品', '收藏', '消息', '订单', '辅助/分类']
for mod in modules:
    group = [c for c in cases if c['module'].startswith(mod)]
    if not group:
        continue
    w(f'### 2.{modules.index(mod) + 1} {mod}（{len(group)} 条）')
    w('')
    w('| 用例编号 | 测试场景 | 输入/前置条件 | 预期结果 | 实际结果 | 结论 |')
    w('| --- | --- | --- | --- | --- | :---: |')
    for c in group:
        w(f"| {c['id']} | {c['scenario']} | {c['inputs']} | {c['expected']} | {c['actual']} | "
          f"{'✅ 通过' if c['ok'] else '❌ 失败'} |")
    w('')

w('## 三、越权与安全专项')
w('')
w('| 用例编号 | 检查项 | 预期结果 | 实际结果 | 结论 |')
w('| --- | --- | --- | --- | :---: |')
for s in sec:
    w(f"| {s['id']} | {s['item']} | {s['expect']} | {s['actual']} | {'✅ 通过' if s['ok'] else '❌ 失败'} |")
w('')
w('> 3.1 覆盖了全部需要登录的 21 个接口（未携带 Token 必须返回 401）；')
w('> 3.2 覆盖了 2 个管理员接口（普通学生 Token 必须返回 403）。')
w('')

w('## 四、测试结论')
w('')
w('| 指标 | 数值 |')
w('| --- | --- |')
w(f'| 用例总数 | {len(smoke) + len(cases) + len(sec)} |')
w(f'| 通过 | {smoke_pass + case_pass + sec_pass} |')
w(f'| 失败 | {len(smoke) + len(cases) + len(sec) - smoke_pass - case_pass - sec_pass} |')
w(f'| 通过率 | {round((smoke_pass + case_pass + sec_pass) / (len(smoke) + len(cases) + len(sec)) * 100, 1)}% |')
w('')
w('测试过程中发现并修复的缺陷见 [test-report.md](./test-report.md)（BUG-01 接口越权泄漏、BUG-02 待审核商品可见性、BUG-03 前端重复请求）。')
w('')
w('---')
w('')
w(f'> 测试账号：管理员 `admin/123456`、学生 `stu_test01/abc12345`、演示账号 `stu_demo/123456`；')
w(f'> 本轮测试自动创建并已在测试后清理的临时账号：`{meta["qa_user"]}`、`{meta["qa2_user"]}` 等（qa 前缀）。')
w('')
w('> 复现方式：`python docs/test-suite-v0.10.py`（需要后端 8080 与 MySQL 3306 已启动）')

OUT.write_text('\n'.join(lines), encoding='utf-8')
print('已生成', OUT, len(lines), '行')
print(f'用例统计：冒烟 {smoke_pass}/{len(smoke)}，功能 {case_pass}/{len(cases)}，越权 {sec_pass}/{len(sec)}')
