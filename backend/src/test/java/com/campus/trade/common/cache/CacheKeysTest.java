package com.campus.trade.common.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.campus.trade.config.CacheProperties;

/**
 * 缓存键命名规范验证（v0.17）
 *
 * <p>缓存键看起来只是字符串拼接，但拼错会导致两类严重问题：</p>
 * <ul>
 *   <li><b>串键</b>：不同业务的键撞在一起 → 读到别人的数据（错的缓存比没有缓存更危险）；</li>
 *   <li><b>删不掉</b>：失效时按通配模式删除，若模式与实际键不匹配 → 脏数据一直留在缓存里。</li>
 * </ul>
 * <p>因此这里重点验证三件事：前缀与层级规范、不同业务/维度彼此隔离、
 * 通配模式确实能匹配到它要删的具体键。</p>
 */
class CacheKeysTest {

    private CacheKeys keys;

    @BeforeEach
    void setUp() {
        CacheProperties props = new CacheProperties();
        props.setKeyPrefix("campus");
        keys = new CacheKeys(props);
    }

    @Nested
    @DisplayName("一、前缀与层级规范")
    class Naming {

        @Test
        @DisplayName("所有键都以配置的前缀开头，且用冒号分层")
        void allKeysUsePrefix() {
            String[] all = {
                    keys.productList("page=1"), keys.productDetail(1L), keys.categoryList(),
                    keys.recommend(9L, 8, "item-cf"), keys.recommend(null, 8, "hot"),
                    keys.hotList(), keys.viewDedup(1L, "u:9"), keys.viewCounter(1L),
                    keys.rateLimit("publish", "user:9"), keys.jwtBlacklist("abc"),
            };
            for (String k : all) {
                assertTrue(k.startsWith("campus:"), "键必须以配置前缀开头：" + k);
                assertTrue(k.length() > "campus:".length(), "键不能只有前缀：" + k);
                assertFalse(k.contains("::"), "不应出现空层级（双冒号）：" + k);
            }
        }

        @Test
        @DisplayName("前缀可配置（多应用共用 Redis 时用不同前缀隔离）")
        void prefixIsConfigurable() {
            CacheProperties other = new CacheProperties();
            other.setKeyPrefix("shop");
            CacheKeys k2 = new CacheKeys(other);
            assertTrue(k2.productDetail(1L).startsWith("shop:"));
            assertTrue(keys.productDetail(1L).startsWith("campus:"));
            assertNotEquals(keys.productDetail(1L), k2.productDetail(1L));
        }

        @Test
        @DisplayName("不同业务段彼此隔离（商品/分类/推荐/热门/浏览量/限流/黑名单）")
        void differentBusinessSegments() {
            String[] segs = {
                    keys.productList("all"), keys.productDetail(1L), keys.categoryList(),
                    keys.recommend(1L, 8, "x"), keys.hotList(), keys.viewDedup(1L, "v"),
                    keys.rateLimit("login", "ip:1"), keys.jwtBlacklist("h"),
            };
            assertEquals(segs.length, java.util.Arrays.stream(segs).distinct().count(),
                    "不同业务的键不应相同");
        }
    }

    @Nested
    @DisplayName("二、维度正确性（同业务不同维度必须区分）")
    class Dimensions {

        @Test
        @DisplayName("商品详情：不同商品不同键，且键里含商品 id")
        void productDetailById() {
            assertNotEquals(keys.productDetail(1L), keys.productDetail(2L));
            assertTrue(keys.productDetail(42L).endsWith(":product:detail:42"));
        }

        @Test
        @DisplayName("商品列表：不同查询条件不同键")
        void productListByCondition() {
            assertNotEquals(keys.productList("page=1"), keys.productList("page=2"));
            assertTrue(keys.productList("page=1&size=12").endsWith(":product:list:page=1&size=12"));
        }

        @Test
        @DisplayName("推荐：未登录写 anon，登录写 userId；size 与策略也参与区分")
        void recommendDimensions() {
            assertTrue(keys.recommend(null, 8, "item-cf").contains(":recommend:anon:8:item-cf"));
            assertTrue(keys.recommend(9L, 8, "item-cf").contains(":recommend:9:8:item-cf"));
            assertNotEquals(keys.recommend(9L, 8, "a"), keys.recommend(9L, 8, "b"));
            assertNotEquals(keys.recommend(9L, 8, "a"), keys.recommend(9L, 12, "a"));
        }

        @Test
        @DisplayName("浏览量去重：同一商品不同访问者必须是不同键（否则会把别人的访问误去重）")
        void viewDedupByVisitor() {
            assertNotEquals(keys.viewDedup(1L, "user:9"), keys.viewDedup(1L, "user:10"));
            assertNotEquals(keys.viewDedup(1L, "user:9"), keys.viewDedup(2L, "user:9"));
        }

        @Test
        @DisplayName("热门榜：键里带当天日期（自然日切换后自动换键）")
        void hotListCarriesDay() {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            assertEquals("campus:hot:" + today, keys.hotList());
        }

        @Test
        @DisplayName("限流：按「业务 + 维度」隔离")
        void rateLimitByBizAndDimension() {
            assertNotEquals(keys.rateLimit("login", "user:1"), keys.rateLimit("publish", "user:1"));
            assertNotEquals(keys.rateLimit("login", "user:1"), keys.rateLimit("login", "user:2"));
            assertTrue(keys.rateLimit("publish", "user:1").endsWith(":ratelimit:publish:user:1"));
        }
    }

