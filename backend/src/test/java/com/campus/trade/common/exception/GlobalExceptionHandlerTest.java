package com.campus.trade.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.core.MethodParameter;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import com.campus.trade.common.result.ProductResultCode;
import com.campus.trade.common.result.Result;
import com.campus.trade.common.result.ResultCode;

/**
 * 全局异常处理器验证（v0.17）
 *
 * <p>{@link GlobalExceptionHandler} 是全站对外的"统一响应出口"：无论哪里抛出什么异常，
 * 前端拿到的都必须是 <code>{ code, message, data }</code> 结构，否则前端拦截器会失效、
 * 页面会显示 Spring 默认错误页。</p>
 *
 * <p>因此这里逐个覆盖 10 个处理分支，锁定三件事：</p>
 * <ol>
 *   <li>每类异常映射到<b>正确的业务码</b>（参数类 400 / 方法不支持 405 / 404 / 唯一键冲突 1002 / 兜底 500）；</li>
 *   <li>提示语对用户可读，且参数类异常会把<b>具体字段与缺失项</b>带出来；</li>
 *   <li>所有分支都<b>不返回 data</b>，且都带服务端时间戳。</li>
 * </ol>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        return req;
    }

    /** 供构造 MethodParameter 用的占位方法 */
    @SuppressWarnings("unused")
    private void dummyMethod(String arg) {
        // 仅用于反射取参数元信息
    }

    private MethodParameter firstParamOfDummy() throws Exception {
        Method m = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyMethod", String.class);
        return new MethodParameter(m, 0);
    }

    @Nested
    @DisplayName("一、业务异常：原样透传业务码与提示")
    class Business {

        @Test
        @DisplayName("BusinessException → 使用它自带的 code 与 message")
        void businessException() {
            BusinessException e = new BusinessException(3001, "商品不存在或已被删除");
            Result<Void> r = handler.handleBusinessException(e, request("GET", "/api/product/1"));
            assertEquals(3001, r.getCode());
            assertEquals("商品不存在或已被删除", r.getMessage());
            assertNull(r.getData());
        }

        @Test
        @DisplayName("内容机审拒绝（3010）也能原样返回，并带上具体原因")
        void moderationRejection() {
            ProductException e = ProductException.contentRejected("标题包含违规内容「代写」");
            Result<Void> r = handler.handleBusinessException(e, request("POST", "/api/product/publish"));
            assertEquals(ProductResultCode.CONTENT_REJECTED.getCode(), r.getCode());
            assertEquals(3010, r.getCode());
            assertTrue(r.getMessage().contains("代写"));
        }

        @Test
        @DisplayName("限流异常（429）同样走业务异常分支")
        void tooManyRequests() {
            BusinessException e = new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), "操作过于频繁");
            assertEquals(429, handler.handleBusinessException(e, request("POST", "/api/user/login")).getCode());
        }
    }

    @Nested
    @DisplayName("二、参数类异常：统一 400，且提示具体是哪出错")
    class ParameterErrors {

        @Test
        @DisplayName("@RequestBody 校验失败：多个字段错误用「；」拼接")
        void methodArgumentNotValid() throws Exception {
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "dto");
            binding.addError(new FieldError("dto", "title", "请输入商品名称"));
            binding.addError(new FieldError("dto", "price", "售价必须大于 0"));
            MethodArgumentNotValidException e =
                    new MethodArgumentNotValidException(firstParamOfDummy(), binding);

            Result<Void> r = handler.handleMethodArgumentNotValidException(e);

            assertEquals(ResultCode.PARAM_ERROR.getCode(), r.getCode());
            assertEquals(400, r.getCode());
            assertTrue(r.getMessage().contains("请输入商品名称"));
            assertTrue(r.getMessage().contains("售价必须大于 0"));
            assertTrue(r.getMessage().contains("；"), "多个错误应以「；」分隔：" + r.getMessage());
        }

        @Test
        @DisplayName("表单/查询参数绑定失败：同样是 400")
        void bindException() {
            BindException e = new BindException(new Object(), "query");
            e.addError(new FieldError("query", "page", "页码必须为正整数"));

            Result<Void> r = handler.handleBindException(e);

            assertEquals(400, r.getCode());
            assertEquals("页码必须为正整数", r.getMessage());
        }

        @Test
        @DisplayName("缺少必填参数：提示里带参数名")
        void missingParam() {
            MissingServletRequestParameterException e =
                    new MissingServletRequestParameterException("page", "int");
            Result<Void> r = handler.handleMissingParam(e);
            assertEquals(400, r.getCode());
            assertTrue(r.getMessage().contains("缺少必填参数"));
            assertTrue(r.getMessage().contains("page"));
        }

        @Test
        @DisplayName("参数类型不匹配：提示里带参数名")
        void typeMismatch() {
            MethodArgumentTypeMismatchException e =
                    new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, null);
            Result<Void> r = handler.handleTypeMismatch(e);
            assertEquals(400, r.getCode());
            assertTrue(r.getMessage().contains("参数类型错误"));
            assertTrue(r.getMessage().contains("id"));
        }

        @Test
        @DisplayName("请求体 JSON 解析失败：给出可读提示（不暴露解析堆栈）")
        void notReadable() {
            HttpMessageNotReadableException e =
                    new HttpMessageNotReadableException("JSON parse error", (org.springframework.http.HttpInputMessage) null);
            Result<Void> r = handler.handleNotReadable(e);
            assertEquals(400, r.getCode());
            assertTrue(r.getMessage().contains("请求体格式错误"));
            assertTrue(r.getMessage().contains("JSON"));
        }
    }

    @Nested
    @DisplayName("三、协议类异常：405 / 404")
    class ProtocolErrors {

        @Test
        @DisplayName("请求方法不支持 → 405，并带出实际方法名")
        void methodNotSupported() {
            HttpRequestMethodNotSupportedException e = new HttpRequestMethodNotSupportedException("PATCH");
            Result<Void> r = handler.handleMethodNotSupported(e);
            assertEquals(ResultCode.METHOD_NOT_ALLOWED.getCode(), r.getCode());
            assertEquals(405, r.getCode());
            assertTrue(r.getMessage().contains("PATCH"));
        }

        @Test
        @DisplayName("接口不存在 → 404，并带出请求路径")
        void noHandlerFound() {
            NoHandlerFoundException e =
                    new NoHandlerFoundException("GET", "/api/not-exist", HttpHeaders.EMPTY);
            Result<Void> r = handler.handleNoHandlerFound(e);
            assertEquals(ResultCode.NOT_FOUND.getCode(), r.getCode());
            assertEquals(404, r.getCode());
            assertTrue(r.getMessage().contains("/api/not-exist"));
        }

        /**
         * 安全测试 S41 发现的缺口：客户端用 form-urlencoded 调 JSON 接口时，
         * 此前该异常没有被专门处理，会落到兜底分支返回 500（看起来像服务端故障）。
         */
        @Test
        @DisplayName("请求体类型不受支持 → 400 参数错误（而不是兜底 500）")
        void mediaTypeNotSupported() {
            org.springframework.web.HttpMediaTypeNotSupportedException e =
                    new org.springframework.web.HttpMediaTypeNotSupportedException(
                            org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED,
                            java.util.List.of());

            Result<Void> r = handler.handleMediaTypeNotSupported(e);

            assertEquals(ResultCode.PARAM_ERROR.getCode(), r.getCode());
            assertEquals(400, r.getCode());
            assertTrue(r.getMessage().contains("application/json"),
                    "提示应告诉调用方正确的类型：" + r.getMessage());
            assertNull(r.getData());
            assertNotNull(r.getTimestamp());
        }
    }

    @Nested
    @DisplayName("四、数据与兜底异常")
    class DataAndFallback {

        @Test
        @DisplayName("唯一键冲突 → 1002，且提示面向用户（不暴露索引名）")
        void duplicateKey() {
            DuplicateKeyException e = new DuplicateKeyException(
                    "Duplicate entry 'stu_demo-1' for key 'uk_user_product'");
            Result<Void> r = handler.handleDuplicateKey(e);
            assertEquals(ResultCode.DATA_ALREADY_EXIST.getCode(), r.getCode());
            assertEquals(1002, r.getCode());
            assertEquals("数据已存在（违反唯一约束）", r.getMessage());
        }

        @Test
        @DisplayName("未知异常 → 500，并把原因拼进提示（便于定位）")
        void unexpectedWithMessage() {
            Result<Void> r = handler.handleException(new IllegalStateException("boom"),
                    request("GET", "/api/product/list"));
            assertEquals(ResultCode.ERROR.getCode(), r.getCode());
            assertEquals(500, r.getCode());
            assertTrue(r.getMessage().contains("boom"));
            assertNull(r.getData());
        }

        @Test
        @DisplayName("未知异常且无 message → 给出可读兜底提示（引导看后端日志）")
        void unexpectedWithoutMessage() {
            Result<Void> r = handler.handleException(new RuntimeException(),
                    request("GET", "/api/product/list"));
            assertEquals(500, r.getCode());
            assertTrue(r.getMessage().contains("后端控制台日志"), "提示应引导排查：" + r.getMessage());
        }
    }

    @Nested
    @DisplayName("五、统一响应结构（所有分支都必须满足）")
    class ResponseShape {

        @Test
        @DisplayName("任何分支都不返回 data，且都带时间戳")
        void allBranchesShape() throws Exception {
            Result<?>[] results = {
                    handler.handleBusinessException(new BusinessException("x"), request("GET", "/a")),
                    handler.handleMissingParam(new MissingServletRequestParameterException("p", "int")),
                    handler.handleTypeMismatch(new MethodArgumentTypeMismatchException("a", Long.class, "id", null, null)),
                    handler.handleNotReadable(new HttpMessageNotReadableException("x", (org.springframework.http.HttpInputMessage) null)),
                    handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("PUT")),
                    handler.handleNoHandlerFound(new NoHandlerFoundException("GET", "/x", HttpHeaders.EMPTY)),
                    handler.handleDuplicateKey(new DuplicateKeyException("dup")),
                    handler.handleException(new RuntimeException("err"), request("GET", "/a")),
            };
            for (Result<?> r : results) {
                assertNotNull(r.getCode(), "响应必须带业务码");
                assertNotNull(r.getMessage(), "响应必须带提示信息");
                assertNull(r.getData(), "异常响应一律不携带 data（避免前端误用半截数据）");
                assertNotNull(r.getTimestamp(), "响应必须带服务端时间戳");
            }
        }

        @Test
        @DisplayName("参数类异常一律使用 400，不泄漏 500")
        void parameterErrorsNever500() {
            assertEquals(400, handler.handleMissingParam(
                    new MissingServletRequestParameterException("p", "int")).getCode());
            assertEquals(400, handler.handleTypeMismatch(
                    new MethodArgumentTypeMismatchException("a", Long.class, "id", null, null)).getCode());
            assertEquals(400, handler.handleNotReadable(
                    new HttpMessageNotReadableException("x", (org.springframework.http.HttpInputMessage) null)).getCode());
        }
    }
}
