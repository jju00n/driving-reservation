CREATE TABLE IF NOT EXISTS users (
    member_idx  BIGINT      NOT NULL AUTO_INCREMENT,
    email       VARCHAR(100) NOT NULL,
    password    VARCHAR(60)  NOT NULL COMMENT 'BCrypt',
    name        VARCHAR(255) NOT NULL COMMENT 'AES-256',
    phone       VARCHAR(255) NOT NULL COMMENT 'AES-256',
    role        VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  DATETIME     NULL,
    PRIMARY KEY (member_idx),
    UNIQUE KEY uk_users_email (email)
);

CREATE TABLE IF NOT EXISTS vehicles (
    vehicle_idx BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
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
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
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
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (schedule_idx),
    CONSTRAINT fk_schedules_program FOREIGN KEY (program_idx) REFERENCES programs (program_idx)
);

CREATE TABLE IF NOT EXISTS reservations (
    reservation_idx BIGINT       NOT NULL AUTO_INCREMENT,
    member_idx      BIGINT       NOT NULL,
    program_idx     BIGINT       NOT NULL,
    schedule_idx    BIGINT       NOT NULL,
    order_id        VARCHAR(64)  NOT NULL,
    amount          BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PAYMENT_PENDING',
    reserved_at     DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (reservation_idx),
    UNIQUE KEY uk_reservations_order_id (order_id),
    CONSTRAINT fk_reservations_member   FOREIGN KEY (member_idx)   REFERENCES users (member_idx),
    CONSTRAINT fk_reservations_program  FOREIGN KEY (program_idx)  REFERENCES programs (program_idx),
    CONSTRAINT fk_reservations_schedule FOREIGN KEY (schedule_idx) REFERENCES schedules (schedule_idx)
);

CREATE TABLE IF NOT EXISTS payments (
    payment_idx     BIGINT       NOT NULL AUTO_INCREMENT,
    reservation_idx BIGINT       NOT NULL,
    payment_key     VARCHAR(200) NULL,
    order_id        VARCHAR(64)  NOT NULL,
    amount          BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    failure_code    VARCHAR(50)  NULL,
    failure_message VARCHAR(255) NULL,
    requested_at    DATETIME     NULL,
    approved_at     DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (payment_idx),
    UNIQUE KEY uk_payments_reservation  (reservation_idx),
    UNIQUE KEY uk_payments_payment_key  (payment_key),
    UNIQUE KEY uk_payments_order_id     (order_id),
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
