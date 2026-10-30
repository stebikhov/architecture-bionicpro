# Архитектурное решение: Управление учётными данными, безопасность и аналитическая платформа BionicPRO

## 1. Введение и контекст проблемы

Компания BionicPRO разрабатывает и производит бионические протезы. В результате ранее выявленных уязвимостей (утечка данных, компрометация пользовательских токенов на стороне SPA-клиента, риски штрафов по законам о защите персональных и медицинских данных), а также возросшей нагрузки на базу данных при запросах аналитики возникла необходимость комплексной модернизации архитектуры:

1. **Унификация доступа и локальное хранение данных**: единая точка входа через Keycloak IAM Broker при подключении к внешним источникам пользователей (User Federation / LDAP) в странах присутствия (GDPR, 152-ФЗ, HIPAA).
2. **Безопасная работа с токенами (BFF)**: перенос логики работы с access- и refresh-токенами на выделенный бэкенд-сервис `bionicpro-auth`; передача фронтенду безопасных HttpOnly Secure cookie с регулярной ротацией сессий против Session Fixation.
3. **Мультипровайдерная аутентификация (Identity Brokering)**: поддержка внешних удостоверяющих центров (Яндекс ID, госсервисы, корпоративные SAML/OIDC).
4. **Оптимизация аналитики и витрина отчётов**: внедрение ETL-пайплайна на Apache Airflow и колоночной аналитической СУБД ClickHouse.
5. **Снижение нагрузки на OLAP-базу**: кеширование готовых отчётов в объектном хранилище S3 (MinIO/Ceph) и их быстрая раздача пользователям через Reverse Proxy CDN (Nginx).

---

## 2. Ключевые компоненты архитектурного решения

### 2.1. Backend-for-Frontend / Security Gateway (`bionicpro-auth`)

- Выступает посредником между клиентским фронтендом и Identity Provider (Keycloak).
- Реализует протокол **OAuth 2.0 Authorization Code Flow с PKCE** (RFC 7636).
- Исключает передачу токенов на сторону браузера: фронтенд взаимодействует исключительно через `session_id` в куке `BIONIC_SESSION` (`HttpOnly`, `Secure`, `SameSite=Lax`).
- Хранит access-токены (в оперативной памяти) и refresh-токены (в зашифрованном виде AES-256-GCM).
- Выполняет ротацию `session_id` при каждом обращении к защищённым ресурсам для предотвращения атак фиксации сессии (Session Fixation).
- Автоматически обращается в Keycloak за обновлением `access_token` через `refresh_token`, когда его 2-минутный срок жизни подходит к концу.

### 2.2. Централизованный брокер идентификации (Keycloak IdP)

- Управляет жизненным циклом токенов: `accessTokenLifespan = 120s`, `ssoSessionIdleTimeout = 1800s`.
- Настроен на обязательную двухфакторную аутентификацию (TOTP MFA: Google Authenticator / FreeOTP).
- Интегрирован с региональным каталогом **OpenLDAP** через User Federation и ролевой маппинг (`role-ldap-mapper`).
- Поддерживает **Identity Brokering** с внешним сервисом **Яндекс ID** (OAuth 2.0 OIDC).

### 2.3. Сервис отчётов (`reports-api`)

- Написан на Java (Spring Boot 3, Spring Security Resource Server).
- Эндпоинт `GET /reports?startDate=...&endDate=...`:
  - Проверяет Bearer JWT токен.
  - Жёстко изолирует доступ: пользователь имеет возможность запросить и получить аналитику **только по собственному пользователю и протезу**.
  - Проверяет наличие отчёта в объектном хранилище S3 перед обращением в OLAP-базу.

### 2.4. Аналитическое хранилище ClickHouse и ETL Airflow

