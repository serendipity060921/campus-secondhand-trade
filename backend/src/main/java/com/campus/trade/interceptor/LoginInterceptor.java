package com.campus.trade.interceptor;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.BusinessException;
import com.campus.trade.common.exception.UserException;
import com.campus.trade.common.result.UserResultCode;
import com.campus.trade.common.util.JwtUtil;
import com.campus.trade.config.JwtProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录校验拦截器（v0.04）。
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>判断目标接口（方法或所在 Controller 类）是否标注了 {@link LoginRequired}；未标注 → 公开接口，直接放行；</li>
 *   <li>已标注 → 从请求头读取 Token（默认 <code>Authorization: Bearer xxx</code>）；</li>
 *   <li>未携带 Token → 抛 401「未登录，请先登录」；</li>
 *   <li>解析 Token：过期 → 401「登录已过期」；签名错误/被篡改 → 401「Token 无效」；</li>
 *   <li>校验通过 → 把用户信息放入 {@link UserContext}，供业务代码使用；</li>
 *   <li>请求结束后清理 ThreadLocal，防止线程复用造成数据串号。</li>
 * </ol>
 *
 * <p>抛出的异常会交给全局异常处理器（GlobalExceptionHandler）统一转换为 Result 返回。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    /** 备用请求头：方便用 curl / Postman 快速测试 */
    private static final String TOKEN_HEADER_FALLBACK = "token";

    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 跨域预检请求直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // 非 Controller 方法（静态资源、错误页等）放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // ① 是否需要登录：方法上的注解优先，其次看所在类
        LoginRequired loginRequired = handlerMethod.getMethodAnnotation(LoginRequired.class);
        if (loginRequired == null) {
            loginRequired = handlerMethod.getBeanType().getAnnotation(LoginRequired.class);
        }
        if (loginRequired == null) {
            return true;
        }

        // ② 取 Token
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            log.warn("[鉴权失败] 未携带 Token: {} {}", request.getMethod(), request.getRequestURI());
            throw UserException.tokenMissing();
        }

        // ③ 解析 Token（过期 / 无效会抛出对应的 401 异常）
        Claims claims = jwtUtil.parseToken(token);
        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("username", String.class);
        Integer role = claims.get("role", Integer.class);

        // ④ 需要管理员的接口做角色校验（预留后台管理接口使用）
        if (loginRequired.admin() && (role == null || role != 1)) {
            log.warn("[鉴权失败] 需要管理员权限，当前用户={} role={}", username, role);
            throw new BusinessException(UserResultCode.NO_PERMISSION.getCode(), UserResultCode.NO_PERMISSION.getMessage());
        }

        // ⑤ 写入上下文
        UserContext.set(new LoginUser(userId, username, role));
        log.debug("[鉴权通过] userId={} username={} -> {} {}", userId, username, request.getMethod(), request.getRequestURI());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // ⑥ 必须清理，避免线程池复用导致上一个请求的用户信息残留
        UserContext.clear();
    }

    /**
     * 从请求头解析 Token，兼容两种写法：
     * <pre>
     * Authorization: Bearer eyJhbGciOi...
     * token: eyJhbGciOi...
     * </pre>
     */
    private String resolveToken(HttpServletRequest request) {
        String headerValue = request.getHeader(jwtProperties.getHeader());
        if (StringUtils.hasText(headerValue)) {
            String prefix = jwtProperties.getPrefix();
            if (StringUtils.hasText(prefix) && headerValue.startsWith(prefix)) {
                return headerValue.substring(prefix.length()).trim();
            }
            // 没带前缀也容忍（例如直接放 token）
            return headerValue.trim();
        }
        String fallback = request.getHeader(TOKEN_HEADER_FALLBACK);
        return StringUtils.hasText(fallback) ? fallback.trim() : null;
    }
}
