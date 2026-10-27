CREATE DATABASE IF NOT EXISTS bionicpro_analytics;

-- 1. Таблица учета обработанных ETL-периодов
CREATE TABLE IF NOT EXISTS bionicpro_analytics.etl_processed_periods
(
    dataset_name String,
    period_start DateTime,
    period_end DateTime,
    processed_at DateTime DEFAULT now(),
    records_count UInt64,
    status String
)
ENGINE = ReplacingMergeTree()
ORDER BY (dataset_name, period_end);

-- 2. Таблица KafkaEngine для чтения CDC стрима изменений из топика crm_cdc.public.clients
DROP TABLE IF EXISTS bionicpro_analytics.crm_clients_kafka_stream;

CREATE TABLE bionicpro_analytics.crm_clients_kafka_stream
(
    op String,
    after Tuple(
        id Nullable(String),
        username Nullable(String),
        client_name Nullable(String),
        client_email Nullable(String),
        prosthetic_id Nullable(String),
        prosthetic_model Nullable(String),
        last_calibration_date Nullable(Int32),
        status Nullable(String)
    )
)
ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092',
         kafka_topic_list = 'crm_cdc.public.clients',
         kafka_group_name = 'clickhouse_crm_cdc_group',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = 1;

-- 3. Реплицированная таблица клиентов CRM в ClickHouse
CREATE TABLE IF NOT EXISTS bionicpro_analytics.crm_clients_replicated
(
    client_id String,
    username String,
    client_name String,
    client_email String,
    prosthetic_id String,
    prosthetic_model String,
    last_calibration_date Nullable(Date),
    status String,
    cdc_op String,
    replicated_at DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(replicated_at)
ORDER BY (client_id, username);

-- 4. MaterializedView для переливки сообщений из KafkaEngine в crm_clients_replicated
DROP TABLE IF EXISTS bionicpro_analytics.mv_crm_clients_kafka_to_replicated;

CREATE MATERIALIZED VIEW bionicpro_analytics.mv_crm_clients_kafka_to_replicated
TO bionicpro_analytics.crm_clients_replicated AS
SELECT
    assumeNotNull(after.1) AS client_id,
    assumeNotNull(after.2) AS username,
    ifNull(after.3, '') AS client_name,
    ifNull(after.4, '') AS client_email,
    ifNull(after.5, '') AS prosthetic_id,
    ifNull(after.6, '') AS prosthetic_model,
    if(after.7 IS NULL, NULL, toDate('1970-01-01') + after.7) AS last_calibration_date,
    ifNull(after.8, 'ACTIVE') AS status,
    op AS cdc_op,
    now() AS replicated_at
FROM bionicpro_analytics.crm_clients_kafka_stream
WHERE after.1 IS NOT NULL AND after.2 IS NOT NULL;

-- 5. Базовая таблица телеметрии
CREATE TABLE IF NOT EXISTS bionicpro_analytics.telemetry_daily_aggregates
(
    prosthetic_id LowCardinality(String),
    report_date Date,
    total_active_seconds UInt32,
    gestures_performed UInt32,
    avg_muscle_signal_amplitude Float32,
    max_muscle_signal_amplitude Float32,
    avg_battery_level Float32,
    battery_charge_cycles UInt16,
    avg_response_latency_ms Float32,
    warning_alerts_count UInt16
)
ENGINE = SummingMergeTree(
    (total_active_seconds, gestures_performed, warning_alerts_count)
)
PARTITION BY toYYYYMM(report_date)
ORDER BY (prosthetic_id, report_date);

-- 6. Новая отчётная витрина (View) объединяющая CDC данные CRM и телеметрию
CREATE OR REPLACE VIEW bionicpro_analytics.v_user_prosthetic_reports_datamart AS
SELECT
    c.username AS username,
    c.client_id AS client_id,
    c.client_name AS client_name,
    c.client_email AS client_email,
    c.prosthetic_id AS prosthetic_id,
    c.prosthetic_model AS prosthetic_model,
    t.report_date AS report_date,
    t.total_active_seconds AS total_active_seconds,
    t.gestures_performed AS gestures_performed,
    t.avg_muscle_signal_amplitude AS avg_muscle_signal_amplitude,
    t.max_muscle_signal_amplitude AS max_muscle_signal_amplitude,
    t.avg_battery_level AS avg_battery_level,
    t.battery_charge_cycles AS battery_charge_cycles,
    t.avg_response_latency_ms AS avg_response_latency_ms,
    t.warning_alerts_count AS warning_alerts_count,
    c.last_calibration_date AS last_calibration_date
FROM bionicpro_analytics.crm_clients_replicated AS c
INNER JOIN bionicpro_analytics.telemetry_daily_aggregates AS t
    ON c.prosthetic_id = t.prosthetic_id;

-- Прежняя витрина для обратной совместимости
CREATE TABLE IF NOT EXISTS bionicpro_analytics.user_prosthetic_reports_datamart
(
    username LowCardinality(String),
    client_id String,
    client_name String,
    client_email String,
    prosthetic_id LowCardinality(String),
    prosthetic_model String,
    report_date Date,
    total_active_seconds UInt32,
    gestures_performed UInt32,
    avg_muscle_signal_amplitude Float32,
    max_muscle_signal_amplitude Float32,
    avg_battery_level Float32,
    battery_charge_cycles UInt16,
    avg_response_latency_ms Float32,
    warning_alerts_count UInt16,
    last_calibration_date Date,
    etl_loaded_at DateTime DEFAULT now()
)
ENGINE = SummingMergeTree(
    (total_active_seconds, gestures_performed, warning_alerts_count)
)
PARTITION BY toYYYYMM(report_date)
ORDER BY (username, prosthetic_id, report_date);

-- Заполнение начальных данных
INSERT INTO bionicpro_analytics.crm_clients_replicated (client_id, username, client_name, client_email, prosthetic_id, prosthetic_model, last_calibration_date, status, cdc_op) VALUES ('crm-c-001', 'prothetic1', 'Prothetic One', 'prothetic1@example.com', 'PROSTH-ALPHA-01', 'BionicPRO Hand Elite v2', today() - 10, 'ACTIVE', 'r');
INSERT INTO bionicpro_analytics.crm_clients_replicated (client_id, username, client_name, client_email, prosthetic_id, prosthetic_model, last_calibration_date, status, cdc_op) VALUES ('crm-c-002', 'prothetic2', 'Prothetic Two', 'prothetic2@example.com', 'PROSTH-BETA-02', 'BionicPRO Arm Ultra v1', today() - 5, 'ACTIVE', 'r');
INSERT INTO bionicpro_analytics.crm_clients_replicated (client_id, username, client_name, client_email, prosthetic_id, prosthetic_model, last_calibration_date, status, cdc_op) VALUES ('crm-c-003', 'prothetic3', 'Prothetic Three', 'prothetic3@example.com', 'PROSTH-ALPHA-03', 'BionicPRO Hand Elite v2', today() - 12, 'ACTIVE', 'r');
INSERT INTO bionicpro_analytics.crm_clients_replicated (client_id, username, client_name, client_email, prosthetic_id, prosthetic_model, last_calibration_date, status, cdc_op) VALUES ('crm-c-004', 'john.doe', 'John Doe', 'john@example.com', 'PROSTH-GLOBAL-01', 'BionicPRO BioArm Pro', today() - 7, 'ACTIVE', 'r');
INSERT INTO bionicpro_analytics.crm_clients_replicated (client_id, username, client_name, client_email, prosthetic_id, prosthetic_model, last_calibration_date, status, cdc_op) VALUES ('crm-c-005', 'alex.johnson', 'Alex Johnson', 'alex@example.com', 'PROSTH-GLOBAL-02', 'BionicPRO Hand Elite v2', today() - 3, 'ACTIVE', 'r');

INSERT INTO bionicpro_analytics.telemetry_daily_aggregates (prosthetic_id, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count) VALUES ('PROSTH-ALPHA-01', today() - 2, 28800, 1420, 48.5, 96.2, 82.0, 1, 64.2, 0);
INSERT INTO bionicpro_analytics.telemetry_daily_aggregates (prosthetic_id, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count) VALUES ('PROSTH-ALPHA-01', today() - 1, 31200, 1680, 51.2, 98.4, 76.5, 1, 61.8, 1);
INSERT INTO bionicpro_analytics.telemetry_daily_aggregates (prosthetic_id, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count) VALUES ('PROSTH-BETA-02', today() - 1, 24500, 980, 42.1, 88.0, 89.0, 1, 72.5, 0);
INSERT INTO bionicpro_analytics.telemetry_daily_aggregates (prosthetic_id, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count) VALUES ('PROSTH-ALPHA-03', today() - 1, 29000, 1250, 46.0, 91.5, 71.0, 2, 68.0, 0);
INSERT INTO bionicpro_analytics.telemetry_daily_aggregates (prosthetic_id, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count) VALUES ('PROSTH-GLOBAL-01', today() - 1, 27400, 1340, 47.8, 93.1, 85.0, 1, 65.0, 0);
INSERT INTO bionicpro_analytics.telemetry_daily_aggregates (prosthetic_id, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count) VALUES ('PROSTH-GLOBAL-02', today() - 1, 32000, 1750, 53.0, 99.0, 79.0, 1, 59.5, 2);

INSERT INTO bionicpro_analytics.user_prosthetic_reports_datamart (username, client_id, client_name, client_email, prosthetic_id, prosthetic_model, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count, last_calibration_date) VALUES ('prothetic1', 'crm-c-001', 'Prothetic One', 'prothetic1@example.com', 'PROSTH-ALPHA-01', 'BionicPRO Hand Elite v2', today() - 2, 28800, 1420, 48.5, 96.2, 82.0, 1, 64.2, 0, today() - 10);
INSERT INTO bionicpro_analytics.user_prosthetic_reports_datamart (username, client_id, client_name, client_email, prosthetic_id, prosthetic_model, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count, last_calibration_date) VALUES ('prothetic1', 'crm-c-001', 'Prothetic One', 'prothetic1@example.com', 'PROSTH-ALPHA-01', 'BionicPRO Hand Elite v2', today() - 1, 31200, 1680, 51.2, 98.4, 76.5, 1, 61.8, 1, today() - 10);
INSERT INTO bionicpro_analytics.user_prosthetic_reports_datamart (username, client_id, client_name, client_email, prosthetic_id, prosthetic_model, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count, last_calibration_date) VALUES ('prothetic2', 'crm-c-002', 'Prothetic Two', 'prothetic2@example.com', 'PROSTH-BETA-02', 'BionicPRO Arm Ultra v1', today() - 1, 24500, 980, 42.1, 88.0, 89.0, 1, 72.5, 0, today() - 5);
INSERT INTO bionicpro_analytics.user_prosthetic_reports_datamart (username, client_id, client_name, client_email, prosthetic_id, prosthetic_model, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count, last_calibration_date) VALUES ('prothetic3', 'crm-c-003', 'Prothetic Three', 'prothetic3@example.com', 'PROSTH-ALPHA-03', 'BionicPRO Hand Elite v2', today() - 1, 29000, 1250, 46.0, 91.5, 71.0, 2, 68.0, 0, today() - 12);
INSERT INTO bionicpro_analytics.user_prosthetic_reports_datamart (username, client_id, client_name, client_email, prosthetic_id, prosthetic_model, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count, last_calibration_date) VALUES ('john.doe', 'crm-c-004', 'John Doe', 'john@example.com', 'PROSTH-GLOBAL-01', 'BionicPRO BioArm Pro', today() - 1, 27400, 1340, 47.8, 93.1, 85.0, 1, 65.0, 0, today() - 7);
INSERT INTO bionicpro_analytics.user_prosthetic_reports_datamart (username, client_id, client_name, client_email, prosthetic_id, prosthetic_model, report_date, total_active_seconds, gestures_performed, avg_muscle_signal_amplitude, max_muscle_signal_amplitude, avg_battery_level, battery_charge_cycles, avg_response_latency_ms, warning_alerts_count, last_calibration_date) VALUES ('alex.johnson', 'crm-c-005', 'Alex Johnson', 'alex@example.com', 'PROSTH-GLOBAL-02', 'BionicPRO Hand Elite v2', today() - 1, 32000, 1750, 53.0, 99.0, 79.0, 1, 59.5, 2, today() - 3);

INSERT INTO bionicpro_analytics.etl_processed_periods (dataset_name, period_start, period_end, records_count, status) VALUES ('user_prosthetic_reports', toDateTime(today() - 30), toDateTime(today() - 1) + 86399, 6, 'COMPLETED');