- **Apache Airflow (`bionicpro_telemetry_crm_etl_dag`)**: регулярный запуск каждый час (`0 * * * *`). Извлекает данные клиентов из CRM DB и телеметрию из PostgreSQL OLTP, агрегирует метрики и сохраняет в ClickHouse.
- **ClickHouse OLAP (`user_prosthetic_reports_datamart`)**: колоночная таблица на движке `SummingMergeTree` с первичным ключом `ORDER BY (username, prosthetic_id, report_date)`, обеспечивающая мгновенный поиск по пользователю без тяжёлых расчётов в рантайме.
- **Контроль обработанного периода (`etl_processed_periods`)**: отслеживание временной границы готовности данных Airflow с предупреждением пользователя при запросе ещё не обработанных периодов.

---

## 3. Снижение нагрузки на базу данных: Объектное хранилище S3 и CDN

### 3.1. Проблема повторных запросов

После запуска аналитических отчётов пользователи регулярно запрашивают данные за прошедшие периоды. Поскольку исходные данные обновляются регламентным ETL-процессом по расписанию, повторные обращения к ClickHouse создавали избыточную нагрузку, вычисляя одни и те же результаты.

### 3.2. Архитектура решения с S3 и CDN

Для снижения нагрузки на OLAP-базу внедрён двухуровневый контур кеширования:

1. **Первый эшелон (S3 Object Storage — MinIO/Ceph)**:
   - Сформированный JSON-отчёт сохраняется как неизменяемый объект в бакете `bionicpro-reports`.
   - При повторном запросе пользователя с теми же параметрами `reports-api` проверяет наличие объекта в S3. Если объект существует (**Cache Hit**), сервис не обращается в ClickHouse, а сразу возвращает данные и CDN-ссылку на объект.
   - Запрос к ClickHouse выполняется **только при Cache Miss**.

2. **Второй эшелон (Reverse Proxy CDN — Nginx)**:
   - Выступает граничным кешем перед S3-хранилищем (порт `8082`).
   - Настроена зона кеширования `proxy_cache s3_cdn_cache` на базе быстрого локального дискового хранилища с ключом `$scheme$request_method$host$request_uri`.
   - Директивы:
     - `proxy_cache_valid 200 206 1h;` — время жизни горячего кеша популярных отчётов.
     - `proxy_cache_use_stale error timeout updating;` — отдача кешированного отчёта даже при временной недоступности бэкенда S3.
     - `proxy_cache_background_update on;` — фоновое обновление кеша без блокировки клиентов.
     - Заголовок `X-Cache-Status` (`HIT`, `MISS`, `EXPIRED`, `STALE`) для прозрачного аудита кеша.

### 3.3. Структура хранения отчётов в S3 для быстрого доступа

Хранилище структурировано по иерархическому принципу с шардированием по пользователям:

```
bionicpro-reports/
└── reports/
    ├── {username}/
    │   ├── report_{startDate}_{endDate}.json
    │   ├── report_2026-10-01_2026-10-16.json
    │   └── report_2026-09-01_2026-09-30.json
    └── ...
```

**Преимущества данной структуры:**

- **Изоляция и безопасность**: префикс `reports/{username}/` позволяет гибко настраивать IAM-политики бакета (Bucket Policy / S3 ACL) для ограничения доступа на уровне объектов.
- **Мгновенный поиск**: ключ формируется детерминированно из имени пользователя и дат интервала (`reports/%s/report_%s_%s.json`), поиск выполняется за $O(1)$ по операции `GET/HEAD Object` без необходимости сканирования списка ключей (`ListObjects`).
- **Согласованность с CDN**: URL пути в CDN точно соответствует структуре ключа:
  `http://localhost:8082/bionicpro-reports/reports/{username}/report_{startDate}_{endDate}.json`.

### 3.4. Механизм инвалидации и обновления кеша

1. **TTL-инвалидация**: кеш в CDN валиден в течение 1 часа, что соответствует интервалу запусков часового ETL DAG Airflow.
2. **Параметр принудительного обновления (`forceRefresh=true`)**:
   - При передаче параметра `forceRefresh=true` или заголовка `Cache-Control: no-cache` сервис `reports-api` обращается напрямую к витрине ClickHouse, перезаписывает объект в S3 и обновляет кеш в CDN (`proxy_cache_bypass $http_cache_control`).
