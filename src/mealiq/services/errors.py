"""Errors raised by application services and shown to the user by the GUI and CLI."""


class ServiceError(Exception):
    """Base class for expected, user-facing service failures."""


class NotFoundError(ServiceError):
    pass


class InvalidInputError(ServiceError):
    """Rejected input, with one message per field so the interface can show it in place."""

    def __init__(self, errors: dict[str, str]) -> None:
        self.errors = errors
        super().__init__("; ".join(f"{name}: {message}" for name, message in errors.items()))
