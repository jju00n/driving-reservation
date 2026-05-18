-- 로컬 테스트용 더미 데이터
INSERT IGNORE INTO vehicles (vehicle_idx, name) VALUES
(1, 'BMW M3'),
(2, 'BMW M5'),
(3, 'BMW M8');

INSERT IGNORE INTO programs (program_idx, vehicle_idx, name, duration, amount, status) VALUES
(1, 1, 'M3 드라이빙 체험', 60, 150000, 'ACTIVE'),
(2, 2, 'M5 고성능 드라이빙', 90, 220000, 'ACTIVE'),
(3, 3, 'M8 프리미엄 드라이빙', 120, 350000, 'ACTIVE');

INSERT IGNORE INTO schedules (schedule_idx, program_idx, start_at, end_at, capacity, remaining, status) VALUES
(1, 1, '2026-06-01 10:00:00', '2026-06-01 11:00:00', 10, 10, 'OPEN'),
(2, 1, '2026-06-01 13:00:00', '2026-06-01 14:00:00', 10, 10, 'OPEN'),
(3, 2, '2026-06-02 10:00:00', '2026-06-02 11:30:00', 8,  8,  'OPEN'),
(4, 3, '2026-06-03 14:00:00', '2026-06-03 16:00:00', 5,  5,  'OPEN');
