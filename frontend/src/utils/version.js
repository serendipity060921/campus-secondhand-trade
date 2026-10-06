/**
 * 前端版本号 —— **唯一来源**（v0.16）
 *
 * 背景：登录页、注册页、顶栏、页脚等处原本各自硬编码 "v0.04"，发版时经常漏改
 * （答辩演示时登录页还显示 v0.04 就是这个原因）。
 * 现在统一从这里读取：以后发版只改这一个文件。
 *
 * 与后端的一致性：后端 `app.version` 在 application.yml / application-dev.yml /
 * application-prod.yml 中配置，/api/health 会返回该值；两处应保持同一个版本。
 */
export const APP_VERSION = 'v0.16'

/** 版本说明（用于页脚等需要描述本次里程碑的地方） */
export const APP_VERSION_LABEL = 'v0.16 全站 UI 改版'

export default { APP_VERSION, APP_VERSION_LABEL }
