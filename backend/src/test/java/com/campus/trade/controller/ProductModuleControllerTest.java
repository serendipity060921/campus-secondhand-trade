package com.campus.trade.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.GlobalExceptionHandler;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.config.FileUploadProperties;
import com.campus.trade.dto.ProductPublishDTO;
import com.campus.trade.service.FileService;
import com.campus.trade.service.ProductModuleService;
import com.campus.trade.vo.FileUploadVO;
import com.campus.trade.vo.ProductDetailVO;
import com.campus.trade.vo.ProductVO;

/**
 * 商品模块接口层验证（v0.18 / 收尾⑨-3）
 *
 * <p>服务层已经测透了，这一层测的是"HTTP 到业务的接线"—— 这类问题单测服务层永远发现不了：</p>
 *
 * <ul>
 *   <li><b>路由与绑定</b>：路径、方法、查询参数绑定、路径变量是否对上；</li>
 *   <li><b>参数校验</b>：{@code @Valid} 是否真的生效、错误提示是否可读；</li>
 *   <li><b>统一响应结构</b>：成功与失败都必须返回 {@code code/message/data/timestamp}
 *       （前端拦截器只认这个结构）；</li>
 *   <li>★ <b>未登录访问受保护接口</b>：服务层不负责鉴权，这里要确认它会以 401 结束而不是
 *       带着 null 用户继续往下跑。</li>
 * </ul>
 *
 * <p>用 standalone MockMvc（不起 Spring 容器）+ 真实的 {@link GlobalExceptionHandler}，
 * 因此断言的是**真实会发给前端的报文**。鉴权拦截器单独在 {@code LoginInterceptorTest} 里测，
 * 这里通过手工写入 {@code UserContext} 模拟"已登录"。</p>
 */
class ProductModuleControllerTest {

    private ProductModuleService productModuleService;
    private FileService fileService;
    private FileUploadProperties fileUploadProperties;
    private MockMvc mvc;

    private static final Long USER_ID = 11L;

