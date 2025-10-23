package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PointService {

    final UserPointTable userPointTable;
    final PointHistoryTable pointHistoryTable;

    // 포인트 조회
    public UserPoint getUserPoint(long userId) {
        UserPoint userPoint = userPointTable.selectById(userId);

        if (userPoint == null) {
           userPoint = UserPoint.empty(userId);
        }

        return userPoint;
    }

    // 포인트 충전
    public UserPoint chargePoint(long userId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("0원 이하의 금액은 충전할 수 없습니다.");
        }
        else if (amount > 1000000) {
            throw new IllegalArgumentException("포인트는 한 번에 1,000,000원까지만 충전 가능합니다.");
        }
        else if (amount % 100 != 0) {
            throw new IllegalArgumentException("포인트는 100원 단위로만 충전할 수 있습니다.");
        }

        UserPoint originalUserPoint = userPointTable.selectById(userId);

        UserPoint userPoint = userPointTable.insertOrUpdate(userId, originalUserPoint.point() + amount);

        // 충전내역 저장
        pointHistoryTable.insert(userId, amount, TransactionType.CHARGE, System.currentTimeMillis());

        return userPoint;
    }

    // 포인트 사용
    public UserPoint usePoint(long userId, long amount) {

        if (amount <= 0) {
            throw new IllegalArgumentException("0 이하의 포인트는 사용할 수 없습니다.");
        }

        UserPoint originalUserPoint = userPointTable.selectById(userId);

        if (originalUserPoint == null) {
            throw new IllegalArgumentException("포인트 충전이 필요합니다.");
        }
        else if (originalUserPoint.point() < 1000L) {
            throw new IllegalArgumentException("보유 포인트가 1000원 미만일 경우 사용하실 수 없습니다.");
        }
        else if (originalUserPoint.point() < amount) {
            throw new IllegalArgumentException("사용자의 포인트가 부족합니다. 현재 포인트 : " + originalUserPoint.point());
        }

        UserPoint resultUserPoint = userPointTable.insertOrUpdate(userId, originalUserPoint.point() - amount);

        // 사용내역 저장
        pointHistoryTable.insert(userId, amount, TransactionType.USE, System.currentTimeMillis());

        return resultUserPoint;
    }

    // 포인트 히스토리 조회
    public List<PointHistory> getAllPointHistory(long userId) {
        return pointHistoryTable.selectAllByUserId(userId);
    }
}
