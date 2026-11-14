FROM python:3.13-alpine
WORKDIR /app
COPY server.py .
ENV PORT=8080 PYTHONUNBUFFERED=1
EXPOSE 8080
USER nobody
CMD ["python", "server.py"]