    @BeforeEach
    void setUp() {
        productModuleService = mock(ProductModuleService.class);
        fileService = mock(FileService.class);
        fileUploadProperties = mock(FileUploadProperties.class);
        when(fileUploadProperties.getMaxCount()).thenReturn(5);

        ProductModuleController controller =
                new ProductModuleController(productModuleService, fileService, fileUploadProperties);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())   // 让错误响应也是统一结构
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new LoginUser(USER_ID, "stu_test01", 0));
    }

    private String publishJson() {
        return """
                {"title":"九成新《数据结构》教材","description":"东校区自取","price":25.5,
                 "categoryId":3,"conditionLevel":3,"campus":"东校区"}
                """;
    }

    @Nested
    @DisplayName("一、发布商品")
    class Publish {

        @Test
        @DisplayName("★ 已登录发布成功：返回统一结构，且卖家取自上下文（前端传不了 sellerId）")
        void publishSuccess() throws Exception {
            login();
            ProductDetailVO vo = new ProductDetailVO();
            vo.setId(88L);
            vo.setSellerId(USER_ID);
            when(productModuleService.publish(any(ProductPublishDTO.class), eq(USER_ID))).thenReturn(vo);

            mvc.perform(post("/api/product/publish").contentType(MediaType.APPLICATION_JSON)
                            .content(publishJson()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("发布成功"))
                    .andExpect(jsonPath("$.data.id").value(88))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productModuleService).publish(any(ProductPublishDTO.class), eq(USER_ID));
        }

        @Test
        @DisplayName("★ 参数校验生效：缺标题与价格 → 400，且提示可读（不落服务层）")
        void publishValidationFails() throws Exception {
            login();

            mvc.perform(post("/api/product/publish").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").exists());

            verify(productModuleService, never()).publish(any(), anyLong());
        }

        @Test
        @DisplayName("★ 未登录发布 → 401（不会带着空用户继续执行）")
        void publishWithoutLogin() throws Exception {
            mvc.perform(post("/api/product/publish").contentType(MediaType.APPLICATION_JSON)
                            .content(publishJson()))
                    .andExpect(jsonPath("$.code").value(401));

            verify(productModuleService, never()).publish(any(), anyLong());
        }

        @Test
        @DisplayName("请求体不是合法 JSON → 400（而不是 500）")
        void publishMalformedJson() throws Exception {
            login();

            mvc.perform(post("/api/product/publish").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":"))
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    @Nested
    @DisplayName("二、列表与详情（公开接口）")
    class Query {

        @Test
        @DisplayName("列表：公开可访问，返回分页结构")
        void list() throws Exception {
            PageResult<ProductVO> page = new PageResult<>(2L, 1L, 1L, 12L, List.of(new ProductVO()));
            when(productModuleService.pageList(any())).thenReturn(page);

            mvc.perform(get("/api/product/list").param("page", "1").param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.total").value(2))
                    .andExpect(jsonPath("$.data.records.length()").value(1));
        }

        @Test
        @DisplayName("详情：公开可访问，路径变量正确传入")
        void detail() throws Exception {
            ProductDetailVO vo = new ProductDetailVO();
            vo.setId(88L);
            vo.setTitle("九成新《数据结构》教材");
            when(productModuleService.detail(88L)).thenReturn(vo);

            mvc.perform(get("/api/product/88"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.title").value("九成新《数据结构》教材"));

            verify(productModuleService).detail(88L);
        }

        @Test
        @DisplayName("我的商品：需要登录，且只查自己的")
        void mine() throws Exception {
            login();
            PageResult<ProductVO> page = new PageResult<>(0L, 0L, 1L, 12L, List.of());
            when(productModuleService.pageMine(any(), eq(USER_ID))).thenReturn(page);

            mvc.perform(get("/api/product/mine")).andExpect(jsonPath("$.code").value(200));

            verify(productModuleService).pageMine(any(), eq(USER_ID));
        }

        @Test
        @DisplayName("我的商品：未登录 → 401")
        void mineWithoutLogin() throws Exception {
            mvc.perform(get("/api/product/mine")).andExpect(jsonPath("$.code").value(401));
            verify(productModuleService, never()).pageMine(any(), anyLong());
        }
    }

    @Nested
    @DisplayName("三、上下架")
    class Status {

        @Test
        @DisplayName("上架：提示「上架成功」，并把 dto 拆成参数传给服务层")
        void onSale() throws Exception {
            login();

            mvc.perform(put("/api/product/status").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":88,\"status\":1}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("上架成功"));

            verify(productModuleService).changeStatus(88L, 1, USER_ID);
        }

        @Test
        @DisplayName("下架：提示「下架成功」")
        void offShelf() throws Exception {
            login();

            mvc.perform(put("/api/product/status").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":88,\"status\":3}"))
                    .andExpect(jsonPath("$.message").value("下架成功"));

            verify(productModuleService).changeStatus(88L, 3, USER_ID);
        }

        @Test
        @DisplayName("★ 服务层抛业务异常（越权 3002）→ 统一结构返回该业务码，而不是 500")
        void businessExceptionKeepsCode() throws Exception {
            login();
            org.mockito.Mockito.doThrow(ProductException.noPermission())
                    .when(productModuleService).changeStatus(anyLong(), any(), anyLong());

            mvc.perform(put("/api/product/status").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":88,\"status\":3}"))
                    .andExpect(jsonPath("$.code").value(ProductException.noPermission().getCode()));
        }
    }

    @Nested
    @DisplayName("四、图片上传")
    class Upload {

        @Test
        @DisplayName("★ 没有文件 → 报「文件为空」，不进入存储流程")
        void emptyFiles() throws Exception {
            login();

            mvc.perform(multipart("/api/product/upload"))
                    .andExpect(jsonPath("$.code").value(ProductException.fileEmpty().getCode()));

            verify(fileService, never()).store(any());
        }

        @Test
        @DisplayName("超过数量上限 → 报「图片数量超限」")
        void tooManyFiles() throws Exception {
            login();
            when(fileUploadProperties.getMaxCount()).thenReturn(1);
            MockMultipartFile a = new MockMultipartFile("files", "a.png", "image/png", "a".getBytes());
            MockMultipartFile b = new MockMultipartFile("files", "b.png", "image/png", "b".getBytes());

            mvc.perform(multipart("/api/product/upload").file(a).file(b))
                    .andExpect(jsonPath("$.code").value(ProductException.imageCountExceeded().getCode()));
        }

        @Test
        @DisplayName("上传成功：返回图片地址；带 productId 时写入商品图片表")
        void uploadSuccess() throws Exception {
            login();
            // FileUploadVO 用 @Builder 生成（没有无参构造器），因此这里用 builder 构造
            FileUploadVO vo = FileUploadVO.builder().url("/upload/a.png").originalName("a.png").build();
            when(fileService.store(any())).thenReturn(vo);

            MockMultipartFile a = new MockMultipartFile("files", "a.png", "image/png", "a".getBytes());
            mvc.perform(multipart("/api/product/upload").file(a).param("productId", "88"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[0].url").value("/upload/a.png"))
                    .andExpect(jsonPath("$.message").value("上传成功"));

            verify(productModuleService).addImages(eq(88L), eq(List.of("/upload/a.png")), eq(USER_ID));
        }
    }

    @Nested
    @DisplayName("五、★ 统一响应契约")
    class ResponseContract {

        @Test
        @DisplayName("所有成功响应都带 code / message / timestamp（前端拦截器依赖）")
        void successShape() throws Exception {
            PageResult<ProductVO> page = new PageResult<>(0L, 0L, 1L, 12L, List.of());
            when(productModuleService.pageList(any())).thenReturn(page);

            mvc.perform(get("/api/product/list"))
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("★ 业务异常也走同一结构（HTTP 200 + body.code=3001），与线上行为一致")
        void errorShape() throws Exception {
            when(productModuleService.detail(anyLong())).thenThrow(ProductException.notFound());

            mvc.perform(get("/api/product/999999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ProductException.notFound().getCode()))
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("不支持的请求方法 → 405 业务码，而不是让前端拿到 500")
        void methodNotAllowed() throws Exception {
            mvc.perform(put("/api/product/list"))
                    .andExpect(jsonPath("$.code").value(405));
        }
    }
}
