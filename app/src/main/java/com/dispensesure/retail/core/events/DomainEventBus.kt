package com.dispensesure.retail.core.events

import com.dispensesure.retail.core.utils.logger.AppLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide bus for [DomainEvent] emissions.
 *
 * Description:
 * A single `@Singleton` funnel a flow posts pharmacy facts to — a dispense completed, a bottle
 * recorded — without knowing or caring who consumes them. Consumers subscribe to [events].
 *
 * What it does:
 * - Exposes [tryPost] as a non-blocking, non-throwing publish path, so an emitting flow is never
 *   slowed down or broken by a consumer. A producer on a database or HL7 path must be able to
 *   announce what it did and move on.
 * - Exposes [events] as a hot `SharedFlow` with no replay: a consumer sees what happens after it
 *   subscribes, never a backlog from before it started.
 * - Buffers [BUFFER_CAPACITY] events with `DROP_OLDEST`, so a stalled consumer costs an undercount
 *   of the oldest events rather than back-pressure on the pharmacy flows.
 *
 * With no subscriber attached, [tryPost] still succeeds and the event is simply discarded.
 */
@Singleton
class DomainEventBus @Inject constructor() {

    private val logger = AppLogger.create<DomainEventBus>()

    private val _events = MutableSharedFlow<DomainEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Public read-only stream of domain events. */
    val events: SharedFlow<DomainEvent> = _events.asSharedFlow()

    /**
     * Announces [event] without suspending.
     *
     * Safe to call from any thread and from any flow, including inside a database transaction:
     * it never suspends, never throws, and never waits for a consumer.
     *
     * @param event The fact that just happened.
     * @return true if the event was accepted into the buffer.
     */
    fun tryPost(event: DomainEvent): Boolean {
        val accepted = _events.tryEmit(event)
        if (!accepted) logger.w("Domain event dropped: $event")
        return accepted
    }

    private companion object {
        /**
         * Room for a burst of events while a consumer is busy. A stock count writes one event per
         * bottle line, so a fast operator can post several in quick succession.
         */
        const val BUFFER_CAPACITY = 64
    }
}