3. **ETL-триггерная инвалидация**:
   - При завершении загрузки новой порции данных Airflow может выполнять точечный сброс префиксов или перезапись ключей за текущий месяц для активных пользователей.

---

## 4. Схема потоков данных (Flows)

```
[Пользователь / Браузер]
         |
         | 1. GET /reports?startDate=...&endDate=... (с Cookie)
         v
[bionicpro-auth (BFF)]
         |
         | 2. Валидация сессии, ротация session_id,
         |    Bearer JWT проксирование
         v
[reports-api]
         |
         | 3. Проверка наличия отчёта в S3 (GET /bionicpro-reports/reports/{user}/...)
         +---------------------------------------+
         |                                       |
    [CACHE HIT]                             [CACHE MISS]
         |                                       |
         |                                       | 4. Запрос к витрине ClickHouse OLAP
         |                                       v
         |                              [ClickHouse OLAP]
         |                                       | 5. Агрегированные метрики
         |                                       v
         |                               [reports-api]
         |                                       | 6. Сохранение отчёта в S3 (PUT Object)
         |                                       v
         |                              [MinIO S3 Storage]
         |                                       |
         +---------------------------------------+
         |
         | 7. Ответ с телом отчёта, CDN URL и статусом кеша
         v
[bionicpro-auth]
         |
         | 8. JSON + Set-Cookie (rotated)
         v
[Пользователь / Браузер]
         |
         | 9. (Опционально) Прямое скачивание / открытие через CDN:
         |    GET http://localhost:8082/bionicpro-reports/reports/{user}/report_...json
         v
[CDN (Nginx Cache)] ---> (При Cache Miss забирает из MinIO, кеширует и отдаёт клиенту)
```

---

## 5. Задание 4: Повышение оперативности и стабильности CRM (Change Data Capture)

### 5.1. Контекст проблемы и архитектурное решение

Ранее аналитические запросы и регулярная выгрузка данных из базы данных CRM приводили к чрезмерной утилизации ресурсов PostgreSQL, деградации производительности транзакционных OLTP-операций, блокировкам строк и сбоям в работе менеджеров.

Для полного разделения транзакционной (OLTP) и аналитической (OLAP) нагрузок внедрён асинхронный механизм **Change Data Capture (CDC)** на базе **Debezium**, **Apache Kafka** и **ClickHouse KafkaEngine**:

1. **PostgreSQL CRM DB (`crm_db`)**:
   - Работает в режиме `wal_level = logical` с логической репликацией (`pgoutput`).
   - Транзакции пользователей (добавление, изменение клиентов и договоров) фиксируются в WAL без задержек и накладных расходов на чтение.
2. **Debezium PostgreSQL Connector (`debezium`)**:
   - Непрерывно считывает журнал упреждающей записи (WAL) PostgreSQL CRM без блокировки таблиц.
   - Транслирует изменения таблицы `public.clients` в виде JSON-событий (Create, Update, Delete) в топик Kafka `crm_cdc.public.clients`.
3. **Apache Kafka (`kafka`)**:
   - Обеспечивает надёжный буфер и шину потоковых данных между CRM и аналитическим контуром.
4. **ClickHouse KafkaEngine и Materialized View**:
   - Специальная таблица `crm_clients_kafka_stream` на движке `ENGINE = Kafka` читает поток событий из топика `crm_cdc.public.clients`.
   - Материализованное представление `mv_crm_clients_kafka_to_replicated` в потоковом режиме переносит данные в таблицу `crm_clients_replicated` (`ENGINE = ReplacingMergeTree`).
   - Итоговая витрина `v_user_prosthetic_reports_datamart` объединяет актуальные данные клиентов CRM с суточными агрегатами телеметрии `telemetry_daily_aggregates`.
5. **Сервис API (`reports-api`)**:
   - Переведён на запросы к новой витрине `v_user_prosthetic_reports_datamart` в ClickHouse, полностью изолируя CRM DB от аналитических нагрузок.

