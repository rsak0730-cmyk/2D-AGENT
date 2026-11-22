from datetime import datetime
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from backend.nova_api.db import Base, get_db
from backend.nova_api.main import app
from backend.nova_api.auth import digest
from backend.nova_api.models import ApiKey

engine = create_engine("sqlite://", connect_args={"check_same_thread":False}, poolclass=StaticPool)
TestingSession = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
Base.metadata.create_all(engine)
def override_db():
    db = TestingSession()
    try: yield db
    finally: db.close()
app.dependency_overrides[get_db] = override_db
client = TestClient(app)

def auth_key(scopes):
    raw = "test_" + str(datetime.now().timestamp())
    db = TestingSession(); row = ApiKey(name="test", key_hash=digest(raw), scopes=scopes); db.add(row); db.commit(); db.close()
    return {"Authorization": f"Bearer {raw}"}
