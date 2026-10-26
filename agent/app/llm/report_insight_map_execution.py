"""Bound independent MAP batches, preserve order, and drain every started call."""

from concurrent.futures import ThreadPoolExecutor
from threading import Lock

from app.core.errors import AgentError


def _cancelled_before_call(error):
    return (
        isinstance(error, AgentError)
        and error.code == "PROVIDER_UNAVAILABLE"
        and isinstance(error.details, dict)
        and error.details.get("pipelineCancelled") is True
        and error.details.get("requestNotStarted") is True
    )


def run_report_maps(items, evaluate, *, concurrency, cancel_pending):
    """Each worker owns a draft plus its repair; no REVIEW overlaps MAP work."""
    if concurrency <= 1 or len(items) <= 1:
        return [evaluate(index, item) for index, item in enumerate(items)]
    failures = []
    interrupted = None
    lock = Lock()

    def run(index, item):
        with lock:
            if failures:
                return None  # Queued work never reaches provider admission.
        try:
            return evaluate(index, item)
        except BaseException as error:
            with lock:
                if not failures:
                    failures.append(error)
                    cancel_pending()
                elif _cancelled_before_call(failures[0]) and not _cancelled_before_call(error):
                    # A sibling can observe pipeline cancellation before the
                    # original provider failure reaches this worker boundary.
                    failures[0] = error
            raise

    # Exiting this context waits for admitted requests to settle. Their usage
    # must reach the shared ledger before the service annotates a failure.
    with ThreadPoolExecutor(max_workers=min(3, concurrency, len(items))) as executor:
        futures = [executor.submit(run, index, item) for index, item in enumerate(items)]
        try:
            outputs = [future.result() for future in futures]
        except BaseException as error:
            interrupted = error
            cancel_pending()
            for future in futures:
                future.cancel()
    if failures:
        raise failures[0]
    if interrupted is not None:
        raise interrupted
    return outputs
