package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenServiceTests {

    private static final String CLIENT_ID = "client-a";
    private static final String RESOURCE = "http://localhost:8181";
    private static final int CONCURRENT_REFRESHES = 8;

    private final RefreshTokenService refreshTokenService = new RefreshTokenService();

    @Test
    void consumeAndRotate_concurrentRefreshes_onlyOneSucceeds() throws Exception {
        var token = refreshTokenService.issueToken("admin", "mcp:read", CLIENT_ID, RESOURCE);
        var start = new CountDownLatch(1);
        Callable<Boolean> refresh = () -> {
            start.await();
            return refreshTokenService.consumeAndRotate(token, CLIENT_ID, Optional.empty()).isPresent();
        };

        try (var executor = Executors.newFixedThreadPool(CONCURRENT_REFRESHES)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < CONCURRENT_REFRESHES; i++) {
                results.add(executor.submit(refresh));
            }
            start.countDown();

            int successes = 0;
            for (var result : results) {
                successes += result.get() ? 1 : 0;
            }
            assertThat(successes).isEqualTo(1);
        }
        assertThat(refreshTokenService.consumeAndRotate(token, CLIENT_ID, Optional.empty())).isEmpty();
    }
}
