package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class PointIntegrationTest {

    @Autowired
    MockMvc mockMvc; // HTTP 요청을 시뮬레이션

    @Autowired
    PointService pointService; // 실제 빈 사용

    @Autowired
    UserPointTable userPointTable;

    @Autowired
    PointHistoryTable pointHistoryTable;

    // 각 테스트마다 고유한 userId를 생성하기 위한 카운터
    // 1000 단위로 증가시켜서 테스트 간 충돌 방지
    private static final AtomicLong userIdCounter = new AtomicLong(1000);

    // 각 테스트에서 사용할 userId
    private long userId;

    @BeforeEach
    void setUp() {
        // 각 테스트마다 새로운 userId 할당 (1000씩 증가)
        userId = userIdCounter.getAndAdd(1000);
    }

    @Test
    @DisplayName("포인트 충전 후 조회하면 정확한 잔액이 반환된다")
    public void chargeAndGetPoint() throws Exception {
        // given
        long chargeAmount = 5000L;

        // when - 포인트 충전하기
        mockMvc.perform(patch("/point/{id}/charge", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(String.valueOf(chargeAmount)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.point").value(chargeAmount))
                .andExpect(jsonPath("$.updateMillis").exists());

        // then - 포인트 조회하기
        mockMvc.perform(get("/point/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.point").value(chargeAmount))
                .andExpect(jsonPath("$.updateMillis").exists());
    }

    @Test
    @DisplayName("포인트 충전 -> 사용 -> 조회 전체 플로우가 정상 동작한다")
    void chargeUseAndGetPoint() throws Exception {
        // given
        long chargeAmount = 10000L;
        long useAmount = 3000L;
        long expectedBalance = chargeAmount - useAmount;

        // when - 충전
        mockMvc.perform(patch("/point/{id}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(chargeAmount)))
                .andExpect(status().isOk());

        // when - 사용
        mockMvc.perform(patch("/point/{id}/use", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(useAmount)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.point").value(expectedBalance));

        // then - 최종 조회
        mockMvc.perform(get("/point/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.point").value(expectedBalance));
    }

    @Test
    @DisplayName("포인트 충전/사용 내역이 히스토리에 정확히 기록된다")
    void pointHistoryRecorded() throws Exception {
        // given
        long chargeAmount = 5000L;
        long useAmount = 2000L;

        // when - 충전 및 사용
        mockMvc.perform(patch("/point/{id}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(chargeAmount)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/point/{id}/use", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(useAmount)))
                .andExpect(status().isOk());

        // then - 히스토리 조회
        mockMvc.perform(get("/point/{id}/histories", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("CHARGE"))
                .andExpect(jsonPath("$[0].amount").value(chargeAmount))
                .andExpect(jsonPath("$[1].type").value("USE"))
                .andExpect(jsonPath("$[1].amount").value(useAmount));
    }

    @Test
    @DisplayName("잔액이 부족하면 포인트 사용이 실패한다")
    void usePointWithInsufficientBalance() throws Exception {
        // given
        long chargeAmount = 2000L;
        long useAmount = 5000L;

        // when - 충전
        mockMvc.perform(patch("/point/{id}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(chargeAmount)))
                .andExpect(status().isOk());

        // then - 잔액보다 큰 금액 사용 시도 -> 실패
        mockMvc.perform(patch("/point/{id}/use", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(useAmount)))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("동일 사용자에 대한 동시 충전 요청이 모두 정확히 처리된다")
    void concurrentChargeForSameUser() throws Exception {
        // given
        int threadCount = 10;
        long chargeAmount = 1000L;
        long expectedTotal = threadCount * chargeAmount;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // when - 동시에 10번 충전
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    pointService.chargePoint(userId, chargeAmount);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 예외 발생 시 무시
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // then - 최종 잔액 확인
        UserPoint finalPoint = userPointTable.selectById(userId);
        assertThat(finalPoint.point()).isEqualTo(expectedTotal);
        assertThat(successCount.get()).isEqualTo(threadCount);

        // 히스토리도 정확히 10개 기록되어야 함
        List<PointHistory> histories = pointHistoryTable.selectAllByUserId(userId);
        assertThat(histories).hasSize(threadCount);
    }

    @Test
    @DisplayName("유효하지 않은 충전 금액으로 요청하면 실패한다")
    void chargeWithInvalidAmount() throws Exception {
        // when & then - 음수 금액
        mockMvc.perform(patch("/point/{id}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("-1000"))
                .andExpect(status().is5xxServerError());

        // when & then - 100원 단위가 아닌 금액
        mockMvc.perform(patch("/point/{id}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("1550"))
                .andExpect(status().is5xxServerError());

        // when & then - 최대 한도 초과
        mockMvc.perform(patch("/point/{id}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("2000000"))
                .andExpect(status().is5xxServerError());
    }
}
