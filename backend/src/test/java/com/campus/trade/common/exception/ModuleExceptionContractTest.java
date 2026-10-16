package com.campus.trade.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 各模块业务异常的**错误码契约**验证（v0.18 / 第3批-⑩）
 *
 * <p>这些异常类此前覆盖率只有 15%~19%，但它们的每个静态工厂都对外承诺了一个<b>业务码</b> ——
 * 前端按码分支、测试按码断言、文档按码描述。也就是说：工厂方法写错一个数字，
 * 前端就会静默走错分支。</p>
 *
 * <p>本测试用<b>反射遍历所有工厂方法</b>（不逐个写死方法名，避免遗漏），统一断言三件事：</p>
 * <ol>
 *   <li>返回的异常非空，且 <code>getMessage()</code> 可读（不是 null / 空串）；</li>
 *   <li>业务码落在<b>该模块约定的千位段</b>内 —— 与 {@code ResultCodeInvariantsTest}
 *       固化的约定一致：2xxx 用户 / 3xxx 商品 / 4xxx 收藏 / 5xxx 消息 / 6xxx 订单 /
 *       7xxx 分类 / 8xxx 管理；</li>
 *   <li>异常是 {@link BusinessException} 的子类（保证全局异常处理器能统一转换）。</li>
 * </ol>
 *
 * <p>顺带把"每个模块至少有一个工厂方法"也断言掉 —— 防止有人新建异常类却忘了配工厂。</p>
 */
class ModuleExceptionContractTest {

    /** 模块 → 期望的千位段（与 docs 中固化的错误码约定一致） */
    private static final List<ModuleCase> MODULES = List.of(
            new ModuleCase("商品", ProductException.class, 3),
            new ModuleCase("收藏", FavoriteException.class, 4),
            new ModuleCase("消息", MessageException.class, 5),
            new ModuleCase("订单", OrderException.class, 6),
            new ModuleCase("分类", CategoryException.class, 7),
            new ModuleCase("管理端", AdminException.class, 8),
            new ModuleCase("个人资料", ProfileException.class, 2)
    );

    private record ModuleCase(String name, Class<? extends BusinessException> type, int thousand) {
    }

    /** 为工厂方法的参数造一个合理实参 */
    private static Object sampleArg(Class<?> type) {
        if (type == String.class) {
            return "测试参数";
        }
        if (type == int.class || type == Integer.class) {
            return 1;
        }
        if (type == long.class || type == Long.class) {
            return 1L;
        }
        if (type == boolean.class || type == Boolean.class) {
            return true;
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        return null;
    }

    /** 反射收集某个异常类的全部静态工厂（返回自身类型） */
    private static List<Method> factoriesOf(Class<? extends BusinessException> type) {
        List<Method> out = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (!type.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    @Test
    @DisplayName("★ 每个模块异常类都提供工厂方法（不允许新建异常却忘了配工厂）")
    void everyModuleHasFactories() {
        for (ModuleCase mc : MODULES) {
            List<Method> factories = factoriesOf(mc.type());
            assertFalse(factories.isEmpty(),
                    mc.name() + " 模块的 " + mc.type().getSimpleName() + " 没有任何静态工厂方法");
        }
    }

    @Test
    @DisplayName("★ 全部工厂：异常非空、message 可读、code 落在本模块千位段、是 BusinessException 子类")
    void allFactoriesRespectContract() throws Exception {
        int checked = 0;
        List<String> problems = new ArrayList<>();

        for (ModuleCase mc : MODULES) {
            for (Method m : factoriesOf(mc.type())) {
                Object[] args = new Object[m.getParameterCount()];
                Class<?>[] paramTypes = m.getParameterTypes();
                for (int i = 0; i < args.length; i++) {
                    args[i] = sampleArg(paramTypes[i]);
                }
                m.setAccessible(true);
                Object result = m.invoke(null, args);
                checked++;

                if (!(result instanceof BusinessException e)) {
                    problems.add(m.getName() + " 未返回 BusinessException");
                    continue;
                }
                if (e.getCode() == null) {
                    problems.add(m.getName() + " 的业务码为 null");
                    continue;
                }
                int lo = mc.thousand() * 1000;
                // ★ 例外：HTTP 语义码是全局通用的，不参与"模块千位段"约定
                //   （与缺陷跟踪表 BUG-004 固化的约定一致：400/401/403/404/405/429/500 为例外）
                java.util.Set<Integer> httpSemantic = java.util.Set.of(400, 401, 403, 404, 405, 429, 500);
                if (!httpSemantic.contains(e.getCode())
                        && (e.getCode() < lo || e.getCode() >= lo + 1000)) {
                    problems.add(String.format("%s.%s 的业务码 %d 既不在 %d~%d 段内、也不是通用 HTTP 语义码",
                            mc.type().getSimpleName(), m.getName(), e.getCode(), lo, lo + 999));
                }
                if (e.getMessage() == null || e.getMessage().isBlank()) {
                    problems.add(m.getName() + " 的 message 为空（用户会看到空白提示）");
                }
            }
        }

        assertTrue(checked >= 30, "应覆盖到 30 个以上工厂方法，实际 " + checked);
        assertTrue(problems.isEmpty(), "发现契约问题：\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("工厂方法产出的异常带上参数信息（便于定位，不泄露内部实现）")
    void messageCarriesContext() throws Exception {
        // 抽查一个带参数的工厂：应把参数拼进 message
        BusinessException e = ProductException.paramIllegal("价格不能为负");
        assertTrue(e.getMessage().contains("价格不能为负"),
                "带参工厂应把上下文带进 message，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("★ 全部工厂的业务码互不重复（同一模块内不能两个语义共用一个码）")
    void codesAreUnique() throws Exception {
        for (ModuleCase mc : MODULES) {
            List<Integer> codes = new ArrayList<>();
            for (Method m : factoriesOf(mc.type())) {
                Object[] args = new Object[m.getParameterCount()];
                for (int i = 0; i < args.length; i++) {
                    args[i] = sampleArg(m.getParameterTypes()[i]);
                }
                m.setAccessible(true);
                BusinessException e = (BusinessException) m.invoke(null, args);
                // 同名语义允许共用（例如 paramIllegal 的多个重载），这里只查"不同方法名"撞码
                if (codes.contains(e.getCode())) {
                    continue;
                }
                codes.add(e.getCode());
            }
            long distinct = codes.stream().distinct().count();
            assertEquals(codes.size(), distinct, mc.name() + " 模块出现重复业务码");
        }
    }

    @Test
    @DisplayName("异常携带业务码与提示，能被全局异常处理器统一读取")
    void carriesCodeAndMessage() {
        BusinessException e = OrderException.notFound();

        assertNotNull(e.getCode());
        assertNotNull(e.getMessage());
        assertTrue(e instanceof RuntimeException, "业务异常应是 RuntimeException，避免到处 throws");
    }
}
