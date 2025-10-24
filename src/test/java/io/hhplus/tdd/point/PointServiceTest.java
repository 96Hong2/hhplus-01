package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PointServiceTest {

    @Mock
    UserPointTable userPointTable;

    @Mock
    PointHistoryTable pointHistoryTable;

    @InjectMocks
    PointService pointService;

    // 1. 포인트 조회 테스트
    @Test
    @DisplayName("유저 ID로 포인트를 조회한다.")
    void getExistUserPoint() {
        // given
        // 포인트를 가지고 있는 유저 ID
        long userId = 1L;
        long pointAmount = 5000L;
        long updateMillis = System.currentTimeMillis();
        UserPoint expectedUserPoint = new UserPoint(userId, pointAmount, updateMillis);

        when(userPointTable.selectById(userId)).thenReturn(expectedUserPoint);

        // when
        // 해당 유저의 포인트를 조회
        UserPoint actualUserPoint = pointService.getUserPoint(userId);

        // then
        // 해당 유저가 가지고 있는 포인트가 조회된다.
        assertThat(actualUserPoint).isNotNull();
        assertThat(actualUserPoint.id()).isEqualTo(userId);
        assertThat(actualUserPoint.point()).isEqualTo(pointAmount);
        assertThat(actualUserPoint.updateMillis()).isEqualTo(updateMillis);
    }

    @Test
    @DisplayName("존재하지 않는 유저 ID로 조회 시 0원을 반환한다.")
    void getNonExistUserPoint() {
        // given
        // 존재하지 않는 유저 ID
        long userId = 999L;
        when(userPointTable.selectById(userId)).thenReturn(null);

        // when
        // 해당 유저의 포인트를 조회
        UserPoint userPoint = pointService.getUserPoint(userId);

        // then
        // 0원 반환
        assertThat(userPoint).isNotNull();
        assertThat(userPoint.point()).isEqualTo(0L);
    }

    // 2. 포인트 충전 테스트
    @Test
    @DisplayName("유저의 포인트를 충전한다.")
    void chargeUserPoint() {
        // given
        // 존재하는 유저 ID와 충전 금액
        long userId = 1L;
        long initialPoint = 100L;
        long chargeAmount = 1000L;

        UserPoint initialUserPoint = new UserPoint(userId, initialPoint, 0);
        UserPoint expectedUserPoint = new UserPoint(userId, initialPoint + chargeAmount, 0);
        when(userPointTable.selectById(userId)).thenReturn(initialUserPoint);
        when(userPointTable.insertOrUpdate(userId, initialPoint + chargeAmount)).thenReturn(expectedUserPoint);

        // when
        // 0 이상의 포인트 충전
        UserPoint resultUserPoint = pointService.chargePoint(userId, chargeAmount);

        // then
        // 기존 포인트 + 충전 금액 반환
        assertThat(resultUserPoint).isEqualTo(expectedUserPoint);
        verify(userPointTable).selectById(userId);
        verify(userPointTable).insertOrUpdate(userId, initialPoint + chargeAmount);
        verify(pointHistoryTable).insert(eq(userId), eq(chargeAmount), eq(TransactionType.CHARGE), anyLong());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1000L})
    @DisplayName("0원 이하의 금액은 충전할 수 없다.")
    void chargeMinusUserPoint(long amount) {
        // given
        // 유저 ID, 0원 또는 음수 금액
        long userId = 1L;

        // when & then 
        // 포인트 충전 시도 -> IllegalArgumentException 발생
        assertThatThrownBy(() -> pointService.chargePoint(userId, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("0원 이하의 금액은 충전할 수 없습니다.");
    }

    // 추가 요구사항1 - 심화 과제
    @ParameterizedTest
    @ValueSource(longs = {99L, 555L, 100001L})
    @DisplayName("포인트 충전은 100원 단위로만 가능하다.")
    void onlyMultiplesOf100Allowed(long amount) {
        // given
        // 유저 Id, 100원 단위가 아닌 양수 금액
        long userId = 1L;

        // when & then 
        // 포인트 충전 시도 -> Exception 발생
        assertThatThrownBy(() -> pointService.chargePoint(userId, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("포인트는 100원 단위로만 충전할 수 있습니다.");
    }

    // 추가 요구사항2 - 심화 과제
    @ParameterizedTest
    @ValueSource(longs = {99999900L, 1000001L})
    @DisplayName("한 번에 백만원을 초과하여 충전할 수 없다.")
    void chargePointExceedingMaximum(long amount) {
        // given
        // 충전 시 최대값(1000000) 초과
        long userId = 1L;

        // when & then
        // 포인트 충전 시도 -> Exception 발생
        assertThatThrownBy(() -> pointService.chargePoint(userId, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("포인트는 한 번에 1,000,000원까지만 충전 가능합니다.");
    }

    @Test
    @DisplayName("충전 시 사용내역에 저장한다.")
    void chargePointSaveHistory() {
        // given
        // 유저 ID, 충전할 금액
        long userId = 1L;
        long amount = 1000L;

        when(userPointTable.selectById(userId)).thenReturn(new UserPoint(userId, 100L, 0L));
        when(userPointTable.insertOrUpdate(userId, amount + 100L)).thenReturn(new UserPoint(userId, amount + 100L, 0L));

        // when
        UserPoint userPoint = pointService.chargePoint(userId, amount);

        // then
        verify(pointHistoryTable).insert(eq(userId), eq(amount), eq(TransactionType.CHARGE), anyLong());
    }

    // 3. 포인트 사용 테스트
    @Test
    @DisplayName("유저의 포인트를 사용한다.")
    void useExistUserPoint() {
        // given
        // 포인트를 가진 유저 ID, 사용할 포인트 금액
        long userId = 1L;
        long amount = 1000L;
        when(userPointTable.selectById(userId)).thenReturn(new UserPoint(userId, 2500L, 0));
        when(userPointTable.insertOrUpdate(userId, 1500L)).thenReturn(new UserPoint(userId, 1500L, 0));

        // when
        // 해당 유저가 가진 포인트보다 적은 포인트를 사용
        UserPoint userPoint = pointService.usePoint(userId, amount);

        // then
        // 기존 포인트 - 사용금액 반환
        assertThat(userPoint.point()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("포인트 충전을 하지 않은 사용자는 사용할 수 없다.")
    void useNonExistUserPoint() {
        // given
        // 존재하지 않는 유저 ID, 포인트 금액
        long userId = 999L;
        long amount = 1000L;
        when(userPointTable.selectById(userId)).thenReturn(null);

        // when & then
        // 해당 유저의 ID로 포인트 사용 -> IllegalArgumentsException 발생
        assertThatThrownBy(() -> pointService.usePoint(userId, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("포인트 충전이 필요합니다.");
    }

    @Test
    @DisplayName("유저가 가진 포인트보다 많은 포인트는 사용할 수 없다.")
    void useMoreUserPointThenExist() {
        // given
        // 유저 ID, 보유 포인트보다 큰 사용 금액
        long userId = 1;
        long amount = 2000L;
        when(userPointTable.selectById(userId)).thenReturn(new UserPoint(userId, 1000L, 0));

        // when & then
        // 해당 유저가 가진 포인트보다 많은 포인트를 사용 -> IllegalArgumentsException 발생
        assertThatThrownBy(() -> pointService.usePoint(userId, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용자의 포인트가 부족합니다. 현재 포인트 : " + 1000L);
    }

    @Test
    @DisplayName("0원 이하의 금액은 사용할 수 없다.")
    void useMinusUserPoint() {
        // given
        // 유저 Id, 0원 이하의 금액
        long userId = 1L;
        long amount = -1000L;

        // when & then
        // 해당 유저가 0 또는 음수의 포인트를 사용 -> IllegalArgumentsException 발생
        assertThatThrownBy(() -> pointService.usePoint(userId, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("0 이하의 포인트는 사용할 수 없습니다.");
    }

    // 추가 요구사항3 - 심화 과제
    @Test
    @DisplayName("보유 포인트가 1000원 미만일 경우 사용할 수 없다.")
    void useLessThanMinimumPoint() {
        // given
        // 유저의 보유 포인트가 1000원 미만
        long userId = 1L;
        when(userPointTable.selectById(userId)).thenReturn(new UserPoint(userId, 900L, 0));
        long amount = 100L;

        // when & then
        // 해당 유저가 포인트를 사용 시도 -> exception 발생
        assertThatThrownBy(() -> pointService.usePoint(userId, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("보유 포인트가 1000원 미만일 경우 사용하실 수 없습니다.");
    }

    @Test
    @DisplayName("사용 시 사용내역에 저장한다.")
    void usePointSaveHistory() {
        // given
        // 유저 ID, 사용할 금액
        long userId = 1L;
        long amount = 1000L;
        when(userPointTable.selectById(userId)).thenReturn(new UserPoint(userId, 5000L, 0L));
        when(userPointTable.insertOrUpdate(userId, 5000L - amount)).thenReturn(new UserPoint(userId, 5000L - amount, 0L));

        // when
        UserPoint userPoint = pointService.usePoint(userId, amount);

        // then
        verify(pointHistoryTable).insert(eq(userId), eq(amount), eq(TransactionType.USE), anyLong());
    }

    // 4. 포인트 내역 조회 테스트
    @Test
    @DisplayName("유저의 포인트 사용/충전 내역을 조회한다.")
    void historyUserPoint() {
        // given
        // 포인트 내역이 있는 유저 ID
        long userId = 1L;
        List<PointHistory> pointHistoryList = List.of(new PointHistory(1L, userId, 1000L, TransactionType.CHARGE, 0L));
        when(pointHistoryTable.selectAllByUserId(userId)).thenReturn(pointHistoryList);

        // when
        // 히스토리 조회
        List<PointHistory> searchedHistoryList = pointService.getAllPointHistory(userId);

        // then
        // 해당 유저의 포인트 사용/충전 내역을 반환
        assertThat(searchedHistoryList).isNotNull();
        assertThat(searchedHistoryList.size()).isEqualTo(1);
        assertThat(searchedHistoryList.get(0).id()).isEqualTo(1L);
        assertThat(searchedHistoryList.get(0).userId()).isEqualTo(userId);
        assertThat(searchedHistoryList.get(0).amount()).isEqualTo(1000L);
        assertThat(searchedHistoryList.get(0).type()).isEqualTo(TransactionType.CHARGE);
        assertThat(searchedHistoryList.get(0).updateMillis()).isEqualTo(0L);
    }

    @Test
    @DisplayName("포인트 내역이 없는 유저는 빈 리스트를 반환한다.")
    void getEmptyPointHistories() {
        // given
        // 포인트 내역이 없는 유저
        long userId = 1L;
        when(pointHistoryTable.selectAllByUserId(userId)).thenReturn(List.of());

        // when
        // 히스토리 조회
        List<PointHistory> pointHistoryList = pointService.getAllPointHistory(userId);

        // then
        // 빈 리스트 반환
        assertThat(pointHistoryList).isNotNull();
        assertThat(pointHistoryList.size()).isEqualTo(0);
    }

    // 5. 동시성 제어 테스트
    @Test
    @DisplayName("동시에 같은 사용자가 포인트를 충전할 때 모든 충전이 정확히 반영되어야 한다")
    void concurrentChargePointForSameUser() throws Exception {
        // given
        // 같은 사용자 ID, 동시 요청 수, 충전 금액
        long userId = 1L;
        int threadCount = 10;  // 10개의 스레드가 동시에 충전 시도
        long chargeAmount = 1000L;
        long initialPoint = 5000L;

        when(userPointTable.selectById(userId))
                .thenAnswer(invocation -> {
                    // 실제로는 동시성 제어로 인해 순차적으로 업데이트되므로 여기서는 단순화를 위해 초기값 반환 (실제 금액 계산보다는 동시성 제어가 되는지 확인)
                    return new UserPoint(userId, initialPoint, 0L);
                });

        // insertOrUpdate는 호출될 때마다 누적된 포인트 반환하도록 설정
        AtomicInteger callCount = new AtomicInteger(0);  // 호출 횟수를 추적하는 카운터
        when(userPointTable.insertOrUpdate(eq(userId), anyLong()))
                .thenAnswer(invocation -> {
                    // 두 번째 파라미터로 전달된 금액 (initialPoint + chargeAmount * 호출횟수 (누적))
                    long updatedAmount = invocation.getArgument(1);
                    callCount.incrementAndGet();  // 호출 횟수 증가
                    return new UserPoint(userId, updatedAmount, System.currentTimeMillis());
                });

        // 10개 쓰레드 작업을 병렬로 실행하는 쓰레드 풀 생성
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        // 모든 쓰레드 작업 완료를 기다리는 동기화 도구, 10번의 countDown()이 호출될 때까지 기다린다.
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when
        // 10개의 쓰레드가 동시에 같은 사용자의 포인트를 충전
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    pointService.chargePoint(userId, chargeAmount);
                } finally {
                    // 이 작업이 10번 호출되면 latch.await()가 해제됨
                    latch.countDown();
                }
            });
        }

        // 모든 스레드의 작업이 완료될 때까지 대기
        latch.await();

        // 스레드 풀 종료 (더 이상 새 작업을 받지 않음)
        executorService.shutdown();

        // then
        // insertOrUpdate가 정확히 10번 호출되었는지 검증
        assertThat(callCount.get()).isEqualTo(threadCount);

        // pointHistoryTable.insert가 10번 호출되었는지 검증
        verify(pointHistoryTable, org.mockito.Mockito.times(threadCount))
                .insert(eq(userId), eq(chargeAmount), eq(TransactionType.CHARGE), anyLong());
    }

    @Test
    @DisplayName("동시에 같은 사용자가 포인트를 사용할 때 모든 사용이 정확히 반영되어야 한다")
    void concurrentUsePointForSameUser() throws Exception {
        // given
        long userId = 1L;
        int threadCount = 5;  // 5개의 스레드가 동시에 사용 시도
        long useAmount = 1000L;
        long initialPoint = 10000L;

        when(userPointTable.selectById(userId))
                .thenReturn(new UserPoint(userId, initialPoint, 0L));

        AtomicInteger callCount = new AtomicInteger(0);  // insertOrUpdate 호출 횟수 추적
        when(userPointTable.insertOrUpdate(eq(userId), anyLong()))
                .thenAnswer(invocation -> {
                    long updatedAmount = invocation.getArgument(1);
                    callCount.incrementAndGet();
                    return new UserPoint(userId, updatedAmount, System.currentTimeMillis());
                });

        // 스레드 풀 생성
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when
        // 5개의 스레드가 동시에 같은 사용자의 포인트를 사용
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    pointService.usePoint(userId, useAmount);
                } finally {
                    latch.countDown();
                }
            });
        }

        // 모든 스레드 작업 완료 대기
        latch.await();
        executorService.shutdown();

        // then
        // insertOrUpdate가 정확히 threadCount만큼 호출되었는지 검증
        assertThat(callCount.get()).isEqualTo(threadCount);

        // pointHistoryTable.insert가 threadCount만큼 호출되었는지 검증
        verify(pointHistoryTable, org.mockito.Mockito.times(threadCount))
                .insert(eq(userId), eq(useAmount), eq(TransactionType.USE), anyLong());
    }

    @Test
    @DisplayName("서로 다른 사용자는 동시에 포인트를 충전할 수 있어야 한다")
    void concurrentChargePointForDifferentUsers() throws Exception {
        // given
        // 서로 다른 사용자 ID
        long userId1 = 1L;
        long userId2 = 2L;
        int requestPerUser = 5;  // 각 사용자당 5번씩 충전
        long chargeAmount = 1000L;
        long initialPoint = 5000L;

        // 사용자별로 독립적인 Lock이 적용되므로 병렬 처리가 가능하다.
        when(userPointTable.selectById(userId1))
                .thenReturn(new UserPoint(userId1, initialPoint, 0L));
        when(userPointTable.selectById(userId2))
                .thenReturn(new UserPoint(userId2, initialPoint, 0L));

        // 각 사용자별 호출 횟수 추적
        AtomicInteger user1CallCount = new AtomicInteger(0);
        AtomicInteger user2CallCount = new AtomicInteger(0);

        when(userPointTable.insertOrUpdate(eq(userId1), anyLong()))
                .thenAnswer(invocation -> {
                    long updatedAmount = invocation.getArgument(1);
                    user1CallCount.incrementAndGet();
                    return new UserPoint(userId1, updatedAmount, System.currentTimeMillis());
                });

        when(userPointTable.insertOrUpdate(eq(userId2), anyLong()))
                .thenAnswer(invocation -> {
                    long updatedAmount = invocation.getArgument(1);
                    user2CallCount.incrementAndGet();
                    return new UserPoint(userId2, updatedAmount, System.currentTimeMillis());
                });

        // 총 스레드 수: 각 사용자당 5번씩 = 10개
        int totalThreadCount = requestPerUser * 2;
        ExecutorService executorService = Executors.newFixedThreadPool(totalThreadCount);
        CountDownLatch latch = new CountDownLatch(totalThreadCount);

        // when
        // 두 사용자의 요청이 섞여서 동시에 실행되며, 서로 병렬 처리된다.
        // 사용자별 Lock으로 인해 같은 사용자 요청끼리만 순차 처리된다.
        for (int i = 0; i < requestPerUser; i++) {
            // 사용자1의 충전 요청
            executorService.submit(() -> {
                try {
                    pointService.chargePoint(userId1, chargeAmount);
                } finally {
                    latch.countDown();
                }
            });

            // 사용자2의 충전 요청
            executorService.submit(() -> {
                try {
                    pointService.chargePoint(userId2, chargeAmount);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // then
        // 각 사용자별로 정확히 requestPerUser번씩 호출되었는지 검증
        assertThat(user1CallCount.get()).isEqualTo(requestPerUser);
        assertThat(user2CallCount.get()).isEqualTo(requestPerUser);

        // 각 사용자별 히스토리 기록 검증
        verify(pointHistoryTable, org.mockito.Mockito.times(requestPerUser))
                .insert(eq(userId1), eq(chargeAmount), eq(TransactionType.CHARGE), anyLong());
        verify(pointHistoryTable, org.mockito.Mockito.times(requestPerUser))
                .insert(eq(userId2), eq(chargeAmount), eq(TransactionType.CHARGE), anyLong());
    }
}
