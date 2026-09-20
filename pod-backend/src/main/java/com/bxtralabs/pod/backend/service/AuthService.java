package com.bxtralabs.pod.backend.service;

import com.bxtralabs.pod.backend.model.User;
import com.bxtralabs.pod.backend.repository.UserRepository;
import com.bxtralabs.pod.backend.security.JwtUtil;
import com.bxtralabs.pod.backend.security.TokenBlacklist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklist tokenBlacklist;

    public User signup(String name, String email, String password) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already registered: " + email);
        }
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        return userRepository.save(user);
    }

    public String login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("Invalid email or password");
        }
        return jwtUtil.generateToken(user.getId(), user.getEmail());
    }

    public void logout(String authorizationHeader) {
        tokenBlacklist.revoke(extractToken(authorizationHeader));
    }

    // Validates the bearer token and returns the authenticated user's id.
    public String requireUserId(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        if (tokenBlacklist.isRevoked(token)) {
            throw new IllegalArgumentException("Token has been logged out");
        }
        return jwtUtil.extractUserId(token);
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        }
        return authorizationHeader.substring("Bearer ".length());
    }
}
