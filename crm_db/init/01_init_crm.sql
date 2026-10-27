-- Инициализация базы данных CRM
CREATE TABLE IF NOT EXISTS clients (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    client_name VARCHAR(255) NOT NULL,
    client_email VARCHAR(255) NOT NULL,
    prosthetic_id VARCHAR(64) NOT NULL,
    prosthetic_model VARCHAR(255) NOT NULL,
    last_calibration_date DATE,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Тестовые клиенты CRM
INSERT INTO clients (id, username, client_name, client_email, prosthetic_id, prosthetic_model, last_calibration_date, status) VALUES
('crm-c-001', 'prothetic1', 'Prothetic One', 'prothetic1@example.com', 'PROSTH-ALPHA-01', 'BionicPRO Hand Elite v2', CURRENT_DATE - INTERVAL '10 days', 'ACTIVE'),
('crm-c-002', 'prothetic2', 'Prothetic Two', 'prothetic2@example.com', 'PROSTH-BETA-02', 'BionicPRO Arm Ultra v1', CURRENT_DATE - INTERVAL '5 days', 'ACTIVE'),
('crm-c-003', 'prothetic3', 'Prothetic Three', 'prothetic3@example.com', 'PROSTH-ALPHA-03', 'BionicPRO Hand Elite v2', CURRENT_DATE - INTERVAL '12 days', 'ACTIVE'),
('crm-c-004', 'john.doe', 'John Doe', 'john@example.com', 'PROSTH-GLOBAL-01', 'BionicPRO BioArm Pro', CURRENT_DATE - INTERVAL '7 days', 'ACTIVE'),
('crm-c-005', 'alex.johnson', 'Alex Johnson', 'alex@example.com', 'PROSTH-GLOBAL-02', 'BionicPRO Hand Elite v2', CURRENT_DATE - INTERVAL '3 days', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;
