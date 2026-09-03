FROM python:3.14.7-alpine3.24@sha256:c6ead215bfd31f1e433d968853b7a769989117115b728874824e6c0a27cb96fc
RUN apk upgrade --no-cache
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /opt/analyzers/tree-sitter
COPY analyzer-contract/schemas/v1 /opt/analyzer-contract/schemas/v1
COPY analyzers/shared /opt/analyzers/shared
COPY analyzers/tree-sitter /opt/analyzers/tree-sitter
RUN pip install --no-cache-dir --only-binary=:all: -r requirements.txt && chmod 0555 semantic-analyzer && ln -s /opt/analyzers/tree-sitter/semantic-analyzer /usr/local/bin/semantic-analyzer
USER 65532:65532
ENTRYPOINT ["semantic-analyzer"]
