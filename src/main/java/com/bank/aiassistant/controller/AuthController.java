package com.bank.aiassistant.controller;

import com.bank.aiassistant.model.dto.auth.AuthResponse;
import com.bank.aiassistant.model.dto.auth.LoginRequest;
import com.bank.aiassistant.model.dto.auth.RegisterRequest;
import com.bank.aiassistant.model.entity.User;
import com.bank.aiassistant.repository.UserRepository;
import com.bank.aiassistant.security.jwt.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        String accessToken  = tokenProvider.generateAccessToken(auth);
        String refreshToken = tokenProvider.generateRefreshToken(request.email());

        User user = userRepository.findByEmail(request.email()).orElseThrow();
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();

        return ResponseEntity.ok(AuthResponse.of(accessToken, refreshToken, 3600L * 1000,
                user.getId(), user.getEmail(), user.getFullName(), roles));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .firstName(request.firstName())
                .lastName(request.lastName())
                .roles(Set.of(com.bank.aiassistant.model.entity.Role.USER))
                .build();
        userRepository.save(user);

        // Auto-login after registration
        return login(new LoginRequest(request.email(), request.password()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            tokenProvider.revokeToken(bearerToken.substring(7));
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestParam String refreshToken) {
        if (!tokenProvider.validateToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String username = tokenProvider.getUsernameFromToken(refreshToken);
        User user = userRepository.findByEmail(username).orElseThrow();

        Authentication auth = new UsernamePasswordAuthenticationToken(username, null,
                user.getRoles().stream()
                        .map(r -> (GrantedAuthority) () -> "ROLE_" + r.name()).toList());

        String newAccessToken = tokenProvider.generateAccessToken(auth);
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();

        return ResponseEntity.ok(AuthResponse.of(newAccessToken, refreshToken, 3600L * 1000,
                user.getId(), user.getEmail(), user.getFullName(), roles));
    }
}
