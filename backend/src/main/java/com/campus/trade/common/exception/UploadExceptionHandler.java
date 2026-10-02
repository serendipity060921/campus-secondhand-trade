package com.campus.trade.common.exception;

import com.campus.trade.common.result.ProductResultCode;
import com.campus.trade.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 上传相关异常的控制器通知（v0.05）。
 *
 * <p>为什么单独建一个类：文件超过 <code>spring.servlet.multipart.max-file-size</code> 时，
 * 异常在 Servlet 解析 multipart 阶段就抛出（早于进入 Controller），
 * 由 Spring 的 MaxUploadSizeExceededException 表达。</p>
 *
 * <p>这里用 <b>@Order(HIGHEST_PRECEDENCE)</b> 让它优先于脚手架原有的
 * GlobalExceptionHandler（其兜底 handler 会把它变成 500），
 * 从而返回与业务一致的 3007「图片大小超出限制」，并且<b>不需要修改</b>原有全局异常处理器。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class UploadExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("[上传超限] {}", e.getMessage());
        return Result.error(ProductResultCode.FILE_SIZE_EXCEEDED.getCode(),
                ProductResultCode.FILE_SIZE_EXCEEDED.getMessage());
    }
}
