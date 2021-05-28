package io.rlecomte.fsm

import io.rlecomte.fsm.store.Version

import java.time.Instant

case class Event(
    id: EventId,
    runId: RunId,
    seqNum: Version,
    timestamp: Instant,
    payload: WorkflowEvent
)
