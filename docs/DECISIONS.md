# RecoverSense — Architecture Decision Records

## ADR-001 — AI is not the financial authorization layer
**Status:** Accepted

AI diagnoses/proposes. Deterministic policy controls execution.

## ADR-002 — Verification is mandatory before recovery is counted
**Status:** Accepted

A provider write response is not sufficient evidence of business recovery.

## ADR-003 — One modular Spring Boot application
**Status:** Accepted

The dataset is demo-scale. A modular monolith reduces integration and deployment risk.

## ADR-004 — No RAG/vector DB
**Status:** Accepted

Payment context is structured transactional data, not an unstructured retrieval problem.

## ADR-005 — No multi-agent orchestration
**Status:** Accepted

One agent/diagnosis component plus deterministic services is clearer and safer for the MVP.

## ADR-006 — Simulation only where test infrastructure lacks the needed state transition
**Status:** Accepted

Simulator must be deterministic and visibly labeled.

## ADR-007 — Current feature-complete target is September 4, 2026
**Status:** Accepted

This supersedes the historical September 2 date in the earlier pre-build specification.

## ADR-008 — Project renamed to RecoverSense
**Status:** Accepted

The previous project name was RecoverFlow. RecoverSense is the current permanent name.

## ADR-009 — PostgreSQL exposed on host port 5433
**Status:** Accepted

A native (non-Docker) PostgreSQL Windows service already binds host port 5432, which caused Spring Boot to connect to the wrong instance and fail authentication. We do not stop or modify the native service. `infra/docker-compose.yml` and `spring.datasource.url` use port 5433.

## ADR-010 — Application runs on port 8081
**Status:** Accepted

Host port 8080 is already occupied by a native `httpd.exe` service. We do not stop or modify that service. `server.port=8081`.

## ADR-011 — JVM timezone pinned to Asia/Kolkata
**Status:** Accepted

The local JVM resolved the default timezone as the deprecated alias `Asia/Calcutta`, which PostgreSQL 17 rejects during the JDBC session `SET TimeZone`, breaking Flyway migrations. Fixed via `-Duser.timezone=Asia/Kolkata` in `backend/pom.xml` for both `maven-surefire-plugin` and `spring-boot-maven-plugin`.
