# Minimal image running ollamazure (https://github.com/sinedied/ollamazure), a local emulator of
# the Azure OpenAI wire protocol that forwards to an Ollama backend. Built on-the-fly by
# Testcontainers (see OllamazureContainer); ollamazure is a pure-JS npm package, so this is a small,
# quick-to-build layer on top of a Node base image.
FROM node:26-alpine

# Pin the version so test behavior is reproducible; bump deliberately.
RUN npm install -g ollamazure@1.4.1

EXPOSE 4041

# Bind to all interfaces so the mapped port is reachable from the host. The Ollama backend URL and
# the -d/--use-deployment flag are supplied as the command by OllamazureContainer.
ENTRYPOINT ["ollamazure", "--yes", "--host", "0.0.0.0"]
