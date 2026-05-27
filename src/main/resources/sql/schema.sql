CREATE TABLE IF NOT EXISTS users (
    member_idx  BIGINT       NOT NULL AUTO_INCREMENT,
    email       VARCHAR(100) NOT NULL,
    password    VARCHAR(60)  NOT NULL COMMENT 'BCrypt',
    name        VARCHAR(255) NOT NULL,
    phone       VARCHAR(255) NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER' COMMENT 'CUSTOMER / ADMIN',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  DATETIME     NULL,
    PRIMARY KEY (member_idx),
    UNIQUE KEY uk_users_email (email)
);

CREATE TABLE IF NOT EXISTS vehicles (
    vehicle_idx BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    model       VARCHAR(100) NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (vehicle_idx)
);

CREATE TABLE IF NOT EXISTS programs (
    program_idx BIGINT       NOT NULL AUTO_INCREMENT,
    vehicle_idx BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    duration    INT          NOT NULL COMMENT '진행시간(분)',
    amount      BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (program_idx),
    CONSTRAINT fk_programs_vehicle FOREIGN KEY (vehicle_idx) REFERENCES vehicles (vehicle_idx)
);

CREATE TABLE IF NOT EXISTS schedules (
    schedule_idx BIGINT      NOT NULL AUTO_INCREMENT,
    program_idx  BIGINT      NOT NULL,
    start_at     DATETIME    NOT NULL,
    end_at       DATETIME    NOT NULL,
    capacity     INT         NOT NULL,
    remaining    INT         NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED / CANCELLED',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (schedule_idx),
    CONSTRAINT fk_schedules_program FOREIGN KEY (program_idx) REFERENCES programs (program_idx),
    INDEX idx_schedules_program_start (program_idx, start_at)
);

CREATE TABLE IF NOT EXISTS reservations (
    reservation_idx BIGINT       NOT NULL AUTO_INCREMENT,
    member_idx      BIGINT       NOT NULL,
    schedule_idx    BIGINT       NOT NULL,
    program_idx     BIGINT       NOT NULL,
    order_id        VARCHAR(64)  NOT NULL,
    amount          BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PAYMENT_PENDING' COMMENT '결제대기/예약확정/결제실패/예약취소/예약만료',
    reserved_at     DATETIME     NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (reservation_idx),
    UNIQUE KEY uk_reservations_order_id (order_id),
    CONSTRAINT fk_reservations_member   FOREIGN KEY (member_idx)   REFERENCES users (member_idx),
    CONSTRAINT fk_reservations_schedule FOREIGN KEY (schedule_idx) REFERENCES schedules (schedule_idx),
    CONSTRAINT fk_reservations_program  FOREIGN KEY (program_idx)  REFERENCES programs (program_idx),
    INDEX idx_reservations_member_idx (member_idx)
);

CREATE TABLE IF NOT EXISTS payments (
    payment_idx     BIGINT       NOT NULL AUTO_INCREMENT,
    reservation_idx BIGINT       NOT NULL,
    payment_key     VARCHAR(200) NULL,
    order_id        VARCHAR(64)  NOT NULL,
    amount          BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '결제대기/결제요청/결제완료/환불요청/환불완료/결제실패',
    failure_code    VARCHAR(50)  NULL,
    failure_message VARCHAR(255) NULL,
    requested_at    DATETIME     NULL,
    approved_at     DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (payment_idx),
    UNIQUE KEY uk_payments_reservation (reservation_idx),
    UNIQUE KEY uk_payments_payment_key (payment_key),
    UNIQUE KEY uk_payments_order_id    (order_id),
    CONSTRAINT fk_payments_reservation FOREIGN KEY (reservation_idx) REFERENCES reservations (reservation_idx)
);

CREATE TABLE IF NOT EXISTS reservation_histories (
    history_idx     BIGINT      NOT NULL AUTO_INCREMENT,
    reservation_idx BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (history_idx),
    CONSTRAINT fk_res_histories_reservation FOREIGN KEY (reservation_idx) REFERENCES reservations (reservation_idx)
);

CREATE TABLE IF NOT EXISTS payment_histories (
    history_idx BIGINT      NOT NULL AUTO_INCREMENT,
    payment_idx BIGINT      NOT NULL,
    status      VARCHAR(20) NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (history_idx),
    CONSTRAINT fk_pay_histories_payment FOREIGN KEY (payment_idx) REFERENCES payments (payment_idx)
);

CREATE TABLE IF NOT EXISTS webhook_events (
    webhook_event_idx BIGINT        NOT NULL AUTO_INCREMENT,
    event_type        VARCHAR(50)   NOT NULL COMMENT 'PAYMENT_STATUS_CHANGED / CANCEL_STATUS_CHANGED',
    order_id          VARCHAR(64)   NULL,
    raw_payload       TEXT          NOT NULL COMMENT '토스 웹훅 원문 JSON',
    status            VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED / PROCESSED / FAILED',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (webhook_event_idx),
    INDEX idx_webhook_events_order_id (order_id)
);
