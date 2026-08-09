package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.dto.ChangePasswordRequest;
import caigou.caigoupetservice.dto.ForgotPasswordRequest;
import caigou.caigoupetservice.dto.LoginRequest;
import caigou.caigoupetservice.dto.LoginResult;
import caigou.caigoupetservice.dto.QrConfirmRequest;
import caigou.caigoupetservice.dto.QrInitResponse;
import caigou.caigoupetservice.dto.RegisterRequest;
import caigou.caigoupetservice.dto.ResetPasswordRequest;
import caigou.caigoupetservice.service.AuthService;
import caigou.caigoupetservice.service.QrLoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证控制器:仅做参数接收与结果返回,业务逻辑全部在 service 层
 * 路径/请求/响应与 Express 端 /api/auth 保持一致
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final QrLoginService qrLoginService;

    public AuthController(AuthService authService, QrLoginService qrLoginService) {
        this.authService = authService;
        this.qrLoginService = qrLoginService;
    }

    /** 注册:成功返回 201 + token(注册即登录) */
    @PostMapping("/register")
    public ResponseEntity<LoginResult> register(@RequestBody RegisterRequest req) {
        return ResponseEntity.status(201).body(authService.register(req));
    }

    /** 登录 */
    @PostMapping("/login")
    public LoginResult login(@RequestBody LoginRequest req) {
        return authService.login(req);
    }

    /** 当前用户:认证信息已由拦截器写入 request attribute */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return Map.of("user", request.getAttribute("currentUser"));
    }

    /** 修改密码 */
    @PostMapping("/change-password")
    public Map<String, String> changePassword(@RequestBody ChangePasswordRequest req, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        authService.changePassword(userId, req);
        return Map.of("message", "密码修改成功");
    }

    /** 找回密码(模拟流程,只打印重置链接到日志) */
    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@RequestBody ForgotPasswordRequest req) {
        authService.forgotPassword(req);
        return Map.of("message", "密码重置链接已发送到您的邮箱（模拟）");
    }

    /** 重置密码 */
    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req);
        return Map.of("message", "密码重置成功，请使用新密码登录");
    }

    /** 扫码登录初始化(GET/POST 均支持,复刻 Express 双注册) */
    @RequestMapping(value = "/qrcode/init", method = {RequestMethod.GET, RequestMethod.POST})
    public QrInitResponse qrcodeInit() {
        return qrLoginService.init();
    }

    /** 扫码状态轮询 */
    @GetMapping("/qrcode/poll")
    public Map<String, Object> qrcodePoll(@RequestParam(value = "session", required = false) String session) {
        return qrLoginService.poll(session);
    }

    /** 手机端确认扫码登录 */
    @PostMapping("/qrcode/confirm")
    public Map<String, String> qrcodeConfirm(@RequestBody QrConfirmRequest req) {
        qrLoginService.confirm(req);
        return Map.of("message", "确认成功，请在电脑端等待登录");
    }
}
