package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.ChangePasswordRequest;
import caigou.caigoupetservice.dto.ForgotPasswordRequest;
import caigou.caigoupetservice.dto.LoginRequest;
import caigou.caigoupetservice.dto.LoginResult;
import caigou.caigoupetservice.dto.RegisterRequest;
import caigou.caigoupetservice.dto.ResetPasswordRequest;
import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 认证业务:注册/登录/改密/找回/重置密码
 * 所有校验分支抛出 ApiException,由全局异常处理器统一返回 {error:"..."}
 * 分层约定:本类承载全部业务逻辑,controller 只做参数透传与返回
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * 注册新用户:参数校验 → 用户名查重 → bcrypt 哈希 → 入库 → 签发 token
     * 成功返回 201 与 token(注册即登录,与 Express 行为一致)
     */
    public LoginResult register(RegisterRequest req) {
        String username = req.username();
        String password = req.password();
        if (isBlank(username) || isBlank(password)) {
            throw new ApiException(400, "用户名和密码不能为空");
        }
        if (username.length() < 2 || username.length() > 50) {
            throw new ApiException(400, "用户名长度需在 2-50 字之间");
        }
        if (password.length() < 6) {
            throw new ApiException(400, "密码长度不能少于 6 位");
        }
        // 用户名查重,冲突返回 409
        if (userMapper.findByUsername(username) != null) {
            throw new ApiException(409, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        // 昵称缺省时回退为用户名,等价 Express 的 nickname || username
        user.setNickname(isBlank(req.nickname()) ? username : req.nickname());
        user.setEmail(req.email());
        userMapper.insert(user);
        return new LoginResult(jwtService.generateToken(user.getId()), UserView.from(user));
    }

    /**
     * 登录:校验用户名与密码,成功签发 token
     */
    public LoginResult login(LoginRequest req) {
        if (isBlank(req.username()) || isBlank(req.password())) {
            throw new ApiException(400, "用户名和密码不能为空");
        }
        User user = userMapper.findByUsername(req.username());
        // 用户不存在与密码错误统一提示,避免暴露账号存在性
        if (user == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new ApiException(401, "用户名或密码错误");
        }
        return new LoginResult(jwtService.generateToken(user.getId()), UserView.from(user));
    }

    /**
     * 修改密码:校验旧密码后更新为新密码
     */
    public void changePassword(Long userId, ChangePasswordRequest req) {
        if (isBlank(req.oldPassword()) || isBlank(req.newPassword())) {
            throw new ApiException(400, "旧密码和新密码不能为空");
        }
        if (req.newPassword().length() < 6) {
            throw new ApiException(400, "新密码长度不能少于 6 位");
        }
        User user = userMapper.findById(userId);
        if (!passwordEncoder.matches(req.oldPassword(), user.getPassword())) {
            throw new ApiException(401, "旧密码错误");
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(req.newPassword()));
    }

    /**
     * 找回密码:按用户名或邮箱定位用户,生成重置令牌存库(模拟流程,不发邮件)
     * 重置链接打印到日志,供开发期取用
     */
    public void forgotPassword(ForgotPasswordRequest req) {
        String account = sanitize(req.account());
        if (account.isEmpty()) {
            throw new ApiException(400, "请输入用户名或邮箱");
        }
        User user = userMapper.findByUsernameOrEmail(account);
        if (user == null) {
            throw new ApiException(404, "该账号不存在");
        }
        String resetToken = randomHex(32);
        // 令牌仅以 sha256 摘要存库,库中不保留明文令牌
        String hashed = sha256Hex(resetToken);
        userMapper.saveResetToken(user.getId(), hashed, System.currentTimeMillis() + 30 * 60 * 1000L);
        log.info("Password reset link for {}: /forgot-password.html?token={}&uid={}",
                account, resetToken, user.getId());
    }

    /**
     * 重置密码:校验令牌与过期时间后更新密码并清空令牌字段
     */
    public void resetPassword(ResetPasswordRequest req) {
        if (req.token() == null || req.uid() == null || req.newPassword() == null) {
            throw new ApiException(400, "参数不完整");
        }
        if (req.newPassword().length() < 6) {
            throw new ApiException(400, "新密码长度不能少于 6 位");
        }
        User user = userMapper.findById(req.uid());
        if (user == null) {
            throw new ApiException(404, "用户不存在");
        }
        if (user.getResetToken() == null || user.getResetTokenExpires() == null) {
            throw new ApiException(400, "未请求密码重置");
        }
        if (System.currentTimeMillis() > user.getResetTokenExpires()) {
            throw new ApiException(400, "重置链接已过期");
        }
        String hashed = sha256Hex(req.token());
        if (!hashed.equals(user.getResetToken())) {
            throw new ApiException(400, "重置令牌无效");
        }
        userMapper.clearResetToken(user.getId(), passwordEncoder.encode(req.newPassword()));
    }

    /** 判断字符串是否空白 */
    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 输入清洗:去首尾空白与尖括号(复刻 Express sanitize) */
    private String sanitize(String str) {
        if (str == null) {
            return "";
        }
        return str.trim().replaceAll("[<>]", "");
    }

    /** 生成指定字节数的十六进制随机串(等价 crypto.randomBytes(n).toString('hex')) */
    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        SECURE_RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** 计算字符串的 SHA-256 十六进制摘要(找回密码令牌哈希,与 Express 端 sha256 一致) */
    private String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new ApiException(500, "哈希计算失败");
        }
    }
}
