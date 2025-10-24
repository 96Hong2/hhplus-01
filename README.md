# 포인트 관리 시스템 - 동시성 제어 보고서

## 프로젝트 개요
테스트 주도 개발(TDD) 방법론을 공부하기 위한 프로젝트입니다.
스프링 부트 3.2.0 버전과 Java 17을 기반으로 합니다.

---

## Java 동시성 제어 방법 비교

### 1. synchronized 키워드

#### 개념
Java의 내장 동기화 키워드로, 메서드나 블록 단위로 락을 걸어 하나의 쓰레드만 접근하도록 제어합니다.

#### 장점
- synchronized 키워드만 붙이면 돼서 사용이 매우 쉬움
- JVM이 자동으로 Lock의 획득과 해제를 관리해주어 따로 해제(unlock) 처리 불필요
- 데드락 발생 시 예외가 발생하여 디버깅이 용이

#### 단점
- 유연성 부족: Lock 획득 시도 시간 제한, 타임아웃 설정 불가
- 공정성(Fairness) 보장 불가: 대기 중인 스레드의 순서를 보장하지 않음
- 현재 락이 걸려있는지 확인하는 메서드가 없어서 Lock의 상태를 확인 불가
- 읽기 작업과 쓰기 작업을 구분할 수 없어 읽기 성능 저하
- 메서드 또는 객체 전체에 락을 걸어야 하므로 세밀한 제어 어려움

#### 사용 예시
```java
public synchronized UserPoint chargePoint(long userId, long amount) {
    // 메서드 전체에 락이 걸림
}
```

---

### 2. ReentrantLock

#### 개념
동일한 쓰레드가 여러 번 락을 획득할 수 있는 재진입(Reentrant)의 특성을 가진 락 방법입니다. 
synchronized보다 더 유연하고 세밀한 락 기능들을 제공합니다.


#### 장점
- 같은 쓰레드가 중복으로 락 획득이 가능함(재진입)
- 공정성 모드를 지원하여 대기 중인 쓰레드가 큐처럼 FIFO 방식으로 순서를 보장받을 수 있음 (ReentrantLock(true))
- 타임아웃 설정이 가능
- 락 대기 중 쓰레드 인터럽트 처리가 가능
- 현재 락이 걸려있는지, 큐 길이 확인 등 가능 (isLocked(), getQueueLength())


#### 단점
- 명시적인 unlock 필요: finally 블록에서 반드시 해제해야 함
- synchronized보다 코드가 길고 복잡함
- unlock이 누락되면 데드락 발생 위험
- 약간의 오버헤드 존재 (synchronized보다 느릴 수 있음)

#### 사용 예시
```java
private final ConcurrentHashMap<Long, ReentrantLock> userLocks = new ConcurrentHashMap<>();

public UserPoint chargePoint(long userId, long amount) {
    ReentrantLock lock = userLocks.computeIfAbsent(userId, id -> new ReentrantLock(true));
    lock.lock();
    try {
        // 사용자별로 독립적인 락 관리
    } finally {
        lock.unlock();
    }
}
```

---

### 3. Atomic 클래스

#### 개념
CAS(Compare-And-Swap) 알고리즘을 사용해서 lock-free 동기화를 제공하는 클래스입니다.
락 없이도 현재 값과 예상 값을 비교하여 일치할 때만 업데이트를 수행하고, 실패시 재시도하는 CAS 알고리즘을 사용합니다.

#### 장점
- **Lock-Free**: 락을 사용하지 않아 데드락 발생 불가
- **높은 성능**: 락 오버헤드가 없어 매우 빠름
- 간단한 연산에 최적화
- 경량화된 동기화 메커니즘

#### 단점
- **단순 연산만 지원**: 복잡한 비즈니스 로직에는 부적합
- 여러 변수를 원자적으로 업데이트 불가
- 복합 연산(읽기-수정-쓰기)에는 반복 시도로 인한 성능 저하 가능

#### 사용 예시
```java
private final AtomicLong balance = new AtomicLong(0);

public long chargePoint(long amount) {
    return balance.addAndGet(amount); // 원자적 덧셈
}
```

#### 포인트 시스템에 사용하지 않은 이유
포인트 조회, 충전, 사용 등 업데이트, 히스토리 저장 등 복잡한 연산이 필요한데 단순 숫자 증감만으로는 처리하기 어려웠음

---

### 4. @Transactional (Database Lock)

