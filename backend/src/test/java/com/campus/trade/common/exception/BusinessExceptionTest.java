package com.campus.trade.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.campus.trade.common.result.AdminResultCode;
import com.campus.trade.common.result.CategoryResultCode;
import com.campus.trade.common.result.FavoriteResultCode;
import com.campus.trade.common.result.MessageResultCode;
import com.campus.trade.common.result.OrderResultCode;
import com.campus.trade.common.result.ProductResultCode;
import com.campus.trade.common.result.ProfileResultCode;
import com.campus.trade.common.result.ResultCode;
import com.campus.trade.common.result.UserResultCode;

/**
 * 业务异常体系验证（v0.17）
 *
 * <p>业务异常是"接口返回码"的载体：Service 抛出，全局异常处理器据此生成统一响应。
 * 本测试锁定三件事：</p>
 * <ol>
 *   <li>构造器把 code 与 message 正确透传（前端依赖 code 做分支）；</li>
 *   <li>各模块异常都继承自 {@link BusinessException}（保证被统一处理器捕获）；</li>
 *   <li>带动态原因的工厂方法会把原因拼进 message（用户据此知道改哪里）。</li>
 * </ol>
 */
class BusinessExceptionTest {

    @Nested
    @DisplayName("一、BusinessException 三种构造方式")
    class Base {

        @Test
        @DisplayName("只给 message 时，code 取通用业务错误码 1000")
        void messageOnly() {
            BusinessException e = new BusinessException("出错了");
            assertEquals(ResultCode.BUSINESS_ERROR.getCode(), e.getCode());
            assertEquals(1000, e.getCode());
            assertEquals("出错了", e.getMessage());
        }

        @Test
        @DisplayName("同时给 code 与 message 时原样保留")
        void codeAndMessage() {
            BusinessException e = new BusinessException(3001, "商品不存在");
            assertEquals(3001, e.getCode());
            assertEquals("商品不存在", e.getMessage());
        }

        @Test
        @DisplayName("用 ResultCode 构造时，code 与 message 都取自该枚举")
        void fromResultCode() {
            BusinessException e = new BusinessException(ResultCode.NOT_FOUND);
            assertEquals(ResultCode.NOT_FOUND.getCode(), e.getCode());
            assertEquals(ResultCode.NOT_FOUND.getMessage(), e.getMessage());
        }

        @Test
        @DisplayName("它是 RuntimeException，能被全局处理器按异常捕获")
        void isRuntimeException() {
            assertInstanceOf(RuntimeException.class, new BusinessException("x"));
            assertInstanceOf(BusinessException.class, new BusinessException("x"));
        }
    }

    @Nested
    @DisplayName("二、各模块异常都继承自 BusinessException")
    class ModulesInheritBase {

        @Test
        @DisplayName("用户 / 个人资料 / 商品 / 收藏 / 消息 / 订单 / 分类 / 管理 异常")
        void allModules() {
            BusinessException[] all = {
                    new UserException(UserResultCode.USER_NOT_FOUND),
                    new ProfileException(ProfileResultCode.PHONE_EXISTS),
                    new ProductException(ProductResultCode.PRODUCT_NOT_FOUND),
                    new FavoriteException(FavoriteResultCode.ALREADY_FAVORITED),
                    new MessageException(MessageResultCode.CANNOT_SEND_TO_SELF),
                    new OrderException(OrderResultCode.ORDER_NOT_FOUND),
                    new CategoryException(CategoryResultCode.NOT_FOUND),
                    new AdminException(AdminResultCode.REPORT_NOT_FOUND),
            };
            for (BusinessException e : all) {
                assertInstanceOf(BusinessException.class, e,
                        e.getClass().getSimpleName() + " 必须继承 BusinessException，否则不会被统一处理");
                assertNotNull(e.getCode());
                assertNotNull(e.getMessage());
            }
        }

