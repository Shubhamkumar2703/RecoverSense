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
