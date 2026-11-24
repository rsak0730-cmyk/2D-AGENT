"""PostgreSQL LISTEN/NOTIFY fan-out for persisted owner-scoped activity events."""
from __future__ import annotations

import asyncio
import json
import logging
from sqlalchemy.engine.url import make_url

import psycopg
from sqlalchemy.orm import Session

from .db import SessionLocal
from .models import ActivityEvent

logger=logging.getLogger("nova.event_bus")


class PostgresEventBus:
    def __init__(self,database_url:str):
        parsed=make_url(database_url)
        self.enabled=parsed.drivername.startswith("postgresql")
        self.dsn=parsed.set(drivername="postgresql").render_as_string(hide_password=False) if self.enabled else ""
        self.node_id=__import__("uuid").uuid4().hex
        self._local_seen:dict[str,None]={}

    def mark_local(self,event_id:str|None):
        if event_id:
            self._local_seen[event_id]=None
            if len(self._local_seen)>4096:self._local_seen.pop(next(iter(self._local_seen)))

    def was_seen_locally(self,event_id:str)->bool:
        return event_id in self._local_seen

    async def listen(self,callback):
        if not self.enabled:return
        while True:
            try:
                async with await psycopg.AsyncConnection.connect(self.dsn,autocommit=True) as connection:
                    await connection.execute("LISTEN nova_events")
                    async for notification in connection.notifies():
                        try:
                            payload=json.loads(notification.payload)
                            if payload.get("node")==self.node_id:continue
                            event_id=payload.get("id")
                            if not isinstance(event_id,str) or self.was_seen_locally(event_id):continue
                            event=await asyncio.to_thread(self._load_event,event_id)
                            if event is not None:await callback(event)
                        except Exception as error:
                            logger.warning("Shared event delivery failed (%s)",type(error).__name__)
            except asyncio.CancelledError:
                raise
            except Exception as error:
                logger.warning("PostgreSQL event listener disconnected (%s); retrying",type(error).__name__)
                await asyncio.sleep(1.0)

    @staticmethod
    def _load_event(event_id:str):
        db:Session=SessionLocal()
        try:
            row=db.query(ActivityEvent).filter(ActivityEvent.id==event_id).first()
            if row is None:return None
            return {"owner_key_id":row.owner_key_id,"type":row.event_type,"task_id":row.task_id,
                    "agent_id":row.agent_id,"event_id":row.id,"created_at":row.created_at.isoformat(),"payload":row.payload or {}}
        finally:db.close()