---

## 6. Сводная таблица реализованных сервисов и портов

| Сервис             | Порт           | Технология                        | Назначение                                                              |
| ------------------ | -------------- | --------------------------------- | ----------------------------------------------------------------------- |
| **frontend**       | `3000`         | React / Nginx                     | Пользовательский веб-интерфейс пилота протеза                           |
| **bionicpro-auth** | `8081`         | Java (Spring Boot)                | BFF-шлюз, OAuth2 PKCE, сессионные HttpOnly Cookie, ротация сессий       |
| **keycloak**       | `8080`         | Keycloak 21.1                     | Identity Provider, TOTP MFA, Яндекс ID Brokering, LDAP Federation       |
| **keycloak_db**    | `5433`         | PostgreSQL 14                     | База данных конфигурации Keycloak                                       |
| **crm_db**         | `5434`         | PostgreSQL 14 (wal_level=logical) | База данных CRM системы (клиенты, протезы)                              |
| **zookeeper**      | `2181`         | Apache Zookeeper                  | Координация кластера Kafka                                              |
| **kafka**          | `9092 / 29092` | Apache Kafka 7.5                  | Брокер сообщений для CDC топиков                                        |
| **debezium**       | `8083`         | Debezium Connect 2.4              | Захват изменений из WAL CRM DB и отправка в Kafka                       |
| **openldap**       | `389 / 636`    | OpenLDAP                          | Локальный каталог пользователей и групп зарубежного представительства   |
| **reports-api**    | `8000`         | Java (Spring Boot)                | Ресурсный сервис отчётов, авторизация JWT, интеграция с ClickHouse и S3 |
| **clickhouse**     | `8123 / 9000`  | ClickHouse 23.8                   | Аналитическая СУБД: KafkaEngine, MaterializedView, витрина отчётов      |
| **minio**          | только внутр. сеть | MinIO S3                          | Объектное хранилище готовых отчётов пользователей                       |
| **cdn**            | `8082`         | Nginx Reverse Proxy               | Граничный reverse proxy с кешированием статических отчётов из S3        |
| **airflow**        | -              | Apache Airflow                    | Регулярный ETL-пайплайн суточной агрегации телеметрии                   |

---

## 7. Инструкция по запуску и проверке работоспособности

### 7.1. Запуск проекта

Проект полностью упакован в `docker-compose.yaml`. Запуск всех компонентов выполняется одной командой из корня проекта:

```bash
docker compose up -d
```

Регистрация коннектора Debezium для захвата данных CRM:

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @debezium/crm-connector.json
```

Проверить статус всех запущенных сервисов:

```bash
docker compose ps
```

Остановить проект:

```bash
docker compose down
```

---

### 7.2. Учётные данные и пользователи по умолчанию

#### 1. Административная панель Keycloak (`http://localhost:8080`):

- **Логин**: `admin`
- **Пароль**: `admin`
- **Реалм**: `reports-realm`

#### 2. Пользователи Keycloak (роль `prothetic_user` — доступ к отчётам протезов):

| Логин        | Пароль         | Роль             | Описание                      |
| ------------ | -------------- | ---------------- | ----------------------------- |
| `prothetic1` | `prothetic123` | `prothetic_user` | Пилот протеза (Hand Elite v2) |
| `prothetic2` | `prothetic123` | `prothetic_user` | Пилот протеза (Arm Ultra v1)  |
| `prothetic3` | `prothetic123` | `prothetic_user` | Пилот протеза (Hand Elite v2) |
| `admin1`     | `admin123`     | `administrator`  | Администратор платформы       |
| `user1`      | `password123`  | `user`           | Обычный пользователь          |

#### 3. База данных CRM (`crm_db`):

- **Порт**: `5434`
- **База**: `crm_db`
- **Пользователь**: `crm_user`
- **Пароль**: `crm_password`

#### 4. Пользователи зарубежного представительства (OpenLDAP `dc=example,dc=com`):