    @Nested
    @DisplayName("三、通配模式能删到它该删的键（失效正确性）")
    class Patterns {

        private void assertPatternCovers(String pattern, String... concreteKeys) {
            assertTrue(pattern.endsWith("*"), "通配模式应以 * 结尾：" + pattern);
            String prefix = pattern.substring(0, pattern.length() - 1);
            for (String k : concreteKeys) {
                assertTrue(k.startsWith(prefix),
                        String.format("模式 %s 匹配不到具体键 %s（会导致脏缓存删不掉）", pattern, k));
            }
        }

        @Test
        @DisplayName("商品列表 / 详情 的通配模式覆盖对应具体键")
        void productPatterns() {
            assertPatternCovers(keys.productListPattern(), keys.productList("all"), keys.productList("page=1&size=12"));
            assertPatternCovers(keys.productDetailPattern(), keys.productDetail(1L), keys.productDetail(999L));
        }

        @Test
        @DisplayName("分类列表模式同时覆盖不带后缀与带后缀两种形态")
        void categoryPattern() {
            String base = "campus:category:list";
            assertTrue(keys.categoryList().startsWith("campus:category:list"));
            assertPatternCovers(keys.categoryListPattern(), base, base + ":onlyTop=true");
        }

        @Test
        @DisplayName("推荐模式（全局 / 指定用户）覆盖对应键")
        void recommendPatterns() {
            assertPatternCovers(keys.recommendPattern(), keys.recommend(null, 8, "x"), keys.recommend(9L, 8, "x"));
            assertPatternCovers(keys.recommendUserPattern(9L), keys.recommend(9L, 8, "x"));
            assertFalse(keys.recommend(10L, 8, "x").startsWith(
                            keys.recommendUserPattern(9L).replace("*", "")),
                    "指定用户的模式不应匹配到其他用户的键");
        }

        @Test
        @DisplayName("热门榜模式覆盖当天键")
        void hotPattern() {
            assertPatternCovers(keys.hotListPattern(), keys.hotList());
        }
    }

    @Nested
    @DisplayName("四、条件指纹（必须与参数顺序无关，否则缓存命中率会崩）")
    class ConditionFingerprint {

        @Test
        @DisplayName("参数顺序不同但内容相同 → 同一个指纹")
        void orderIndependent() {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("page", 1);
            a.put("size", 12);
            a.put("categoryId", 3);

            Map<String, Object> b = new LinkedHashMap<>();
            b.put("categoryId", 3);
            b.put("size", 12);
            b.put("page", 1);

            assertEquals(keys.conditionOf(a), keys.conditionOf(b),
                    "同一组参数换个顺序必须得到同一个缓存键，否则缓存永远命中不了");
        }

        @Test
        @DisplayName("剔除 null 与空串（缺省参数不应产生多个不同键）")
        void ignoresNullAndBlank() {
            Map<String, Object> withNull = new LinkedHashMap<>();
            withNull.put("page", 1);
            withNull.put("keyword", null);
            withNull.put("campus", "");

            Map<String, Object> withoutNull = new LinkedHashMap<>();
            withoutNull.put("page", 1);

            assertEquals(keys.conditionOf(withoutNull), keys.conditionOf(withNull));
        }

        @Test
        @DisplayName("空条件返回 all；单个条件原样输出")
        void emptyAndSingle() {
            assertEquals("all", keys.conditionOf(null));
            assertEquals("all", keys.conditionOf(Map.of()));
            assertEquals("page=1", keys.conditionOf(Map.of("page", 1)));
        }

        @Test
        @DisplayName("值与值之间用 & 连接，且顺序稳定（按 key 升序）")
        void joinedAndSorted() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("size", 12);
            m.put("page", 1);
            assertEquals("page=1&size=12", keys.conditionOf(m));
        }

        @Test
        @DisplayName("空格与斜杠被替换为下划线（避免键里出现空白与路径符号）")
        void sanitizesWhitespaceAndSlash() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keyword", "数据结构 教材");
            m.put("path", "a/b");
            String c = keys.conditionOf(m);
            assertFalse(c.contains(" "), "条件指纹不应包含空格：" + c);
            assertFalse(c.contains("/"), "条件指纹不应包含斜杠：" + c);
            assertTrue(c.contains("数据结构_教材"));
            assertTrue(c.contains("a_b"));
        }

        @Test
        @DisplayName("不同参数值必然得到不同指纹")
        void differentValuesDifferentFingerprint() {
            assertNotEquals(keys.conditionOf(Map.of("page", 1)), keys.conditionOf(Map.of("page", 2)));
            assertNotEquals(keys.conditionOf(Map.of("a", 1)), keys.conditionOf(Map.of("b", 1)));
        }

        @Test
        @DisplayName("params 便捷构造：成对写入，多余的单个参数被忽略（不抛异常）")
        void paramsHelper() {
            Map<String, Object> m = keys.params("page", 1, "size", 12, "dangling");
            assertEquals(2, m.size());
            assertEquals(1, m.get("page"));
            assertEquals(12, m.get("size"));
            assertEquals("page=1&size=12", keys.conditionOf(m));
        }
    }
}
