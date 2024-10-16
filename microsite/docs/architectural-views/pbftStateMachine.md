---
sidebar_position: 9
---

# PBFT Request State Machine

## State Machine Diagram

![State Machine Diagram](./assets/pbftSM.svg) 

This diagram illustrates the state machine of a PBFT request. Each request
that is processed by the PBFT algorithm goes through the following states:

- **Pre-Prepare Phase**: In this phase, the primary assigns a sequence number to the request and sends a `PRE-PREPARE` message to all replicas.
- **Prepare Phase**: In this phase, each replica sends a `PREPARE` message to all replicas to confirm that they all agree on the sequence number.
- **Commit Phase**: In this phase, each replica sends a `COMMIT` message to all replicas to confirm that the actual message needs to be executed.
