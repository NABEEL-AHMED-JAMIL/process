package process.security.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import process.model.pojo.RefreshToken;
import process.model.repository.AppUserRepository;
import process.model.repository.RefreshTokenRepository;
import process.model.payload.request.TokenRefreshRequest;
import process.model.payload.response.AppResponse;
import process.util.ProcessUtil;
import process.util.lookuputil.Status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * @author Nabeel Ahmed
 */
@Service
public class RefreshTokenService {

    @Value("${app.jwtRefreshExpirationMs}")
    private Long refreshTokenDurationMs;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private AppUserRepository appUserRepository;

    /**
     * findByToken use for get the refresh token from db
     * @param token
     * @return Optional<RefreshToken>
     * */
    public Optional<RefreshToken> findByToken(String token) {
        return this.refreshTokenRepository.findByTokenAndStatus(token, Status.ACTIVE.getLookupValue());
    }

    /**
     * createRefreshToken use to create refresh token into db
     * @param userId
     * @return RefreshToken
     * */
    public RefreshToken createRefreshToken(Long userId) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setAppUser(this.appUserRepository.findById(userId).get());
        refreshToken.setExpiryDate(Instant.now().plusMillis(this.refreshTokenDurationMs));
        refreshToken.setToken(UUID.randomUUID().toString());
        refreshToken.setStatus(Status.ACTIVE.getLookupValue());
        refreshToken = this.refreshTokenRepository.save(refreshToken);
        return refreshToken;
    }

    /**
     * verifyExpiration use to create refresh token into db
     * @param token
     * @return AppResponse
     * */
    public AppResponse verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().compareTo(Instant.now()) < 0) {
            token.setStatus(Status.DELETE.getLookupValue());
            this.refreshTokenRepository.save(token);
            return new AppResponse(ProcessUtil.ERROR, "Refresh token was expired." +
                "Please make a new signing request" ,token);
        }
        return new AppResponse(ProcessUtil.SUCCESS, "Token valid and can be use." ,token);
    }

    /**
     * deleteRefreshToken use to delete refresh token from db
     * @param tokenRefreshRequest
     * @return AppResponse
     * */
    public AppResponse deleteRefreshToken(TokenRefreshRequest tokenRefreshRequest) {
        Optional<RefreshToken> refreshToken = this.findByToken(tokenRefreshRequest.getRefreshToken());
        if (refreshToken.isPresent()) {
            refreshToken.get().setStatus(Status.DELETE.getLookupValue());
            this.refreshTokenRepository.save(refreshToken.get());
        }
        return new AppResponse(ProcessUtil.SUCCESS, "Token delete." ,tokenRefreshRequest);
    }

}