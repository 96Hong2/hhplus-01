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

    // 추가 요구사항1
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

    // 추가 요구사항2
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

    // 추가 요구사항3
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
}
