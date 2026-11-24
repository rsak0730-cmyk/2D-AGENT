"""Database-backed task queue worker with dependency checks and leased execution.

Run as a separate process with ``python -m nova_api.queue_worker``. Each task is claimed
through the same atomic lease as the HTTP execution endpoint; stale leases are recoverable.
"""
from __future__ import annotations

import asyncio
import logging
import os
from datetime import datetime, timezone
from sqlalchemy.orm import sessionmaker

from .db import SessionLocal
from .models import ApiKey, Meeting, ResourceSetting, Task
from .workforce import execute_task, log_event

logger = logging.getLogger("nova.queue_worker")


def _eligible_for_worker(task: Task, now: datetime) -> bool:
    if task.attempt_count >= task.max_attempts:
        return False
    if task.state == "FAILED":
        retry_at = task.next_retry_at
        return retry_at is None or retry_at.replace(tzinfo=timezone.utc) <= now
    if task.state in {"RECEIVED", "BLOCKED"}:
        return True
    if task.state in {"PLANNING", "ASSIGNED", "RUNNING"}:
        expires = task.lease_expires_at
        return expires is None or expires.replace(tzinfo=timezone.utc) <= now
    return False


def _dependencies_state(db, task: Task) -> tuple[bool, bool]:
    """Return (ready, permanently blocked) for same-owner prerequisites."""
    if not task.dependencies:
        return True, False
    rows = db.query(Task).filter(Task.owner_key_id == task.owner_key_id, Task.id.in_(task.dependencies)).all()
    by_id = {row.id: row for row in rows}
    if len(by_id) != len(set(task.dependencies)):
        return False, True
    states = [by_id[dependency].state for dependency in task.dependencies]
    if any(state in {"FAILED", "CANCELLED", "BLOCKED"} for state in states):
        return False, True
    return all(state == "COMPLETED" for state in states), False


async def run_worker_once(session_factory=SessionLocal, owner_key_id: str | None = None) -> int:
    """Process one available wave, parallel across owners and within each planned task.

    ``owner_key_id`` is an optional filter useful for isolated deployments and tests.
    """
    db = session_factory()
    try:
        now = datetime.now(timezone.utc)
        query = db.query(Task).filter(Task.owner_key_id.isnot(None), Task.state.in_(["RECEIVED", "FAILED", "BLOCKED", "PLANNING", "ASSIGNED", "RUNNING"]))
        if owner_key_id is not None:
            query = query.filter(Task.owner_key_id == owner_key_id)
        candidates = query.order_by(Task.priority.asc(), Task.created_at.asc()).limit(500).all()
        by_owner: dict[str, list[Task]] = {}
        for task in candidates:
            ready, blocked = _dependencies_state(db, task)
            if blocked:
                if task.state != "BLOCKED":
                    task.state = "BLOCKED"
                    task.error_detail = "A task dependency failed, was cancelled, or is no longer available. Complete/recover its prerequisites before retrying."
                    task.lease_owner = None; task.lease_expires_at = None
                    db.commit()
                    log_event(db, task.owner_key_id, "task.blocked", task.id, payload={"dependencies":task.dependencies})
                continue
            if task.state == "BLOCKED" and ready:
                task.state = "RECEIVED"; task.error_detail = None; task.next_retry_at = None
                db.commit(); log_event(db, task.owner_key_id, "task.unblocked", task.id, payload={"dependencies":task.dependencies})
            if not ready or not _eligible_for_worker(task, now):
                continue
            by_owner.setdefault(task.owner_key_id, []).append(task)

        claims: list[tuple[str, str]] = []
        for owner_id, tasks in by_owner.items():
            resources = db.query(ResourceSetting).filter(ResourceSetting.owner_key_id == owner_id).first()
            limit = min(resources.max_concurrency, resources.max_active_tasks) if resources else 2
            active = db.query(Task).filter(Task.owner_key_id == owner_id, Task.state.in_(["PLANNING", "ASSIGNED", "RUNNING"]), Task.lease_expires_at > now).count()
            available = max(0, limit - active)
            for task in tasks[:available]:
                claims.append((owner_id, task.id))
        db.commit()
    finally:
        db.close()

    async def execute_one(owner_id: str, task_id: str):
        session = session_factory()
        try:
            owner = session.query(ApiKey).filter(ApiKey.id == owner_id, ApiKey.revoked.is_(False)).first()
            if owner is None:
                return
            await execute_task(task_id, session, owner)
        except Exception as error:
            # The execution endpoint persists task/assignment failure details. Keep the
            # worker loop alive; log only an exception class and task UUID, never secrets.
            logger.warning("Task %s attempt ended with %s", task_id, type(error).__name__)
        finally:
            session.close()

    if claims:
        await asyncio.gather(*(execute_one(owner_id, task_id) for owner_id, task_id in claims))
    return len(claims)


async def run_scheduled_meetings_once(session_factory=SessionLocal) -> int:
    """Start due, explicitly scheduled meetings; per-meeting DB CAS prevents duplicates."""
    from .collaboration import start_meeting
    db=session_factory()
    try:
        now=datetime.now(timezone.utc)
        due=db.query(Meeting.id,Meeting.owner_key_id).filter(Meeting.status.in_(["scheduled","invited"]),Meeting.scheduled_at.isnot(None),Meeting.scheduled_at<=now).order_by(Meeting.scheduled_at.asc()).limit(100).all()
        targets=[(meeting_id,owner_id) for meeting_id,owner_id in due]
    finally:db.close()
    started=0
    for meeting_id,owner_id in targets:
        session=session_factory()
        try:
            owner=session.query(ApiKey).filter(ApiKey.id==owner_id,ApiKey.revoked.is_(False)).first()
            if owner is None:continue
            await start_meeting(meeting_id,session,owner)
            started+=1
        except Exception as error:
            logger.warning("Scheduled meeting %s ended with %s",meeting_id,type(error).__name__)
        finally:session.close()
    return started


async def run_forever(poll_seconds: float = 2.0, session_factory=SessionLocal) -> None:
    async def task_loop():
        while True:
            processed=await run_worker_once(session_factory)
            if processed==0:await asyncio.sleep(poll_seconds)
    async def meeting_loop():
        while True:
            await run_scheduled_meetings_once(session_factory)
            await asyncio.sleep(max(1.0,poll_seconds))
    await asyncio.gather(task_loop(),meeting_loop())


def main() -> None:
    logging.basicConfig(level=os.environ.get("NOVA_LOG_LEVEL", "INFO"))
    try:
        asyncio.run(run_forever(float(os.environ.get("NOVA_QUEUE_POLL_SECONDS", "2"))))
    except KeyboardInterrupt:
        logger.info("Queue worker stopped")


if __name__ == "__main__":
    main()