| Логин (uid)    | Пароль     | LDAP-группа / Синхронизированная роль | Описание                               |
| -------------- | ---------- | ------------------------------------- | -------------------------------------- |
| `john.doe`     | `password` | `cn=prothetic_user`                   | Пилот международного представительства |
| `alex.johnson` | `password` | `cn=prothetic_user`                   | Пилот международного представительства |
| `jane.smith`   | `password` | `cn=user`                             | Обычный пользователь                   |

Администратор OpenLDAP: `cn=admin,dc=example,dc=com` / пароль: `adminpassword`.

#### 5. Внешний провайдер Яндекс ID:

- Доступен вход через кнопку **«Войти с Яндекс ID»** (Identity Brokering).
- Роль `prothetic_user` присваивается автоматически через `yandex-role-mapper`.
- При первом входе запрашивается согласие на обработку данных профиля с сохранением в БД.

---

### 7.3. Проверка через браузер (UI Flow)

1. **Вход в приложение**:
   - Откройте браузер по адресу: **`http://localhost:3000`**
   - Отобразится стартовая страница с кнопками входа: **«Войти через Keycloak SSO»** и **«Войти с Яндекс ID»**.

2. **Аутентификация и двухфакторная проверка (TOTP MFA)**:
   - Нажмите **«Войти через Keycloak SSO»** (произойдёт редирект на Keycloak через BFF с PKCE).
   - Введите логин `prothetic1` и пароль `prothetic123`.
   - При первом входе Keycloak предложит настроить обязательный одноразовый пароль (OTP): отсканируйте QR-код в приложении **Google Authenticator** или **FreeOTP** (либо введите секретный ключ) и подтвердите код.
   - При последующих входах система запрашивает 6-значный одноразовый код.

3. **Запрос и получение отчёта из новой витрины**:
   - После успешного входа фронтенд получает защищённую сессию (`BIONIC_SESSION` HttpOnly cookie) без передачи JWT-токенов в браузер.
   - На странице отображаются имя пользователя (`prothetic1`) и форма выбора периода отчёта (по умолчанию: с `2026-10-01` по `2026-10-16`).
   - Нажмите **«Получить и скачать отчёт»**:
     - Сервис проверит наличие отчёта в S3. При первом запросе отчёт генерируется из новой витрины `v_user_prosthetic_reports_datamart` (ClickHouse), загружается в S3 и возвращается пользователю (бейдж `🔄 OLAP GENERATED & S3 CACHED`).
     - Автоматически скачивается JSON-файл отчёта `bionicpro-report-PROSTH-ALPHA-01-...json`.
     - На странице отображается прямая ссылка на раздачу через CDN (`http://localhost:8082/bionicpro-reports/reports/prothetic1/...`).
   - Нажмите **«Получить и скачать отчёт»** повторно с теми же датами:
     - Запрос моментально отдаётся из S3-хранилища/CDN без обращения в ClickHouse (зелёный бейдж `⚡ S3 / CDN CACHE HIT`).

4. **Прямое открытие через CDN**:
   - Нажмите кнопку **«Открыть через CDN»** или перейдите по ссылке CDN — статический файл отдаётся через Nginx Reverse Proxy. В сетевой вкладке DevTools виден заголовок `X-Cache-Status: HIT`.

5. **Проверка входа через Яндекс ID**:
   - Нажмите **«Выйти»**, затем выберите **«Войти с Яндекс ID»**.
   - После авторизации сервис запросит согласие: **«Разрешить»** сохраняет профиль в БД и разблокирует получение отчётов.

---

### 7.4. Проверка сервисов через curl / консоль

#### 1. Проверка доступности фронтенда:

```bash
curl -I http://localhost:3000
# Ожидаемый ответ: HTTP/1.1 200 OK
```

#### 2. Проверка сервиса аутентификации bionicpro-auth (BFF):

```bash
curl -i http://localhost:8081/auth/status
# Ожидаемый ответ: HTTP/1.1 401 Unauthorized (сессия отсутствует)
```

#### 3. Проверка здоровья reports-api:

