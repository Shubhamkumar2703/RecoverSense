# RecoverSense — Development Log

Chronological engineering diary. For current state see [PROJECT_STATE.md](PROJECT_STATE.md); for why decisions were made see [DECISIONS.md](DECISIONS.md).

---

## 2026-08-27 — Infrastructure green

### Completed
- Bootstrapped repository, renamed project to RecoverSense.
- Added Spring Boot 4.1.1 backend (Java 21, Maven Wrapper 3.9.16).
- Configured PostgreSQL 17 via Docker (`recoversense-postgres`).
- Added Flyway, JPA, validation, Actuator.
- Verified `mvnw clean test` and `mvnw spring-boot:run` against the real database.

### Problems encountered

**PostgreSQL authentication failure.** Spring Boot's Flyway connection failed with `password authentication failed for user "recoversense"` even after resetting the role's password inside the container and confirming TCP auth manually. Root cause: a native (non-Docker) `postgres.exe` Windows service was also bound to `0.0.0.0:5432`, so `localhost:5432` from the JVM was landing on the wrong Postgres instance, not the container. Fixed by remapping the container to host port 5433 (see ADR-009).

**JVM timezone rejection.** After the port fix, Flyway failed with `FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"`. The JVM's default timezone resolved to the deprecated alias `Asia/Calcutta`, which PostgreSQL 17's tzdata doesn't recognize. Fixed by pinning `-Duser.timezone=Asia/Kolkata` in `backend/pom.xml` for both Surefire (tests) and the Spring Boot Maven plugin (`spring-boot:run`) — MAVEN_OPTS alone doesn't reach Surefire's forked JVM (see ADR-011).

**Application port conflict.** Port 8080 was already occupied by a native `httpd.exe` service. Moved the app to port 8081 (see ADR-010).

### Verification
- `mvnw clean test` → BUILD SUCCESS
- `mvnw spring-boot:run` → `Started RecoversenseBackendApplication`, serving on `http://localhost:8081`

### Next
Begin domain model derivation from `docs/PRODUCT_SPEC.md`, `docs/DATA_MODEL.md`, and the reference mockups.
