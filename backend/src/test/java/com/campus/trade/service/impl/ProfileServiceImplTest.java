package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.trade.common.exception.ProfileException;
import com.campus.trade.common.exception.UserException;
import com.campus.trade.dto.UserUpdateDTO;
import com.campus.trade.entity.User;
import com.campus.trade.service.FileService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.FileUploadVO;
import com.campus.trade.vo.UserProfileVO;

/**
 * 个人资料服务实现验证（v0.18 / 第3批-⑫）
 *
 * <p>资料修改里有两个容易出事的点：</p>
 * <ul>
 *   <li>★ <b>手机号是唯一索引</b>：改之前必须校验是否被别人占用；而且"没改过"就不该白查一次库，
 *       清空手机号时要写 NULL 而不是空串（空串会占用唯一索引，导致第二个用户想留空就报重复）；</li>
 *   <li>★ <b>空白串一律转 NULL</b>：前端把输入框清空时传的是 {@code ""}，直接写库会让
 *       "未填写"变成"填了个空字符串"，既影响展示也会污染唯一索引。</li>
 * </ul>
 */
class ProfileServiceImplTest {

    private UserService userService;
    private FileService fileService;
    private ProfileServiceImpl service;

    private static final Long UID = 11L;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), User.class);
    }

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        fileService = mock(FileService.class);
        service = new ProfileServiceImpl(userService, fileService);
    }

    private User user(String phone) {
        User u = new User();
        u.setId(UID);
        u.setUsername("stu_demo");
        u.setNickname("旧昵称");
        u.setPhone(phone);
        u.setAvatar("/upload/old.png");
        u.setCreditScore(100);
        return u;
    }

    private UserUpdateDTO dto(String nickname, String phone) {
        UserUpdateDTO d = new UserUpdateDTO();
        d.setNickname(nickname);
        d.setPhone(phone);
        return d;
    }

    @Nested
    @DisplayName("一、查询资料")
    class Get {

        @Test
        @DisplayName("用户不存在 → 报错")
        void notFound() {
            when(userService.getById(UID)).thenReturn(null);
            assertThrows(UserException.class, () -> service.getProfile(UID));
        }

        @Test
        @DisplayName("正常返回资料")
        void ok() {
            when(userService.getById(UID)).thenReturn(user("13800000000"));

            UserProfileVO vo = service.getProfile(UID);

            assertEquals("stu_demo", vo.getUsername());
            assertEquals("13800000000", vo.getPhone());
        }
    }

    @Nested
    @DisplayName("二、★ 修改资料：手机号唯一与空值处理")
    class Update {

        @Test
        @DisplayName("用户不存在 → 报错")
        void notFound() {
            when(userService.getById(UID)).thenReturn(null);
            assertThrows(UserException.class, () -> service.updateProfile(dto("x", null), UID));
        }

        @Test
        @DisplayName("★ 手机号被别人占用 → 拒绝")
        void phoneTaken() {
            when(userService.getById(UID)).thenReturn(user("13800000000"));
            when(userService.count(any())).thenReturn(1L);

            ProfileException e = assertThrows(ProfileException.class,
                    () -> service.updateProfile(dto("新昵称", "13900000000"), UID));
            assertEquals(ProfileException.phoneExists().getCode(), e.getCode());
            verify(userService, never()).updateById(any());
        }

        @Test
        @DisplayName("★ 手机号没变 → 不查库（省一次查询）、也不报重复")
        void unchangedPhoneSkipsCheck() {
            when(userService.getById(UID)).thenReturn(user("13800000000"));

            service.updateProfile(dto("新昵称", "13800000000"), UID);

            verify(userService, never()).count(any());
            verify(userService).updateById(any());
        }

        @Test
        @DisplayName("★ 清空手机号 → 写 NULL 而不是空串（空串会占用唯一索引）")
        void emptyPhoneBecomesNull() {
            when(userService.getById(UID)).thenReturn(user("13800000000"));

            service.updateProfile(dto("新昵称", "   "), UID);

            verify(userService).updateById(org.mockito.ArgumentMatchers.argThat(u ->
                    u != null && u.getPhone() == null));
        }

        @Test
        @DisplayName("★ 空白串一律转 NULL（前端清空输入框传的是空串）")
        void blankStringsBecomeNull() {
            when(userService.getById(UID)).thenReturn(user(null));

            UserUpdateDTO d = dto("   ", "");
            d.setCampus("  ");
            d.setEmail("");
            d.setRealName("  ");
            service.updateProfile(d, UID);

            verify(userService).updateById(org.mockito.ArgumentMatchers.argThat(u ->
                    u != null && u.getNickname() == null && u.getCampus() == null
                            && u.getEmail() == null && u.getRealName() == null));
        }

        @Test
        @DisplayName("字段两端空格会被去掉")
        void fieldsTrimmed() {
            when(userService.getById(UID)).thenReturn(user(null));

            service.updateProfile(dto("  新昵称  ", null), UID);

            verify(userService).updateById(org.mockito.ArgumentMatchers.argThat(u ->
                    u != null && "新昵称".equals(u.getNickname())));
        }

        @Test
        @DisplayName("只更新资料字段（不整行覆盖，密码等字段保持为 null 不动）")
        void partialUpdate() {
            when(userService.getById(UID)).thenReturn(user(null));

            service.updateProfile(dto("新昵称", null), UID);

            verify(userService).updateById(org.mockito.ArgumentMatchers.argThat(u ->
                    u != null && Long.valueOf(UID).equals(u.getId()) && u.getPassword() == null));
        }
    }

    @Nested
    @DisplayName("三、上传头像")
    class Avatar {

        @Test
        @DisplayName("用户不存在 → 报错，且不写文件")
        void notFound() {
            when(userService.getById(UID)).thenReturn(null);

            MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
            assertThrows(UserException.class, () -> service.uploadAvatar(file, UID));
            verify(fileService, never()).store(any());
        }

        @Test
        @DisplayName("★ 上传成功 → 头像地址写成文件服务返回的 URL")
        void ok() {
            when(userService.getById(UID)).thenReturn(user("13800000000"));
            when(fileService.store(any())).thenReturn(FileUploadVO.builder()
                    .url("/upload/2026/10/newuuid.png").build());

            MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
            assertNotNull(service.uploadAvatar(file, UID));

            verify(fileService).store(any());
            verify(userService).updateById(org.mockito.ArgumentMatchers.argThat(u ->
                    u != null && "/upload/2026/10/newuuid.png".equals(u.getAvatar())));
        }
    }
}
