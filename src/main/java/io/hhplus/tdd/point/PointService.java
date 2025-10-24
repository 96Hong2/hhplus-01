package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class PointService {

    final UserPointTable userPointTable;
    final PointHistoryTable pointHistoryTable;

    // 사용자별 Lock을 관리하는 Map (key : userId, value : ReentrantLock)
    private final ConcurrentHashMap<Long, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    // 사용자별 Lock 객체를 가져오거나 생성하는 메서드
    private ReentrantLock getUserLock(long userId) {
        // computeIfAbsent: Key가 없으면 새로 생성, 있으면 기존 값 반환
        return userLocks.computeIfAbsent(userId, id -> new ReentrantLock(true)); // fair lock (대기 순서대로 lock 획득)
    }

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
        // 해당 사용자의 Lock 객체를 가져옴
        ReentrantLock lock = getUserLock(userId);

        // Lock 획득 - 다른 스레드가 이 사용자의 Lock을 가지고 있으면 대기
        // 같은 사용자에 대한 모든 작업이 순서대로 실행되도록 보장한다.
        lock.lock();

        try {
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

            // 충전 내역을 히스토리 테이블에 저장
            pointHistoryTable.insert(userId, amount, TransactionType.CHARGE, System.currentTimeMillis());

            return userPoint;

        } finally {
            // finally 블록으로 예외 발생 시에도 항상 Lock 해제
            lock.unlock();
        }
    }

    // 포인트 사용
    public UserPoint usePoint(long userId, long amount) {
        // 해당 사용자의 Lock 객체를 가져옴
        ReentrantLock lock = getUserLock(userId);

        // Lock 획득 - 다른 스레드가 이 사용자의 Lock을 가지고 있으면 대기
        lock.lock();

        try {
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

            // 사용 내역을 히스토리 테이블에 저장
            pointHistoryTable.insert(userId, amount, TransactionType.USE, System.currentTimeMillis());

            return resultUserPoint;

        } finally {
            lock.unlock();
        }
    }

    // 포인트 히스토리 조회
    public List<PointHistory> getAllPointHistory(long userId) {
        return pointHistoryTable.selectAllByUserId(userId);
    }
}
