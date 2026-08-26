# RecoverSense — Frontend Specification

The uploaded frontend mockup is a visual reference, not an implementation contract.

## Navigation

Workspace:
- Overview
- At-risk payments
- Recovery cases
- Audit trail
- Metrics

System:
- Policy rules
- Integrations
- Settings

## Overview

Show:
- Revenue at risk
- Verified recovered revenue
- Actions verified
- Policy violations
- recovery performance
- strategy mix
- recent recovery cases

## Recovery case table

Columns:
- payment
- amount
- failure
- recommendation/strategy
- policy
- outcome
- view

## Decision panel

For selected case show:
- selected strategy
- diagnosis
- confidence
- explanation
- policy decision
- amount limit
- verification result

## Audit panel

Show chronological events:
- failure detected
- diagnosis
- strategy
- policy
- execution
- verification
- completion

## UI rule

Never make simulated data look like live production data.

Use explicit labels:
- Synthetic
- Test mode
- Simulated

## Visual direction

The reference mockup uses:
- dark sidebar
- light content area
- compact cards
- strong status pills
- dark decision panel
- monospace audit trail

The final UI may improve the visual system while preserving the information hierarchy.