```bash
curl http://localhost:8000/health
# Ожидаемый ответ: {"service":"reports-api","status":"UP"}
```

#### 4. Проверка защиты эндпоинта отчётов (запрет без токена):

```bash
curl -i http://localhost:8000/reports
# Ожидаемый ответ: HTTP/1.1 401 Unauthorized
```

#### 5. Проверка коннектора Debezium CDC:

```bash
curl http://localhost:8083/connectors/crm-postgres-connector/status
# Ожидаемый ответ: {"name":"crm-postgres-connector","connector":{"state":"RUNNING",...},"tasks":[{"state":"RUNNING",...}]}
```

#### 6. Проверка сквозного CDC потока (PostgreSQL CRM -> Kafka -> ClickHouse):

```bash
# 1. Вставка нового клиента в базу данных CRM
docker exec -i architecture-bionicpro-crm_db-1 psql -U crm_user -d crm_db -c \
  "INSERT INTO clients (id, username, client_name, client_email, prosthetic_id, prosthetic_model, last_calibration_date, status) \
   VALUES ('crm-c-999', 'live_pilot', 'Live CDC Pilot', 'pilot@bionicpro.com', 'PROSTH-LIVE-99', 'BionicPRO CyberArm v3', CURRENT_DATE, 'ACTIVE');"

# 2. Проверка сообщения в топике Kafka
docker exec -i architecture-bionicpro-kafka-1 kafka-console-consumer \
  --bootstrap-server kafka:9092 --topic crm_cdc.public.clients --from-beginning --max-messages 10 --timeout-ms 5000

# 3. Проверка автоматической материализации в ClickHouse
curl "http://localhost:8123/?query=SELECT+username,client_name,prosthetic_model+FROM+bionicpro_analytics.crm_clients_replicated+WHERE+username='live_pilot'+FORMAT+JSONEachRow"
# Ожидаемый ответ: {"username":"live_pilot","client_name":"Live CDC Pilot","prosthetic_model":"BionicPRO CyberArm v3"}
```

#### 7. Проверка новой отчётной витрины ClickHouse OLAP:

```bash
curl "http://localhost:8123/?query=SELECT+count()+FROM+bionicpro_analytics.v_user_prosthetic_reports_datamart"
# Ожидаемый ответ: число строк витрины (например, 7)

curl "http://localhost:8123/?query=SELECT+username,prosthetic_model,report_date,total_active_seconds+FROM+bionicpro_analytics.v_user_prosthetic_reports_datamart+FORMAT+JSONEachRow"
```

#### 8. Проверка объектного хранилища S3 (MinIO) и CDN (Nginx):

MinIO не публикуется на хост, а CDN отдаёт объект только по подписанной ссылке (`nginx secure_link`).
Ссылку выдаёт `reports-api` в поле `cdnUrl` после проверки JWT и того, что отчёт принадлежит владельцу токена.
Подпись `md5=base64url(md5("<expires><uri> <CDN_SECURE_LINK_SECRET>"))` действует `CDN_LINK_TTL_SECONDS` (300 с).

```bash
# 1. Прямой путь без подписи (в том числе чужой) -> 403 Forbidden
curl -i "http://localhost:8082/bionicpro-reports/reports/prothetic1/report_2026-10-01_2026-10-16.json"

# 2. Подписанная ссылка из ответа GET /reports (поле cdnUrl) -> 200, X-Cache-Status: MISS, затем HIT
curl -i "<cdnUrl из ответа reports-api>"

# 3. Подмена username в пути подписанной ссылки -> 403 (подпись не совпадает с путём)
# 4. Ссылка после истечения expires -> 410 Gone
```

#### 9. Проверка пользователей в OpenLDAP:

```bash
docker exec -i architecture-bionicpro-openldap-1 ldapsearch -x -H ldap://localhost:389 \
  -b "dc=example,dc=com" -D "cn=admin,dc=example,dc=com" -w adminpassword "(uid=john.doe)"
# Ожидаемый ответ: запись пользователя John Doe с принадлежностью к группе prothetic_user
```
