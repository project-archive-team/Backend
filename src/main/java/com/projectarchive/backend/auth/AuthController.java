package com.projectarchive.backend.auth;

import com.projectarchive.backend.domain.User;
import com.projectarchive.backend.repo.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public record SignupRequest(@Email @NotBlank String email,
                                @NotBlank @Size(min = 8, max = 72) String password,
                                @NotBlank String name) {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {}

    public record MeResponse(Long id, String email, String name) {}

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse signup(@Valid @RequestBody SignupRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        User user = users.save(User.withPassword(req.email(), passwordEncoder.encode(req.password()), req.name()));
        return tokensFor(user);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        User user = users.findByEmail(req.email())
                .filter(u -> u.getPasswordHash() != null)
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                // 계정 존재 여부를 흘리지 않도록 두 경우 모두 같은 응답.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials"));
        return tokensFor(user);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        Long userId;
        try {
            userId = jwtService.parseRefresh(req.refreshToken());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token"));
        return tokensFor(user);
    }

    @GetMapping("/me")
    public MeResponse me(@CurrentUserId Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new MeResponse(user.getId(), user.getEmail(), user.getName());
    }

    private TokenResponse tokensFor(User user) {
        return new TokenResponse(jwtService.issueAccess(user.getId()),
                jwtService.issueRefresh(user.getId()),
                jwtService.accessTtlSeconds());
    }
}