        @Test
        @DisplayName("各模块异常的 code/message 与传入的错误码一致")
        void codesPassThrough() {
            assertException(new UserException(UserResultCode.PASSWORD_ERROR), UserResultCode.PASSWORD_ERROR.getCode());
            assertException(new ProductException(ProductResultCode.STATUS_ILLEGAL), ProductResultCode.STATUS_ILLEGAL.getCode());
            assertException(new OrderException(OrderResultCode.STATUS_ILLEGAL), OrderResultCode.STATUS_ILLEGAL.getCode());
            assertException(new AdminException(AdminResultCode.USER_STATUS_ILLEGAL), AdminResultCode.USER_STATUS_ILLEGAL.getCode());
        }

        private void assertException(BusinessException e, int expectedCode) {
            assertEquals(expectedCode, e.getCode());
            assertFalse(e.getMessage().isBlank());
        }

        @Test
        @DisplayName("各模块也支持自定义 code + message")
        void customCodeAndMessage() {
            BusinessException e = new OrderException(6999, "自定义错误");
            assertEquals(6999, e.getCode());
            assertEquals("自定义错误", e.getMessage());
        }
    }

    @Nested
    @DisplayName("三、商品模块的语义化工厂方法（含动态原因）")
    class ProductFactories {

        @Test
        @DisplayName("notFound / noPermission / statusIllegal / categoryNotFound 使用预设文案")
        void presetFactories() {
            assertEquals(3001, ProductException.notFound().getCode());
            assertEquals(3002, ProductException.noPermission().getCode());
            assertEquals(3003, ProductException.statusIllegal().getCode());
            assertEquals(3004, ProductException.categoryNotFound().getCode());
        }

        @Test
        @DisplayName("paramIllegal 会把具体细节拼进 message")
        void paramIllegalCarriesDetail() {
            ProductException e = ProductException.paramIllegal("售价必须大于 0");
            assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getCode());
            assertEquals(400, e.getCode());
            assertTrue(e.getMessage().contains("售价必须大于 0"), "message 应包含具体原因：" + e.getMessage());
            assertTrue(e.getMessage().contains("参数不合法"), "message 应保留标准前缀");
        }

        /**
         * ★ 内容机审的关键契约：命中违禁词时返回 3010 并把命中的原因带给用户。
         * 用户据此知道改哪里，前端与端到端用例也依赖这个码。
         */
        @Test
        @DisplayName("contentRejected 返回 3010 并携带具体命中原因")
        void contentRejectedCarriesReason() {
            ProductException e = ProductException.contentRejected("标题包含违规内容「代写」");
            assertEquals(3010, e.getCode());
            assertEquals(ProductResultCode.CONTENT_REJECTED.getCode(), e.getCode());
            assertTrue(e.getMessage().contains("内容不符合平台规范"));
            assertTrue(e.getMessage().contains("代写"), "应把命中内容回传给用户：" + e.getMessage());
        }

        @Test
        @DisplayName("contentRejected 未给原因时只返回标准文案（不出现多余符号）")
        void contentRejectedWithoutDetail() {
            assertEquals(ProductResultCode.CONTENT_REJECTED.getMessage(),
                    ProductException.contentRejected(null).getMessage());
            assertEquals(ProductResultCode.CONTENT_REJECTED.getMessage(),
                    ProductException.contentRejected("  ").getMessage());
        }

        @Test
        @DisplayName("fileEmpty 使用上传相关错误码")
        void fileEmpty() {
            assertEquals(3005, ProductException.fileEmpty().getCode());
        }

        @Test
        @DisplayName("工厂每次返回新实例（异常可安全重复抛出）")
        void factoriesReturnNewInstances() {
            assertSame(ProductResultCode.PRODUCT_NOT_FOUND,
                    ProductResultCode.valueOf(ProductException.notFound().getCode() == 3001
                            ? "PRODUCT_NOT_FOUND" : "PRODUCT_NOT_FOUND"));
            assertFalse(ProductException.notFound() == ProductException.notFound());
        }
    }
}
