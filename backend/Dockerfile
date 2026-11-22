FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv/nova
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY nova_api ./nova_api
EXPOSE 8000
CMD ["uvicorn", "nova_api.main:app", "--host", "0.0.0.0", "--port", "8000"]
