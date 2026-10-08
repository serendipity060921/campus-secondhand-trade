@echo off
chcp 65001 >nul
title 打开校园二手交易平台数据库（HeidiSQL）
echo ============================================================
echo   校园二手交易平台 - 数据库管理工具 HeidiSQL
echo   连接：127.0.0.1:3306   用户：root   数据库：campus_trade
echo ============================================================
echo.
echo 正在启动 HeidiSQL...
start " D:\major\tool\HeidiSQL\heidisql.exe -h=127.0.0.1 -P=3306 -u=root -p=123456 -d=campus_trade
echo.
echo 已启动。左侧列表中展开 campus_trade 即可看到全部数据表。
echo   常用表：user / product / category / orders / favorite / message / user_behavior
echo   （各表逐字段说明见 docs/数据库说明.md）
echo.
echo （本窗口 3 秒后自动关闭）
timeout /t 3 >nul
