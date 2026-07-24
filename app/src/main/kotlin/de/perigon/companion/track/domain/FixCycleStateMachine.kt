package de.perigon.companion.track.domain

import android.location.Location
import de.perigon.companion.track.data.TrackPointEntity

sealed class FixState {
    data object Idle : FixState()
    data class WaitingForFix(
        val attempt: Int = 0,
        val best: TrackPointEntity? = null,
    ) : FixState()
}

sealed class FixEvent {
    data object StartCycle : FixEvent()
    data class FixProvided(val point: TrackPointEntity) : FixEvent()
    data object Timeout : FixEvent()
    data object Cancel : FixEvent()
}

sealed class FixEffect {
    data object RequestGpsFix : FixEffect()
    data class AcceptFix(val point: TrackPointEntity) : FixEffect()
    data class ProvidedFix(val point: TrackPointEntity) : FixEffect()
    data object ScheduleNext : FixEffect()
    data object AcquireFixWakeLock : FixEffect()
    data object ReleaseFixWakeLock : FixEffect()
    data class SkipCycle(val reason: String) : FixEffect()
}

data class FixTransition(
    val state: FixState,
    val effects: List<FixEffect>,
)

fun fixTransition(
    state: FixState,
    event: FixEvent,
    maxInaccuracyM: Float,
    isSingleFixMode: Boolean,
    maxAttempts: Int = 2,
): FixTransition = when (state) {

    is FixState.Idle -> when (event) {
        is FixEvent.StartCycle -> FixTransition(
            state   = FixState.WaitingForFix(attempt = 0, best = null),
            effects = listOf(FixEffect.AcquireFixWakeLock, FixEffect.RequestGpsFix),
        )

        is FixEvent.FixProvided -> {
            val effects = mutableListOf<FixEffect>(FixEffect.ProvidedFix(event.point))
            if (meetsThreshold(event.point, maxInaccuracyM)) {
                effects += FixEffect.AcceptFix(event.point)
            }
            FixTransition(state = FixState.Idle, effects = effects)
        }

        is FixEvent.Timeout -> FixTransition(state = FixState.Idle, effects = emptyList())
        is FixEvent.Cancel  -> FixTransition(state = FixState.Idle, effects = emptyList())
    }

    is FixState.WaitingForFix -> when (event) {
        is FixEvent.FixProvided -> {
            val effects   = mutableListOf<FixEffect>(FixEffect.ProvidedFix(event.point))
            val candidate = betterOf(event.point, state.best)

            if (meetsThreshold(event.point, maxInaccuracyM)) {
                effects += FixEffect.AcceptFix(event.point)
                effects += FixEffect.ReleaseFixWakeLock
                effects += FixEffect.ScheduleNext
                FixTransition(state = FixState.Idle, effects = effects)
            } else if (state.attempt + 1 < maxAttempts) {
                effects += FixEffect.RequestGpsFix
                FixTransition(
                    state   = FixState.WaitingForFix(attempt = state.attempt + 1, best = candidate),
                    effects = effects,
                )
            } else {
                effects += FixEffect.ReleaseFixWakeLock
                effects += FixEffect.SkipCycle(
                    "No acceptable fix after $maxAttempts attempts" +
                    (candidate?.let { ", best accuracy: %.0fm".format(it.accuracyM) } ?: ""),
                )
                effects += FixEffect.ScheduleNext
                FixTransition(state = FixState.Idle, effects = effects)
            }
        }

        is FixEvent.Timeout -> {
            val candidate = state.best
            if (candidate != null && meetsThreshold(candidate, maxInaccuracyM)) {
                FixTransition(
                    state   = FixState.Idle,
                    effects = listOf(FixEffect.AcceptFix(candidate), FixEffect.ReleaseFixWakeLock, FixEffect.ScheduleNext),
                )
            } else if (state.attempt + 1 < maxAttempts) {
                FixTransition(
                    state   = FixState.WaitingForFix(attempt = state.attempt + 1, best = candidate),
                    effects = listOf(FixEffect.RequestGpsFix),
                )
            } else {
                FixTransition(
                    state   = FixState.Idle,
                    effects = listOf(
                        FixEffect.ReleaseFixWakeLock,
                        FixEffect.SkipCycle(
                            "Timeout after $maxAttempts attempts" +
                            (candidate?.let { ", best accuracy: %.0fm".format(it.accuracyM) } ?: ""),
                        ),
                        FixEffect.ScheduleNext,
                    ),
                )
            }
        }

        is FixEvent.StartCycle -> FixTransition(state = state, effects = emptyList())

        is FixEvent.Cancel -> FixTransition(
            state   = FixState.Idle,
            effects = listOf(FixEffect.ReleaseFixWakeLock),
        )
    }
}

private fun meetsThreshold(point: TrackPointEntity, maxInaccuracyM: Float): Boolean =
    point.accuracyM != null && point.accuracyM <= maxInaccuracyM

private fun betterOf(a: TrackPointEntity?, b: TrackPointEntity?): TrackPointEntity? = when {
    a == null -> b
    b == null -> a
    a.accuracyM != null && b.accuracyM != null && a.accuracyM <= b.accuracyM -> a
    a.accuracyM != null && b.accuracyM == null -> a
    else -> b
}
