"""Declarative Liquibase interpretation over a prevalidated, bounded file inventory."""

from pathlib import PurePosixPath
import posixpath

from changelog_parser import Mark, ChangelogError, parse_document


def truth(value):
    return value is True or isinstance(value, str) and value.lower() == "true"


class Changelogs:
    def __init__(self, run, sources, factory):
        self.run, self.sources, self.factory = run, sources, factory
        self.extractors = {}
        self.documents = {}
        self.done, self.active, self.failed = set(), [], set()
        self.references = set()
        self.reference_graph = {}
        self.include_count = 0

    def extractor(self, path):
        if path not in self.extractors:
            source = self.sources[path]
            self.extractors[path] = self.factory(self.run, source)
            self.run.fact("FILE", "file:" + path, path, "", source.evidence(), "STATIC_EXACT", 1.0,
                          language=PurePosixPath(path).suffix[1:].upper())
        return self.extractors[path]

    def warning(self, path, mark, message, code="LIQUIBASE_PARTIAL"):
        self.run.unknown.add(code)
        self.run.diagnostic(code, message, path, mark.region.evidence()["startLine"], severity="ERROR" if code.endswith("PARSE_ERROR") else "WARNING")

    def resolve(self, path, value, kind, quiet=False):
        name = value.scalar("file" if kind == "include" else "path")
        if not isinstance(name, str) or not name or "${" in name:
            if not quiet:
                self.warning(path, value, "Include requires a literal path", "INCLUDE_REJECTED")
            return None
        name = name.replace("\\", "/")
        base = str(PurePosixPath(path).parent) if truth(value.scalar("relativeToChangelogFile")) else "."
        candidate = posixpath.normpath(posixpath.join(base, name))
        component = self.run.root.relative_to(self.run.workspace).as_posix()
        outside_component = component != "." and not (candidate == component or candidate.startswith(component + "/"))
        if ":" in name or name.startswith("/") or candidate == ".." or candidate.startswith("../") or outside_component:
            if not quiet:
                self.warning(path, value, "Include is outside the admitted component", "INCLUDE_REJECTED")
            return None
        return candidate

    def targets(self, path, kind, value, quiet=False):
        candidate = self.resolve(path, value, kind, quiet)
        if candidate is None:
            return []
        if kind != "includeAll":
            if candidate not in self.sources:
                if not quiet:
                    self.warning(path, value, "Included file was absent, excluded, oversized, invalid, or a rejected symlink: " + candidate, "INCLUDE_UNAVAILABLE")
                return []
            return [candidate]
        if value.get("filter") or value.get("resourceComparator"):
            if not quiet:
                self.warning(path, value, "Custom includeAll filters/comparators are not executed", "INCLUDE_REJECTED")
            return []
        suffix = value.scalar("endsWithFilter", "")
        minimum, maximum = value.scalar("minDepth", 1), value.scalar("maxDepth", 32)
        try:
            minimum, maximum = int(minimum), int(maximum)
        except (ValueError, TypeError):
            minimum, maximum = -1, -1
        if not isinstance(suffix, str) or not 1 <= minimum <= maximum <= 64:
            if not quiet:
                self.warning(path, value, "Invalid includeAll depth/filter", "INCLUDE_REJECTED")
            return []
        prefix = candidate.rstrip("/") + "/" if candidate != "." else ""
        values = [item for item in sorted(self.sources) if item.startswith(prefix) and item.endswith(suffix)
                  and minimum <= len(item[len(prefix):].split("/")) <= maximum]
        if not values and not quiet:
            self.warning(path, value, "includeAll matched no admitted files", "INCLUDE_UNAVAILABLE")
        return values

    def scan_references(self, path, mark):
        pending = [mark]
        references = set()
        while pending:
            item = pending.pop()
            if isinstance(item.value, dict):
                for key, value in item.value.items():
                    if key in {"include", "includeAll", "sqlFile"} and isinstance(value.value, dict):
                        references.update(self.targets(path, key, value, quiet=True))
                    pending.append(value)
            elif isinstance(item.value, list):
                pending.extend(item.value)
        self.reference_graph[path] = references
        self.references.update(references)

    def reachable(self, roots):
        seen, pending = set(), list(roots)
        while pending:
            path = pending.pop()
            if path not in seen:
                seen.add(path)
                pending.extend(self.reference_graph.get(path, ()))
        return seen

    def run_all(self):
        for path, source in sorted(self.sources.items()):
            if path.lower().endswith(".sql"):
                continue
            if "databasechangelog" not in source.text.lower() and "changelog" not in PurePosixPath(path).name.lower():
                self.run.counts["filesDiscovered"] -= 1
                continue
            try:
                document = parse_document(source)
                if not document.get("databaseChangeLog"):
                    self.run.counts["filesDiscovered"] -= 1
                    continue
                self.documents[path] = document
                self.scan_references(path, document)
            except (ChangelogError, ValueError) as error:
                self.failed.add(path)
                self.done.add(path)
                self.warning(path, Mark(None, self.extractor(path).region), str(error), "CHANGELOG_PARSE_ERROR")
        roots = sorted(set(self.documents) - self.references)
        for path in roots:
            self.visit(path)
        reachable = self.reachable(roots)
        for path in sorted(self.documents):
            if path not in reachable:
                self.visit(path)
                reachable.update(self.reachable([path]))
        for path in sorted(self.sources):
            if path.lower().endswith(".sql") and path not in self.references:
                self.visit(path)
        for path in self.done:
            extractor = self.extractors.get(path)
            if path in self.failed or extractor and extractor.failed:
                self.run.counts["filesFailed"] += 1
            else:
                self.run.counts["filesParsed"] += 1
        self.run.counts["filesSkipped"] += len((self.references & set(self.sources)) - self.done)

    def visit(self, path, context=None, scope=None):
        self.run.tick()
        if path in self.active:
            ex = self.extractor(path)
            self.warning(path, Mark(None, ex.region), "Changelog include cycle: " + " -> ".join(self.active + [path]), "INCLUDE_CYCLE")
            return
        if path in self.done:
            return
        if len(self.active) >= 32 or self.include_count >= 2048:
            self.warning(path, Mark(None, self.extractor(path).region), "Include depth/reference limit reached", "INCLUDE_LIMIT")
            return
        self.include_count += 1
        self.active.append(path)
        ex = self.extractor(path)
        try:
            with ex.contextual(scope, context or {}):
                if path.lower().endswith(".sql"):
                    ex.sql(ex.source.text)
                else:
                    if path not in self.documents:
                        self.documents[path] = parse_document(self.sources[path])
                    changes = self.documents[path].get("databaseChangeLog")
                    if not changes or not isinstance(changes.value, list):
                        raise ChangelogError("databaseChangeLog must be an array of changes")
                    for change in changes.value:
                        self.change(path, change)
        except (ChangelogError, ValueError) as error:
            self.failed.add(path)
            self.warning(path, Mark(None, ex.region), str(error), "CHANGELOG_PARSE_ERROR")
        finally:
            self.active.pop()
            self.done.add(path)

    def format(self, path):
        return "LIQUIBASE_" + PurePosixPath(path).suffix[1:].upper()

    def emit(self, path, mark, kind, key, name, owner, **properties):
        ex = self.extractor(path)
        return ex.emit(kind, key, str(name), owner, mark.region.evidence(), origin="FRAMEWORK_DERIVED", confidence=0.95,
                       sourceFormat=self.format(path), declarationStatus="DECLARED", **properties)

    def edge(self, path, mark, source, target, kind):
        self.run.relation(source, target, kind, mark.region.evidence(), "FRAMEWORK_DERIVED", 0.95)

    def table(self, path, mark, name="tableName", schema="schemaName"):
        table = mark.scalar(name)
        namespace = mark.scalar(schema)
        if not isinstance(table, str) or not table or "${" in table or namespace is not None and (not isinstance(namespace, str) or "${" in namespace):
            raise ChangelogError(f"{name} and {schema} require literal identifiers")
        return "db:table:" + (namespace + "." if namespace else "") + table

    def rule(self, path, mark, owner, kind, ownership="DATABASE_CONSTRAINT", **properties):
        key = self.extractor(path).unique(owner, "liquibase-" + kind.lower())
        self.emit(path, mark, "DATA_RULE", key, properties.get("constraintName") or kind, owner,
                  ruleType=kind, ownership=ownership, **properties)
        self.edge(path, mark, owner, key, "OWNED_BY")
        return key

    def columns(self, mark):
        columns = mark.get("columns")
        if not columns:
            return []
        if not isinstance(columns.value, list):
            raise ChangelogError("columns must be an array")
        result = []
        for item in columns.value:
            column = item.get("column")
            if not column or not isinstance(column.value, dict):
                raise ChangelogError("Each columns entry must contain column")
            result.append(column)
        return result

    def column(self, path, mark, table):
        name = mark.scalar("name")
        if not isinstance(name, str) or not name or "${" in name:
            raise ChangelogError("Column name must be literal")
        key = table.replace("db:table:", "db:column:", 1) + "." + name
        constraints = mark.get("constraints")
        nullable = constraints.scalar("nullable", True) if constraints else True
        nullable = truth(nullable) and not (constraints and truth(constraints.scalar("primaryKey")))
        self.emit(path, mark, "COLUMN", key, name, table, dataType=mark.scalar("type"), nullable=bool(nullable), tableKey=table)
        for field in ("defaultValue", "defaultValueNumeric", "defaultValueBoolean", "defaultValueDate", "defaultValueComputed", "defaultValueSequenceNext"):
            value = mark.get(field)
            if value:
                self.rule(path, value, key, "DEFAULT", "DATABASE_DEFAULT", value=value.plain(),
                          expression=value.plain() if field == "defaultValueComputed" else None, valueKind=field)
        if truth(mark.scalar("autoIncrement")):
            self.rule(path, mark, key, "IDENTITY", "DATABASE_GENERATED", startWith=mark.scalar("startWith"), incrementBy=mark.scalar("incrementBy"))
        if truth(mark.scalar("computed")):
            self.warning(path, mark, "Computed column syntax depends on the database/type expression", "LIQUIBASE_COMPUTED_COLUMN")
        if constraints:
            for flag, kind in (("primaryKey", "PRIMARY"), ("unique", "UNIQUE")):
                if truth(constraints.scalar(flag)):
                    self.rule(path, constraints, key, kind, constraintName=constraints.scalar(flag + "Name"))
            if not truth(nullable):
                self.rule(path, constraints, key, "NOTNULL")
            if constraints.scalar("references"):
                self.warning(path, constraints, "Legacy references strings require dialect interpretation; no foreign target is invented", "LIQUIBASE_REFERENCE_SYNTAX")
        return key

    def applicable(self, mark):
        dbms = mark.scalar("dbms")
        if dbms is None:
            return True
        if not isinstance(dbms, str) or "${" in dbms:
            return None
        values = {item.strip().lower() for item in dbms.split(",")}
        if "!postgresql" in values or "none" in values:
            return False
        positive = {item for item in values if not item.startswith("!")}
        return not positive or bool(positive & {"all", "postgresql"})

    def change(self, path, wrapper):
        if not isinstance(wrapper.value, dict) or len(wrapper.value) != 1:
            raise ChangelogError("A change must have exactly one change type")
        kind, mark = next(iter(wrapper.value.items()))
        ex = self.extractor(path)
        if not isinstance(mark.value, dict):
            if kind == "comment":
                return
            raise ChangelogError(f"{kind} requires an object")
        applies = self.applicable(mark)
        if applies is None:
            self.warning(path, mark, "Dynamic dbms condition is not evaluated", "LIQUIBASE_DYNAMIC_PROPERTY")
            return
        if not applies or truth(mark.scalar("ignore")):
            return
        if kind != "changeSet" and "${" in str(mark.plain()):
            self.warning(path, mark, "Liquibase property substitution is unresolved; this change is not interpreted", "LIQUIBASE_DYNAMIC_PROPERTY")
            return
        if kind == "changeSet":
            key = f"liquibase:changeset:{path}#{mark.scalar('id', 'unknown')}:{mark.scalar('author', '')}"
            self.emit(path, mark, "MODULE", key, mark.scalar("id", "changeSet"), ex.scope, framework="LIQUIBASE", author=mark.scalar("author"))
            context = {"changeSetId": mark.scalar("id"), "changeSetAuthor": mark.scalar("author"), "sourceFormat": self.format(path)}
            for field in ("context", "contextFilter", "labels", "preConditions", "modifySql"):
                if mark.get(field):
                    self.warning(path, mark.get(field), f"{field} execution semantics are not evaluated", "LIQUIBASE_EXECUTION_CONDITIONS")
                    context[field] = mark.get(field).plain()
            if mark.get("rollback"):
                self.warning(path, mark.get("rollback"), "Rollback is kept separate from forward changes and is not executed", "LIQUIBASE_ROLLBACK")
            changes = mark.get("changes")
            if not changes or not isinstance(changes.value, list):
                raise ChangelogError("changeSet.changes must be an array")
            with ex.contextual(key, context):
                for change in changes.value:
                    self.change(path, change)
        elif kind in {"include", "includeAll", "sqlFile"}:
            if kind == "sqlFile" and str(mark.scalar("encoding", "UTF-8")).upper().replace("-", "") != "UTF8":
                self.warning(path, mark, "Only UTF-8 sqlFile input is supported", "LIQUIBASE_ENCODING")
                return
            if mark.scalar("endDelimiter", ";") != ";":
                self.warning(path, mark, "Custom SQL delimiters are not interpreted", "LIQUIBASE_DELIMITER")
                return
            for target in self.targets(path, kind, mark):
                self.references.add(target)
                self.edge(path, mark, ex.scope, "file:" + target, "DEPENDS_ON")
                if kind == "sqlFile" and not target.lower().endswith(".sql"):
                    self.warning(path, mark, "sqlFile must refer to an admitted .sql file", "INCLUDE_REJECTED")
                else:
                    self.visit(target, {**ex.context, "includedFrom": path}, ex.scope if kind == "sqlFile" else None)
        elif kind == "sql":
            sql = mark.get("sql")
            if not sql or not isinstance(sql.value, str):
                raise ChangelogError("sql requires a SQL string")
            if mark.scalar("endDelimiter", ";") != ";":
                self.warning(path, mark, "Custom SQL delimiters are not interpreted", "LIQUIBASE_DELIMITER")
                return
            with ex.contextual(None, {"sourceFormat": self.format(path) + "_SQL"}):
                ex.sql(sql.value, region=sql.region)
        elif kind in {"createTable", "addColumn"}:
            table = self.table(path, mark)
            if kind == "createTable":
                self.emit(path, mark, "TABLE", table, table.removeprefix("db:table:"), ex.scope, schema=mark.scalar("schemaName"), operation="CREATE", schemaResolution="EXPLICIT" if mark.scalar("schemaName") else "UNQUALIFIED")
            for column in self.columns(mark):
                self.column(path, column, table)
        elif kind in {"addDefaultValue", "dropDefaultValue", "addAutoIncrement", "addNotNullConstraint"}:
            table = self.table(path, mark)
            column = mark.scalar("columnName")
            if not isinstance(column, str) or not column:
                raise ChangelogError("columnName is required")
            owner = table.replace("db:table:", "db:column:", 1) + "." + column
            if kind == "addAutoIncrement":
                self.rule(path, mark, owner, "IDENTITY", "DATABASE_GENERATED", startWith=mark.scalar("startWith"), incrementBy=mark.scalar("incrementBy"))
            elif kind == "addNotNullConstraint":
                self.rule(path, mark, owner, "NOTNULL", defaultNullValue=mark.scalar("defaultNullValue"))
            else:
                values = {key: value.plain() for key, value in mark.value.items() if key.startswith("defaultValue")}
                self.rule(path, mark, owner, "DEFAULT", "DATABASE_DEFAULT", operation="DROP" if kind == "dropDefaultValue" else "SET", values=values)
        elif kind in {"addPrimaryKey", "addUniqueConstraint", "addCheckConstraint", "addForeignKeyConstraint"}:
            foreign = kind == "addForeignKeyConstraint"
            table = self.table(path, mark, "baseTableName" if foreign else "tableName", "baseTableSchemaName" if foreign else "schemaName")
            rule = {"addPrimaryKey": "PRIMARY", "addUniqueConstraint": "UNIQUE", "addCheckConstraint": "CHECK", "addForeignKeyConstraint": "FOREIGN"}[kind]
            key = self.rule(path, mark, table, rule, constraintName=mark.scalar("constraintName"), columns=mark.scalar("baseColumnNames" if foreign else "columnNames"), expression=mark.scalar("constraintBody"))
            if foreign:
                target = self.table(path, mark, "referencedTableName", "referencedTableSchemaName")
                self.edge(path, mark, key, target, "DEPENDS_ON")
        elif kind == "createIndex":
            table = self.table(path, mark)
            self.rule(path, mark, table, "INDEX", ownership="DATABASE_CONSTRAINT" if truth(mark.scalar("unique")) else None,
                      indexName=mark.scalar("indexName"), unique=truth(mark.scalar("unique")), columns=[column.plain() for column in self.columns(mark)])
        elif kind == "createView":
            table = self.table(path, mark, "viewName")
            key = table.replace("db:table:", "db:view:", 1)
            self.emit(path, mark, "DATABASE_VIEW", key, table.removeprefix("db:table:"), ex.scope, materialized=False)
            query = mark.get("selectQuery")
            if query and isinstance(query.value, str):
                with ex.contextual(key, {"sourceFormat": self.format(path) + "_SQL"}):
                    ex.sql(query.value, region=query.region)
        elif kind == "createProcedure":
            body = mark.get("procedureBody")
            if body and isinstance(body.value, str):
                with ex.contextual(None, {"sourceFormat": self.format(path) + "_SQL"}):
                    ex.sql(body.value, region=body.region)
            else:
                self.warning(path, mark, "createProcedure requires an inline SQL body in this subset", "LIQUIBASE_PROCEDURE")
        elif kind not in {"comment"}:
            self.warning(path, mark, f"Liquibase change {kind} is parsed but not interpreted", "LIQUIBASE_UNSUPPORTED_CHANGE")
