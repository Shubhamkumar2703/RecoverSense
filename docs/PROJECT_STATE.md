# RecoverSense — Project State

> Last updated: 2026-08-27

## Current phase
INFRASTRUCTURE COMPLETE → DOMAIN MODEL NEXT

## DONE
- Product concept established
- Architecture established
- Policy concept established
- Verification concept established
- Frontend mockup/reference established
- Project renamed to RecoverSense
- Pre-code repository package prepared
- Git initialized
- Backend skeleton created (Spring Boot 4.1.1, Java 21, Maven Wrapper 3.9.16)
- PostgreSQL 17 running via Docker (`recoversense-postgres`, host port 5433)
- Flyway + JPA wired and verified against the real database
- JVM timezone fixed (Asia/Kolkata) for Surefire and spring-boot:run
- Application boots cleanly on port 8081
- `mvnw clean test` → BUILD SUCCESS
- `mvnw spring-boot:run` → starts and serves `/actuator/health`, `/actuator/info`

## IN PROGRESS
- None — infrastructure checkpoint closed

## NEXT
1. Read PRODUCT_SPEC.md, DATA_MODEL.md, and reference mockups
2. Identify core domain entities (payment, subscription, recovery case, diagnosis, policy check, execution, verification, audit event)
3. Define relationships and invariants
4. Write first Flyway migration for the domain schema
5. Add first domain entity + repository + test
6. Only then start service layer (diagnosis → strategy → policy → execution)

## BLOCKED
None currently.

## PARKED
- microservices
- Kafka
- Redis
- Kubernetes
- LangChain/LangGraph
- n8n
- vector DB/RAG
- second gateway
- production UPI/eNACH authentication

## RISKS
- limited development time
- current Razorpay test-mode capability must be verified
- provider state verification
- AI diagnosis reliability
- demo stability

## OPEN QUESTIONS
- exact current Razorpay test-mode endpoints and state transitions
- final API schemas
- exact package naming
- deployment target

## Environment checkpoint

| Component | Value |
|---|---|
| Java | 21 (Temurin) |
| Maven | Wrapper 3.9.16 |
| Spring Boot | 4.1.1 |
| PostgreSQL | 17, container `recoversense-postgres`, host port `5433` |
| Application port | `8081` |
| JVM timezone | `Asia/Kolkata` |
| Flyway | enabled, connection verified |
| Actuator | `/actuator/health`, `/actuator/info` |

Rationale for the non-default ports/timezone is recorded in [DECISIONS.md](DECISIONS.md) (ADR-009, ADR-010, ADR-011).

## Rule

Do not modify infrastructure unless a real blocker is discovered. Infrastructure is green. Focus moves to the domain model.
