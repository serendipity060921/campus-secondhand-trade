package com.campus.trade.controller;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.result.Result;
import com.campus.trade.dto.UserUpdateDTO;
import com.campus.trade.service.ProfileService;
import com.campus.trade.vo.UserProfileVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人资料接口（v0.09）。
 *
 * <table border="1">
 *   <caption>接口清单（都映射在 /api/user 下，与 v0.04 的 UserController 互不冲突）</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/user/profile</td><td>查询自己的完整资料（含手机号/邮箱/真实姓名）</td></tr>
 *   <tr><td>PUT</td><td>/api/user/update</td><td>修改个人资料（昵称/头像/性别/学校/校区/手机号/邮箱）</td></tr>
 *   <tr><td>POST</td><td>/api/user/avatar</td><td>上传头像（multipart，字段名 file），上传成功即更新到当前用户</td></tr>
 * </table>
 *
 * <p>说明：v0.04 的 <code>/api/user/info</code> 返回对外展示用的 UserVO（不含隐私字段），
 * 这里的 <code>/api/user/profile</code> 返回含手机号/邮箱的 UserProfileVO，只在"查自己"时使用。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserProfileController {

    private final ProfileService profileService;

    /** 查询自己的完整资料 */
    @LoginRequired
    @GetMapping("/profile")
    public Result<UserProfileVO> profile() {
        return Result.success(profileService.getProfile(UserContext.getUserId()));
    }

    /** 修改个人资料：用户ID 取自 Token，前端不能改别人 */
    @LoginRequired
    @PutMapping("/update")
    public Result<UserProfileVO> update(@Valid @RequestBody UserUpdateDTO dto) {
        UserProfileVO vo = profileService.updateProfile(dto, UserContext.getUserId());
        return Result.success("资料已更新", vo);
    }

    /** 上传头像：上传成功后返回最新的完整资料（含新头像地址） */
    @LoginRequired
    @PostMapping("/avatar")
    public Result<UserProfileVO> uploadAvatar(@RequestParam("file") MultipartFile file) {
        UserProfileVO vo = profileService.uploadAvatar(file, UserContext.getUserId());
        return Result.success("头像已更新", vo);
    }
}
