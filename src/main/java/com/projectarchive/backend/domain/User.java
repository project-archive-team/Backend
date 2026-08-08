package com.projectarchive.backend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /** OAuth 전용 계정은 null. */
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    private String jobTitle;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(nullable = false)
    private String theme = "light";

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_tech_stack", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "tech")
    private List<String> techStack = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private User(String email, String passwordHash, String name) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
    }

    public static User withPassword(String email, String passwordHash, String name) {
        return new User(email, passwordHash, name);
    }

    public static User oauthOnly(String email, String name) {
        return new User(email, null, name);
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** null인 필드는 건드리지 않는다 — 부분 수정(PATCH)으로 쓴다. */
    public void updateProfile(String name, String jobTitle, String bio, String theme, List<String> techStack) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (jobTitle != null) {
            this.jobTitle = jobTitle;
        }
        if (bio != null) {
            this.bio = bio;
        }
        if (theme != null && !theme.isBlank()) {
            this.theme = theme;
        }
        if (techStack != null) {
            this.techStack.clear();
            this.techStack.addAll(techStack);
        }
    }
}
