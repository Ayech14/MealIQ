"""Observer pattern for plan-relevant changes (D06).

ProfileService and PantryService are the concrete publishers. Observers react with
fast, deterministic work only; LLM adaptation is started later by the user.
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

from mealiq.domain import ChangeEventType

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class PlanRelevantChange:
    type: ChangeEventType
    profile_id: UUID
    details: dict[str, Any] = field(default_factory=dict)
    occurred_at: datetime = field(default_factory=lambda: datetime.now(UTC))


class PlanImpactObserver(ABC):
    @abstractmethod
    def on_plan_relevant_change(self, event: PlanRelevantChange) -> None: ...


class PlanRelevantChangePublisher(ABC):
    """Publisher role. Stage 1 draws this as an interface realized by both services;
    here it is an abstract base class that also holds the shared subscriber list, so
    the two services do not duplicate the same three methods.
    """

    def __init__(self) -> None:
        self._observers: list[PlanImpactObserver] = []

    def subscribe(self, observer: PlanImpactObserver) -> None:
        if observer not in self._observers:
            self._observers.append(observer)

    def unsubscribe(self, observer: PlanImpactObserver) -> None:
        if observer in self._observers:
            self._observers.remove(observer)

    def publish(self, event: PlanRelevantChange) -> None:
        """Notify every observer. The change is already saved when this runs, so one
        failing observer is logged and does not stop the others or undo the update."""
        for observer in list(self._observers):
            try:
                observer.on_plan_relevant_change(event)
            except Exception:
                log.exception("Observer %s failed on %s", type(observer).__name__, event.type.value)
