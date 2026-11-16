# k8s 로컬 배포 (kind)

앱 + MySQL + Redis + Kafka 를 단일 노드 kind 클러스터에 올리는 순서.
EKS 배포는 아직 하지 않았다 — 여기까지가 검증된 범위다.

## 구성

| 리소스 | 종류 | 이유 |
|--------|------|------|
| `deps/mysql.yaml` | StatefulSet + PVC | 데이터가 파드보다 오래 살아야 함 |
| `deps/kafka.yaml` | StatefulSet + PVC | 브로커 정체성(node id, 오프셋)과 advertised 주소가 고정돼야 함 |
| `deps/redis.yaml` | Deployment | 락은 TTL 로 풀리고 재고 정합성은 MySQL 원자 UPDATE 가 지킴 (JWT 블랙리스트 유실은 감수) |
| `deployment.yaml` | Deployment (replicas 2) | 앱. initContainer 로 의존성 포트 대기 |

## 실행 순서

```bash
# 1) 클러스터 + 네임스페이스
kind create cluster --name driving
kubectl create namespace driving

# 2) MySQL 초기화 스크립트 — prod 프로파일은 sql.init.mode=never 라 DB 쪽에서 스키마를 넣는다.
#    01-/02- 접두사: initdb 디렉토리는 알파벳 순으로 실행되므로 schema 가 data 보다 먼저 돌아야 한다.
kubectl -n driving create configmap mysql-initdb \
  --from-file=01-schema.sql=src/main/resources/sql/schema.sql \
  --from-file=02-data.sql=src/main/resources/sql/data.sql

# 3) Secret — 실제 값은 저장소에 두지 않는다. MYSQL_PASSWORD 와 DB_PASSWORD 는 같은 값이어야 한다.
kubectl -n driving create secret generic mysql-secret \
  --from-literal=MYSQL_ROOT_PASSWORD=<root 비밀번호> \
  --from-literal=MYSQL_PASSWORD=<앱 계정 비밀번호>
kubectl -n driving create secret generic driving-reservation-secret \
  --from-literal=DB_PASSWORD=<앱 계정 비밀번호> \
  --from-literal=JWT_SECRET=<JWT 시크릿> \
  --from-literal=TOSS_SECRET_KEY=<토스 시크릿 키>

# 4) 의존성 먼저
kubectl -n driving apply -f k8s/deps/
kubectl -n driving get pods -w   # mysql-0, kafka-0, redis 가 Ready 될 때까지

# 5) 앱 이미지 — 태그를 latest 로 두면 pullPolicy 가 Always 가 되어 ErrImagePull 이 난다.
docker build -t driving-reservation:0.1.0 .
kind load docker-image driving-reservation:0.1.0 --name driving

# 6) 앱 (secret.yaml 은 placeholder 라 제외 — 3번에서 CLI 로 만들었다)
kubectl -n driving apply -f k8s/configmap.yaml -f k8s/deployment.yaml -f k8s/service.yaml
```

## 띄워봐야만 나온 결함

| 증상 | 원인 | 해결 |
|------|------|------|
| Kafka `UnknownHostException: kafka-0.kafka` 무한 재시도 | KRaft 브로커는 기동 중 자기 자신을 DNS 조회하는데, 헤드리스 Service 는 Ready 파드만 DNS 에 올림 → DNS와 Ready 가 서로를 기다리는 교착 | `publishNotReadyAddresses: true` |
| DB 의 한글이 깨져 저장됨 (앱 로직은 정상) | mysql:8.0 엔트리포인트가 initdb 를 실행하는 CLI 세션 charset 이 utf8mb4 가 아님 | `[client]/[mysql]/[mysqld]` charset 을 ConfigMap 으로 `/etc/mysql/conf.d` 에 마운트 후 PVC 재생성 |
| QoS 가 Guaranteed 가 아니라 Burstable | requests == limits 를 앱 컨테이너만 맞춤. Guaranteed 는 initContainer 포함 모든 컨테이너가 같아야 함 | initContainer resources 도 동일하게 |

진단 함정: 깨진 한글을 `mysql` CLI 로 SELECT 하면 결과 charset 변환이 깨짐을 되돌려 정상처럼 보인다. `HEX()` 로 바이트를 봐야 한다.

## 알려진 이슈

- 컨슈머 `listener.concurrency: 3` 은 단일 인스턴스 기준 값이다. `replicas: 2` 면 컨슈머 스레드가 6개가 되는데 파티션은 3개라 절반이 논다. 파티션 수를 늘릴지 concurrency 를 낮출지 아직 정하지 않았다.
- MySQL, Kafka 모두 단일 노드다. 운영이면 MySQL 은 복제와 백업, Kafka 는 브로커 3대 + `min.insync.replicas=2` 가 필요하다.
