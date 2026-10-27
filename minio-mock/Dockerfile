FROM alpine:latest
RUN apk add --no-cache python3
WORKDIR /app
COPY server.py .
EXPOSE 9000
VOLUME ["/data"]
ENTRYPOINT ["python3", "server.py"]
