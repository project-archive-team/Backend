package com.projectarchive.backend.repo;

import com.projectarchive.backend.domain.OauthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OauthTokenRepository extends JpaRepository<OauthToken, Long> {
    Optional<OauthToken> findByUserIdAndProvider(Long userId, OauthToken.Provider provider);
}