#### 개념
Spring의 트랜잭션 관리를 통해 데이터베이스 레벨에서 동시성을 제어하는 방법입니다. 
비관적 락(Pessimistic Lock) 또는 낙관적 락(Optimistic Lock)을 사용합니다.

#### 장점
- Spring 환경에서 간편한 선언적 트랜잭션 관리
- 데이터베이스 레벨의 ACID 보장
- 분산 환경에서도 동작 가능 (DB를 공유하는 경우)
- 복잡한 비즈니스 로직을 트랜잭션으로 묶어 처리 가능

#### 단점
- **데이터베이스 의존성**: 실제 DB가 필요하며, 인메모리 테이블에서는 동작하지 않음
- 데이터베이스 커넥션 풀 고갈 가능성
- 긴 트랜잭션 시 성능 저하
- 비관적 락은 데드락 위험, 낙관적 락은 충돌 시 재시도 필요

#### 포인트 시스템에 사용하지 않은 이유
인메모리 테이블을 사용하기 때문에, 실제 DB가 없어서 트랜잭션 처리할 수 없음
(JPA Entity가 아닌 일반 Java객체를 사용)

---

### 5. StampedLock (Java 8+)

#### 개념
ReadWriteLock의 개선 버전으로, 낙관적 읽기(Optimistic Read)를 지원하여 더 높은 성능을 제공합니다.

#### 장점
- **낙관적 읽기**: 락을 걸지 않고 읽기 시도, 변경이 없으면 그대로 사용
- ReadWriteLock보다 높은 읽기 성능
- 락 업그레이드/다운그레이드 가능

#### 단점
- **재진입 불가**: 같은 스레드도 중복 락 획득 불가 (데드락 위험)
- 가장 복잡한 API와 사용법
- 잘못 사용 시 예측 불가능한 동작
- 읽기가 극도로 많은 특수한 경우에만 유용

---

### 6. ConcurrentHashMap

#### 개념
여러 세그먼트로 나누어 동시성을 제어하는 Thread-Safe한 해시맵입니다.

#### 장점
- **높은 동시성**: 여러 세그먼트로 나누어 동시 접근 가능
- Thread-Safe한 Map 연산 제공
- `computeIfAbsent` 등 원자적 연산 지원
- 읽기 작업은 대부분 Lock-Free

#### 단점
- Map 자료구조에 국한됨
- 복잡한 비즈니스 로직 자체는 보호하지 못함
- 메모리 오버헤드 존재

#### 포인트 시스템에서 역할
사용자별 ReentrantLock 객체를 넣어서 관리하기 위한 해시맵으로 사용하고, 
실제 동시성 제어는 ReentrantLock이 담당했음

---

## 프로젝트에서 최종 선택: ConcurrentHashMap + ReentrantLock

### 선택 이유

#### 1. 사용자별 독립적인 Lock 관리
ConcurrentHashMap으로 각 사용자(userId)마다 별도의 Lock 객체를 생성해서 사용자별로 독립적인 관리가 가능하고,
다른 사용자의 요청은 동시 처리가 가능했음

#### 2. 공정성(Fairness) 보장
대기 중인 쓰레드가 FIFO 방식으로 순서대로 락을 획득할 수 있어서 특정 사용자의 요청이 처리되지 않을 위험을 줄였음.
또 사용자 요청 순서대로 처리하여 사용자 경험을 높임

#### 3. 안전한 Lock 관리
finally 블록으로 Lock을 해제할 수 있어서 데드락이나 Lock 누수가 생기지 않음

#### 4. 인메모리 환경에 적합
프로젝트에서 `UserPointTable`, `PointHistoryTable`를 사용했는데, 
실제 DB는 아니기 때문에 JPA나 @Transactional 기반의 DB Lock이 사용 불가했음 
ReentrantLock은 JVM 메모리 내에서 동작하여 인메모리 환경에 적합했음

---

### API 엔드포인트
- `GET /point/{id}`: 포인트 조회
- `GET /point/{id}/histories`: 포인트 히스토리 조회
- `PATCH /point/{id}/charge`: 포인트 충전 (Body: `{"amount": 1000}`)
- `PATCH /point/{id}/use`: 포인트 사용 (Body: `{"amount": 500}`)

---

## 기술 스택
- Java 17
- Spring Boot 3.2.0
- JUnit 5
- AssertJ
- Lombok
- Gradle 8.5

