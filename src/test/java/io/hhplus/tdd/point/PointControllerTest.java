package io.hhplus.tdd.point;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PointController.class)
public class PointControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PointService pointService;

    @Nested
    @DisplayName("GET /point/{id}")
    class GetPoint {
        @Test
        @DisplayName("사용자 포인트를 조회한다.")
        void getPoint() throws Exception {
            // given
            UserPoint userPoint = new UserPoint(1L, 1000L, 0L);
            when(pointService.getUserPoint(1L)).thenReturn(userPoint);

            // when & then
            mockMvc.perform(get("/point/{id}", 1L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.point").value(1000))
                    .andExpect(jsonPath("$.updateMillis").exists());
        }
    }

    @Nested
    @DisplayName("GET /point/{id}/histories")
    class GetPointHistories {
        @Test
        @DisplayName("사용자 포인트 내역을 조회합니다.")
        void getPointHistories() throws Exception {
            // given
            long userId = 1L;
            PointHistory pointHistory = new PointHistory(1L, userId, 1000L, TransactionType.CHARGE, System.currentTimeMillis());
            when(pointService.getAllPointHistory(userId)).thenReturn(List.of(pointHistory));

            // when & then
            mockMvc.perform(get("/point/{id}/histories", userId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].userId").value(userId))
                    .andExpect(jsonPath("$[0].amount").value(1000))
                    .andExpect(jsonPath("$[0].type").value("CHARGE"))
                    .andExpect(jsonPath("$[0].updateMillis").exists());
        }

        @Test
        @DisplayName("포인트 내역이 없는 경우 빈 배열을 반환한다.")
        void getEmptyPointHistories() throws Exception {
            // given
            long userId = 1L;
            when(pointService.getAllPointHistory(userId)).thenReturn(List.of());

            // when & then
            mockMvc.perform(get("/point/{id}/histories", userId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    @Nested
    @DisplayName("PATCH /point/{id}/charge")
    class ChargePoint {
        @Test
        @DisplayName("사용자 포인트를 충전한다.")
        void chargePoint() throws Exception {
            // given
            long amount = 10000L;
            UserPoint userPoint = new UserPoint(1L, 15000L, System.currentTimeMillis());
            when(pointService.chargePoint(1L, amount)).thenReturn(userPoint);

            // when & then
            mockMvc.perform(patch("/point/{id}/charge", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(amount)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.point").value(15000))
                    .andExpect(jsonPath("$.updateMillis").exists());
        }

        @Test
        @DisplayName("서비스에서 예외 발생 시 500 에러를 반환한다.")
        void chargePoint_serviceException() throws Exception {
            // given
            when(pointService.chargePoint(anyLong(), anyLong()))
                    .thenThrow(new IllegalArgumentException("충전 중 어떤 예외 발생!"));

            // when & then
            mockMvc.perform(patch("/point/{id}/charge", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("10000"))
                    .andExpect(status().is5xxServerError())
                    .andExpect(jsonPath("$.code").value("500"))
                    .andExpect(jsonPath("$.message").value("에러가 발생했습니다."));
        }
    }

    @Nested
    @DisplayName("PATCH /point/{id}/use")
    class usePoint {
        @Test
        @DisplayName("사용자의 포인트를 사용한다.")
        public void useUserPoint() throws Exception {
            // given
            long amount = 1000L;
            UserPoint userPoint = new UserPoint(1L, 500L, System.currentTimeMillis());
            when(pointService.usePoint(1L, amount)).thenReturn(userPoint);

            // when & then
            mockMvc.perform(patch("/point/{id}/use", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(amount)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.point").value(500))
                    .andExpect(jsonPath("$.updateMillis").exists());
        }

        @Test
        @DisplayName("서비스에서 예외 발생 시 500 에러를 반환한다.")
        void usePoint_serviceException() throws Exception {
            // given
            when(pointService.usePoint(anyLong(), anyLong()))
                    .thenThrow(new IllegalArgumentException("포인트가 부족합니다."));

            // when & then
            mockMvc.perform(patch("/point/{id}/use", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("10000"))
                    .andExpect(status().is5xxServerError())
                    .andExpect(jsonPath("$.code").value("500"))
                    .andExpect(jsonPath("$.message").value("에러가 발생했습니다."));
        }
    }
}
