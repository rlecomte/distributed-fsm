package io.rlecomte.fsm

import java.time.Instant
import io.rlecomte.fsm.store.SeqNum

case class Event(
    id: EventId,
    runId: RunId,
    seqNum: SeqNum,
    timestamp: Instant,
    payload: WorkflowEvent
)
