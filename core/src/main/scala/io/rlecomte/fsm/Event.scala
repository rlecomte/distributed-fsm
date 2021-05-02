package io.rlecomte.fsm

import java.time.Instant
import io.rlecomte.fsm.store.Version

case class Event(
    id: EventId,
    runId: RunId,
    seqNum: Version,
    timestamp: Instant,
    payload: WorkflowEvent
)
