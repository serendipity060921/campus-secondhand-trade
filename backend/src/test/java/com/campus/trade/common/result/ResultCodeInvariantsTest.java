package com.campus.trade.common.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 错误码枚举的「不变量」验证（v0.17）
 *
 * <p>这些枚举定义了全站对外返回的错误码，一旦出现重复或越界，前端与测试都会受影响。
 * 本测试用反射一次性覆盖 {@code common.result} 下的全部错误码枚举，
 * 把"约定"变成可执行、可回归的断言 —— 新增枚举或新增错误码时若违反约定，CI 会直接失败。</p>
 *
 * <p>★ 维护提示：新增错误码枚举时，请把它加入 {@link #ALL_ENUMS} 与 {@link #SEGMENTS}。</p>
 */
class ResultCodeInvariantsTest {

    /** 全部错误码枚举（新增枚举时请一并加入） */
    @SuppressWarnings("rawtypes")
    private static final Class<? extends Enum>[] ALL_ENUMS = new Class[]{
            AdminResultCode.class,
            CategoryResultCode.class,
            FavoriteResultCode.class,
            MessageResultCode.class,
            OrderResultCode.class,
            ProductResultCode.class,
            ProfileResultCode.class,
            ResultCode.class,
            UserResultCode.class,
    };

    /**
     * 业务码段位规范：每个模块占用自己的千位段，避免跨模块撞码。
     * 仅约束"业务码"（≥1000）；HTTP 语义码（400/401/403/404/405/429/500）不参与段位约束。
     */
    private static final Map<Class<?>, int[]> SEGMENTS = new HashMap<>();

    static {
        SEGMENTS.put(UserResultCode.class, new int[]{2000, 2999});
        SEGMENTS.put(ProfileResultCode.class, new int[]{2000, 2999});   // 个人资料属于用户域
        SEGMENTS.put(ProductResultCode.class, new int[]{3000, 3999});
        SEGMENTS.put(FavoriteResultCode.class, new int[]{4000, 4999});
        SEGMENTS.put(MessageResultCode.class, new int[]{5000, 5999});
        SEGMENTS.put(OrderResultCode.class, new int[]{6000, 6999});
        SEGMENTS.put(CategoryResultCode.class, new int[]{7000, 7999});
        SEGMENTS.put(AdminResultCode.class, new int[]{8000, 8999});
    }

    /** HTTP 语义码：与 HTTP 状态码含义对齐，允许在同一枚举内重复 */
    private static final Set<Integer> HTTP_SEMANTIC = Set.of(400, 401, 403, 404, 405, 429, 500);

    private static boolean isBusinessCode(int code) {
        return code >= 1000 && !HTTP_SEMANTIC.contains(code);
    }

    /** 逐个读取枚举常量的 code / message（依赖 Lombok 生成的 getter） */
    @SuppressWarnings("rawtypes")
    private static List<Object[]> entries(Class<? extends Enum> type) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (Object constant : type.getEnumConstants()) {
            var getCode = type.getMethod("getCode");
            var getMessage = type.getMethod("getMessage");
            rows.add(new Object[]{type.getSimpleName(), constant.toString(),
                    getCode.invoke(constant), getMessage.invoke(constant)});
        }
        return rows;
    }

    @Nested
    @DisplayName("一、错误码与提示文案的完整性")
    class Completeness {

        @Test
        @DisplayName("每个枚举常量都有非空的 code 与 message")
        void codeAndMessagePresent() throws Exception {
            for (Class<? extends Enum> type : ALL_ENUMS) {
                for (Object[] row : entries(type)) {
                    assertNotNull(row[2], row[0] + "." + row[1] + " 的 code 不应为空");
                    assertNotNull(row[3], row[0] + "." + row[1] + " 的 message 不应为空");
                    assertFalse(((String) row[3]).isBlank(),
                            row[0] + "." + row[1] + " 的 message 不应是空白");
                }
            }
        }

        @Test
        @DisplayName("枚举常量数量合理（防止误删）")
        void enumNotEmpty() {
            for (Class<? extends Enum> type : ALL_ENUMS) {
                assertTrue(type.getEnumConstants().length > 0, type.getSimpleName() + " 不应为空枚举");
            }
        }
    }

    @Nested
    @DisplayName("二、业务码段位规范（防跨模块撞码）")
    class Segment {

        @Test
        @DisplayName("每个模块的业务码都落在自己的千位段内")
        void businessCodeInOwnSegment() throws Exception {
            for (Map.Entry<Class<?>, int[]> e : SEGMENTS.entrySet()) {
                Class<?> type = e.getKey();
                int lo = e.getValue()[0];
                int hi = e.getValue()[1];
                for (Object[] row : entries(type.asSubclass(Enum.class))) {
                    int code = (Integer) row[2];
                    if (!isBusinessCode(code)) {
                        continue;   // HTTP 语义码不参与段位约束
                    }
                    assertTrue(code >= lo && code <= hi,
                            String.format("%s.%s 的业务码 %d 超出约定段位 %d-%d",
                                    row[0], row[1], code, lo, hi));
                }
            }
        }

        @Test
        @DisplayName("同一枚举内业务码不重复（HTTP 语义码允许重复）")
        void businessCodeUniqueInEnum() throws Exception {
            for (Class<? extends Enum> type : ALL_ENUMS) {
                Map<Integer, String> seen = new HashMap<>();
                for (Object[] row : entries(type)) {
                    int code = (Integer) row[2];
                    if (!isBusinessCode(code)) {
                        continue;
                    }
                    String name = (String) row[1];
                    String previous = seen.put(code, name);
                    if (previous != null) {
                        throw new AssertionError(String.format(
                                "%s 内业务码 %d 被重复使用：%s 与 %s", row[0], code, previous, name));
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("三、关键错误码的契约（回归保护）")
    class Contracts {

        /**
         * 这段源自一次真实教训：接入内容机审时新增错误码，差点占用已被
         * FILE_UPLOAD_ERROR 使用的 3008。此处把占用关系固定下来，避免再次撞码。
         */
        @Test
        @DisplayName("商品模块错误码占用关系固定（3001~3010）")
        void productCodesFrozen() {
            assertEquals(3001, ProductResultCode.PRODUCT_NOT_FOUND.getCode());
            assertEquals(3002, ProductResultCode.NO_PERMISSION.getCode());
            assertEquals(3003, ProductResultCode.STATUS_ILLEGAL.getCode());
            assertEquals(3004, ProductResultCode.CATEGORY_NOT_FOUND.getCode());
            assertEquals(3005, ProductResultCode.FILE_EMPTY.getCode());
            assertEquals(3006, ProductResultCode.FILE_TYPE_NOT_ALLOWED.getCode());
            assertEquals(3007, ProductResultCode.FILE_SIZE_EXCEEDED.getCode());
            assertEquals(3008, ProductResultCode.FILE_UPLOAD_ERROR.getCode());
            assertEquals(3009, ProductResultCode.IMAGE_COUNT_EXCEEDED.getCode());
            assertEquals(3010, ProductResultCode.CONTENT_REJECTED.getCode());
        }

        @Test
        @DisplayName("通用状态码与 HTTP 语义一致")
        void genericCodesMatchHttpSemantics() {
            assertEquals(200, ResultCode.SUCCESS.getCode());
            assertEquals(400, ResultCode.PARAM_ERROR.getCode());
            assertEquals(401, ResultCode.UNAUTHORIZED.getCode());
            assertEquals(403, ResultCode.FORBIDDEN.getCode());
            assertEquals(404, ResultCode.NOT_FOUND.getCode());
            assertEquals(405, ResultCode.METHOD_NOT_ALLOWED.getCode());
            assertEquals(429, ResultCode.TOO_MANY_REQUESTS.getCode());
            assertEquals(500, ResultCode.ERROR.getCode());
        }

        @Test
        @DisplayName("登录/鉴权类错误码统一使用 401（前端只需判断一个码）")
        void authCodesAreHttp401() {
            for (UserResultCode c : new UserResultCode[]{
                    UserResultCode.TOKEN_MISSING, UserResultCode.TOKEN_INVALID,
                    UserResultCode.TOKEN_EXPIRED, UserResultCode.UNAUTHORIZED}) {
                assertEquals(401, c.getCode(), c.name() + " 应为 401");
            }
            assertEquals(403, UserResultCode.NO_PERMISSION.getCode());
        }
    }
}
